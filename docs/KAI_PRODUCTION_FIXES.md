# Kai — production bug fixes + conversational brain

## Root causes

| Bug | Root cause | Fix |
|---|---|---|
| "Colgate stock vandhiruku add pannu" → "Colgate isn't in your inventory" | `KaiStock` knew only a few stock-in words ("vandhirukku" with two k's, no "vandhiruku", "new stock", "pudhu stock", "inventory-ku podu") and **required a number**. Without one the sentence fell through to the stock *question* path (`KaiCommand.Stock`), which looked the name up and answered "not in your inventory". A product that really wasn't there had no create path at all. | `KaiStock` reads every stock-in / stock-out phrase, number words ("rendu") and an optional quantity. Product known + no number → Kai asks how many (camera offered). Product unknown → STOCK_IN_CAMERA: the camera opens at once and the photo fills an editable form. Stock out of an unknown product → "Product create pannanuma?" |
| Scan Bill → no camera | Kai's `scan()` only returned a card with a "Scan bill" button, and that button navigated to Pesunga, where the owner had to pick Shop bill / tap Camera again. Nothing opened the camera. | `KaiTurn.direct` = a phone action the screen performs at once. "bill scan pannu" → `OPEN_BILL_SCANNER` → `Routes.VoiceEntryScan` → `DocumentCaptureSection(cameraRequest)` opens the camera (permission check first; refused → "Owner, bill scan panna camera permission venum." + **Allow Camera**). |
| "2 minutes la Ruthran-ku call pannanum reminder pannu" asks for a time | The phone's speech-to-text listens in **Tamil (ta-IN)** and writes Tamil script: "2 நிமிஷத்துல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு", "டூ மினிட்ஸ்ல …". `KaiTime` only knew the exact words "நிமிஷம்/நிமிடம்" after digits, so no time was found and Kai asked "Ethana manikku?". Tamil-script names also broke the person rules (Tamil vowel signs aren't `\p{L}`). Typed Tanglish worked. Also: a follow-up answer "10 minutes la" was rejected (only clock times were accepted), "2 mani nerathula" was read as 2 o'clock, and time words became names ("Naalaik", "Manik"). | `KaiSpokenWords.normalize` maps the command vocabulary of Tamil script (numbers, time words, verbs, English loan words, "-க்கு" names) to Tanglish before any rule runs — one brain for voice and text. `KaiTime` units widened; follow-ups accept relative times; person rules fixed. |
| Separate "My Kai language" screen | Added in the previous version (`ui/kaimemory/KaiMemoryScreen.kt`, `Routes.KaiMemory`, a Kai Chat header button). | Removed. Learning is inline in Kai Chat ("'ramba' nu enna meaning?"). |
| "saaptiya?", "enna panra?" → "konjam clear-ah sollunga" | Every non-command message went to the Business Brain, which only answers business questions. | `KaiSmallTalk`: whole small-talk messages get a short friendly answer; business words / amounts never count as small talk. |

## Intent priority (voice and text alike)

explicit button → pending answer (reminder time / stock quantity / learning) → reminder →
stock in → stock out → bill scanner → call → money → business question → calculator →
conversation → unknown word (Kai asks and learns, owner-only, after yes).

`KaiIntents.classify` is the same order; Pesunga sends reminders, stock, the bill scanner
and small talk to the same Kai Chat conversation.

## Safety

- Money and stock: draft → Confirm (Edit / Cancel) → existing engine. Photo-read product
  details are an editable form; nothing is saved before **Confirm Stock In**.
- Learning: saved only after the owner says yes, for that owner only
  (`kai_memory/<business>__<owner>.json`). A money / stock meaning is never a plain word.
- Reminders only remind: "Owner, Ruthran-ku call panna sonneenga." with **Call Ruthran**
  (opens the dialer) and **Dismiss**. Kai never says it called.
