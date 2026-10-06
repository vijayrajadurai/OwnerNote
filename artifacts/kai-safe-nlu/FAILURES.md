# Kai Safe NLU — Failures, Pre-existing Issues, Open Decisions

## Caused by this task
**None.** All 539 baseline tests still pass, with identical per-suite counts. The 75 new tests pass.

## PRE-EXISTING (present on main before this task)

| # | Issue | Status |
|---|---|---|
| P1 | The casual variants "un name enna", "saptia", "saptya", "saaptiyaa" and "hmm" made Kai ask to *learn* the word ("`saptia` nu sonnadhu enna meaning-la?"). | **Fixed** (spelling families in `KaiSmallTalk`) |
| P2 | "saptya Kai?" was answered as a plain hello. | **Fixed** |
| P3 | "dei Kai … tired ah iruka?" got the owner-is-tired answer. | **Fixed** (new STATUS kind) |
| P4 | Tamil script "உன் பேர் என்ன?" got "clear-ah sollunga", and "நீ யாரு?" got "what does நீ mean?". | **Fixed** (Tamil-script families + 9 Tamil pronouns in `KaiLexicon`) |
| P5 | Pesunga voice screen: unrecognised casual spellings went to the older `KaiBrain.hear`, not KaiAgent. That is a text/voice difference. | **Fixed indirectly.** The same `kindOf` now recognises them, so `classify` returns CHAT and they go to KaiAgent. No screen code changed. |
| P6 | "Colgate stock 20 vandhiruku" → "athula 5 pochu" was read as a new product called "Athula". | **Fixed narrowly.** It applies only when no product is named, the sentence has a reference word, no person is named, and the result resolves to the last product. It is still a draft that needs Confirm. |
| P7 | 17 Android/Robolectric unit-test classes cannot run in this environment (no Android SDK). | **BLOCKED.** Run `./gradlew :app:testDebugUnitTest` on your machine. |
| P8 | `KaiChatScreen.send` ignores a new message while Kai is still "thinking" (≈650 ms). A voice result that arrives in that window is dropped without a reply. | **Not changed.** It is UI code and can't be verified on a device here. Logged as a known limitation. |

## Open decisions for the owner (not changed, to keep zero regression)

1. **Eating reply wording.**
   - The spec's example is "Naan AI Owner 😄 enakku saapadu thevai illa. Neenga saaptingala?".
   - The current reply is "Saapten Owner 😄 Neenga saaptingala?", and it is **asserted by 3 existing tests** (KaiFinalFixesTest ×2, KaiProductionFixesTest ×1).
   - The spec forbids changing tests just to get green, so it was kept. If you want the new wording, approve it and I will change the reply and those 3 assertions together.
2. **Unknown-input wording.**
   - The spec suggests "Owner, idha konjam clear-ah sollunga. Enna panna sollreenga?".
   - The current "Owner, konjam clear-ah sollunga." is asserted by KaiBusinessBrainTest, so it was kept. It is already non-blank.
3. **"kai vali" (hand pain)** is still answered as a hello ("Vanakkam Owner… Enna help venum?"), because "Kai" is the assistant's name. It is harmless and writes nothing. A dedicated answer is optional.

## Stop conditions checked

None were hit:
- no payment, reminder or Smart Reminder routing change;
- no personal-memory or context breakage;
- no schema change;
- no dependency added;
- no LLM or key;
- no rewrite.

The only routing change touching business is P6, which is narrow, draft-only and tested.
