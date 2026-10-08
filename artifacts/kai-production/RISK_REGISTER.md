# Risk register (from inspection, before changes)

| Id | Severity | Risk | Root cause | Plan |
|---|---|---|---|---|
| R1 | Critical | Pesunga voice/text answers payments and business questions with a second engine (`KaiBrain.hear`) | `KaiIntents.handledByKai` only lists reminders / stock / chat / learning / morning | Route every Pesunga intent through `KaiAgent` (one core; confirm in Kai Chat as reminders already do) |
| R2 | Critical | An owner nickname for one of two same-named people ("Kumar House" vs "Kumar Anna") loses its identity | `KaiPrivateMemory.apply` rewrites the nickname to the record's *name*; the resolved id (`AppliedMemory.entities`) is dropped in `KaiMemoryAssistant.apply` | Carry the resolved canonical id through the turn; `KaiEntityResolver` takes it as the strongest evidence (priority: owner reference > phone > place > context > ask) |
| R3 | High | Same-named records are labelled by city only; owner references are not used as labels | `KaiEntityResolver.label` knows city / details / phone only | Label by the owner's reference first |
| R4 | High | Reminder "saved" is not read back | `KaiReminderAssistant` trusts `createReminder`'s result | Read the reminder back from the engine before saying it is set |
| R5 | Medium | "Ask My Business" screen answers from the backend engine, not Kai's books | separate legacy screen | Documented; out of this branch's code (UI-only change, can't be compiled here) |
| R6 | Medium | Teaching a nickname for an ambiguous name stores a plain word | `KaiTeaching.entityIn` returns null for two same-named records (correctly), so the phrase becomes a WORD | Learn references through the explicit pick (existing "Remember: X = Y" button / owner choice) |
| R7 | Low | `AppKaiMemoryAccess.entities()` 60 s cache | caching | Documented |
| R8 | Info | JVM harness cannot compile Android files (`AppKaiTools`, `VoiceEntryScreen`, `RepositoryKaiBooks`, `KaiBrain`) | environment | Reviewed by hand; Pixel 8 = BLOCKED |

## Status after this branch

| Id | Status |
|---|---|
| R1 | **Fixed (code)** — every Pesunga intent → `KaiAgent` (`KaiIntents.handledByKai = true`). Android screen path reviewed by hand, not device-tested |
| R2 | **Fixed + JVM-tested** — canonical id carried per turn; resolver priority owner reference first (`KaiOwnerReferenceTest`) |
| R3 | **Fixed + JVM-tested** — labels by owner reference, then place / shop / phone |
| R4 | **Fixed + JVM-tested** — reminder read back via `KaiTools.reminderStored` before "remind pannuren" |
| R5 | Open — Ask My Business screen unchanged |
| R6 | **Fixed + JVM-tested** — references learned from explicit context, from a pick, and re-learned after a correction |
| R7 | Open — 60 s entity cache unchanged |
| R8 | Open — Android not compiled / run here; **Pixel 8 = BLOCKED** |
| New | Payment direction / lexicon / stock / add-entry / duplicate-choice bugs found by the matrix — fixed (see FAILURES.md F5–F12) |