- Action log: intent, tool, the owner's words (text — never audio), result, status,
  time, reference, owner (`STOCK_IN`, `STOCK_OUT`, `STOCK_IN_CAMERA`, `CREATE_PRODUCT`,
  `OPEN_BILL_SCANNER`, `CREATE_REMINDER`, `CALL_CONTACT`, `SMALL_TALK`).

## Tests

`app/src/test/java/com/shopai/app/brain/chat/KaiProductionFixesTest.kt`,
`app/src/test/java/com/shopai/app/util/ProductLabelReaderTest.kt`.

## Real-device acceptance (to run on a phone — not done in the build sandbox)

1. Kai Chat: "Colgate stock vandhiruku add pannu" → "Colgate evlo vandhiruku?" → "12" → Confirm → "Done Owner ✅ Colgate stock-la 12 pieces add panniten."
2. "Pepsodent stock vandhiruku" (not in inventory) → camera opens at once → photo → form filled → edit → Confirm Stock In → product + stock in Inventory.
3. "2 Pepsodent pochu" → "Product create pannanuma?" [Create Product] [Cancel].
4. Tap Scan bill (card / "bill scan pannu" / Pesunga) → camera opens with no extra tap.
5. Deny camera permission → "Owner, bill scan panna camera permission venum." → Allow Camera.
6. Mic: say "2 நிமிஷத்துல ருத்ரனுக்கு கால் பண்ணனும் ரிமைண்டர் பண்ணு" → no time question → notification after 2 minutes with Call / Dismiss → Call opens the dialer.
7. Type the same in Tanglish → same result.
8. "Kumar-ku reminder pannu" → "Eppo…?" → "10 minutes la" → set.
9. "saaptiya?", "enna panra?", "good morning" → friendly answers, spoken when asked by voice.
10. "'ramba' nu enna meaning?" → "romba nu sonna mari" → Aama → "innaiku ramba busy" understood.
11. Log out, log in as another owner → "ramba" is not known.
12. Kai Chat header has no "My Kai language" button.

## Final production fix (one router, every language)

- Router (`KaiIntents.classify`, same order as `KaiAgent.ask`): LEARN_SLANG → MORNING_WORK →
  CREATE / LIST / CANCEL / UPDATE_REMINDER → SCAN_STOCK → STOCK_IN → STOCK_OUT → SCAN_BILL →
  CALL_CONTACT → FINANCIAL_ENTRY → PAYMENT / CUSTOMER / SUPPLIER / BUSINESS_QUERY → CALCULATE → CHAT → UNKNOWN.
- Reminders ask only the missing field: "2 minutes la remind pannu" → "Enna remind pannanum?" (time kept);
  "Ruthran-ku call remind pannu" → "Eppo remind pannanum?". The reminder card shows Reminder / What /
  When / Status with Cancel and Edit.
- Stock: "Colgate vandhuduchu", "New Colgate stock", "Colgate rendu sell panniten", "Colgate 2 pieces out",
  "2 pieces Colgate kuduthuten" (stock out only when a product is named and nobody is given it — otherwise it is
  a payment). "Colgate photo edu", "stock photo edu", "new stock add pannu" open the product camera (SCAN_STOCK).
  The photo form adds Pack size; the product name is brand + variant ("Colgate Strong Teeth"), the category is
  the product type ("Toothpaste").
- Bill draft also reads the invoice number and the tax; the form has Confirm / Cancel ("Owner, bill details
  ready … Review pannitu Confirm pannunga.").
- Learning: "'ramba' na romba" → "Seri Owner 😄 `ramba` = `romba` nu save pannava?" → aama → saved for this
  owner only. "puli = customer payment" → asked as a business meaning with its own buttons.
- Voice: Kai speaks only when the owner spoke (`KaiSpeech`); typed messages get text only.
- Tests: `app/src/test/java/com/shopai/app/brain/chat/KaiFinalFixesTest.kt`.
