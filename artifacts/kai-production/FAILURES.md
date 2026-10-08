# Failures — found, fixed, and still open

## Found in this branch and fixed at the root

| # | Symptom | Root cause | Fix (file) |
|---|---|---|---|
| F1 | "Kumar House enaku 600 tharanum" asked "endha Kumar?" though the owner taught "Kumar House" | `KaiPrivateMemory.apply` added every saved alias to `AppliedMemory.entities`, said or not → two ids for "Kumar" → pin dropped | record an alias only after its phrase was replaced (`memory/KaiPrivateMemory.kt`) |
| F2 | "Kumar evlo tharanum?" answered Kumar House just because it was discussed last | context step applied to a bare name the owner said | context not used for a bare name when the owner keeps those records apart by references; still used for "avan" (`KaiAgent.resolveParty`, `ownerWords`) |
| F3 | Save line / draft said "Kumar" for Kumar House; teaching reply said "`Kumar Bro` = Kumar Bro" | labels taken after the new reference was stored; plan name only | `ownerName()` / `chosenLabels` on cards and save lines; record's own label for the teaching reply (`KaiAgent.kt`) |
| F4 | "Rendu Kumar-oda balance sollu" asked which | "rendu <name>" not read as "all of them" | `bothOf(name)` in `KaiBusinessBrain.personAnswer` |
| F5 | "Kumar paid 500" drafted Credit (owner gave Kumar money) | `paid` only in the owner-paid list | payer-first rule (`tools/KaiCommands.kt`) |
| F6 | "Kumar -500 kuduthaan" saved ₹500 | sign dropped by `amountsIn` | minus amount → asked (`KaiCommands.kt`, add-entry path) |
| F7 | "GPay pannitaan", "anuppitaan", "UPI la anuppinaan", "pay pannitaan", "return pannitaan", "collect pannitten", "kuduthutaru", "kuduthuttan", "குடுத்தான்" → no draft; "Ramesh-ku 400 pay pannitten / GPay pannen" → no draft | gaps in the one payment lexicon | lexicon extended in place (`KaiCommands.kt`) |
| F8 | "ainnooru kuduthaan" → no draft | STT spelling not in the Tamil number table | spellings added (`KaiUnderstanding.kt`) |
| F9 | "Kumar kitta irundhu 500 vandhuchu", "Received 500 from Kumar" → "payment-ah, stock-ah?" | stock-in words ("vandhuchu", "received") won over a named person with plain money | no stock word / unit + a known person → the payment path (`KaiAgent.stockChange`) |
| F10 | "Colgate evlo irukku?" → "Colgate-nu customer record illa"; "How much Colgate in stock?" → "Colgate in isn't in your inventory" | quantity question without the word "stock" went to people; product name included the word "in" | a known product + quantity question + no person → stock; the books' product name preferred (`KaiAgent`) |
| F11 | "Lokesh-ku 500 add pannu" answered the balance; nothing drafted | add-request handled only without an amount | add-entry draft on the record's side (`KaiAgent`, `PaymentRequest.addEntry`) |
| F12 | "Lokesh 500 kuduthaan" → "Endha Lokesh Owner?" — a typed / spoken "Chennai" was not understood; a voice owner never heard the choices | the payment choice existed only as buttons | choices spoken in the reply; `planChoice` lets a typed / spoken pick act like the button (`KaiAgent`) |
| F13 | Reminder "remind pannuren" said without checking the store | trusted `createReminder` | `KaiTools.reminderStored` read-back (`KaiReminderAssistant`, `AppKaiTools`) |
| F14 | Pesunga payments / questions / calculator used a second engine | `KaiIntents.handledByKai` listed only some intents | all Pesunga intents → `KaiAgent` (`KaiIntents.kt`) |

## Still open (not fixed in this branch)

| # | Item | Why open | Impact |
|---|---|---|---|
| O1 | **Pixel 8 / device validation** | no Android SDK / device in this cloud environment | Collect / Pay / Transactions / Dashboard screens, real DB writes through `AppKaiTools`, alarms, notifications, Pesunga mic: **unverified** |
| O2 | Android files changed but not compiled here: `AppKaiTools.kt` (1 line), and earlier `KaiIntents` behaviour seen through `VoiceEntryScreen` | harness compiles `brain/` only | reviewed by hand; needs a Gradle Android build |
| O3 | "Ask My Business" (More menu) still answers from the backend engine (R5) | separate legacy screen; UI change can't be compiled/verified here | a second answer path exists outside Kai Chat / Pesunga |
| O4 | `AppKaiMemoryAccess.entities()` 60 s cache (R7) | unchanged | a party created < 60 s ago may not resolve a nickname yet |
| O5 | Understanding is lexicon + grammar rules (no AI) | by design (no paid AI / no new LLM) | new spellings / phrasings outside the lexicon are asked, not guessed — safe, but can need another rule |
| O7 | A bare day ("15th") is asked "this month or next?" | existing design | one extra question |

## Owner-approved design (kept as is)

| Behaviour | Decision |
|---|---|
| "seri" / "ama" saves the open draft also after a detour (calculator, a balance answer) — as long as Kai's last reply did not ask the owner something else. If it did (a "which Lokesh?" choice, a missing time, Save for a word, "neenga saptingala?"), "seri" answers that question and the draft waits for "confirm" / "save pannu" / the button | Owner asked for this (8 Oct 2026). Locked by `KaiProductionMatrixTest.j8_…` (detour → "seri" saves) and `j8b_…` (Kai's question → "seri" does not save), plus the existing `KaiContextFollowupTest` / `KaiHumanUnderstandingTest` stale-"ama" tests |

## Found on the Pixel 8 by the owner (8 Oct 2026) — fixed on `feature/kai-device-fixes`

| # | Owner saw | Root cause | Fix |
|---|---|---|---|
| D1 | "naan Selvam ku 3000 tharanum" → "nalaiku" → Kai only repeated it; "add pannu" was another step | the due-date answer ended the turn without a draft | `dueDateResolved` now makes the draft at once: "…naalaikku kudukkanum. Add pannalama?" + Confirm card |
| D2 | "Suresh gpay la 5000 pay pannan" → "innaikku UPI la edhuvum nadakkala" / asked what "pannan" means | 3rd-person past "pannan / pannitaan …" after pay / gpay / upi / transfer not in the payment lexicon | one rule for "<pay word> pann(an/aan/itaan/…)" (person paid) and "pann(en/itten/…)" (owner paid) in `KaiCommands` |
| D3 | "Praba thambi enakku 6000 tharanum" → merged into Praba | a relation word after a known name was dropped | relation words (thambi, anna, akka, amma, magan, wife …) are asked: "Praba dhaan-aa, illa Praba-oda thambi (vera aal)-aa?" — typed / spoken / button answer; "Remember" offered |
| D4 | due date "tomorrow" asked for a meaning | spelling variants not in `KaiTime` (tmrw, tomorow, tommorow, nalaiki …) | added |
| D5 | "Chennai Lokesh ten thousand" → ₹1,000; "Madurai Ravi" saved as "Madurai" | "thousand" read alone as a Tamil scale word; a new person's name cut to its first word | English number run wins; two capitalised name words kept for a new person |
| D6 | "Colgate evlo irukku?" → "customer record illa" | a name that is neither a product nor a customer fell to the people answer | said plainly: "X-nu product-um illa, customer-um illa" (a known product answers its stock) |
| D7 | "today yar payment tharanum" → "Yar kitta evlo vaanganum?" | "yar" spelling read as a person's name | yar / yaru / yarukku added as question words |
| D8 | English reply to a Tanglish sentence with "pay" in it | one English word made the whole sentence English | Tanglish words win |
| D9 | after a payable draft, the follow-up direction could flip | the draft path stored cash-flow direction, the stated path stored who-pays | one meaning: IN = owner receives, OUT = owner pays |
| D10 | "add pannitiya?" with a draft open showed no Confirm | the reply had no card | the same draft card is shown again |
