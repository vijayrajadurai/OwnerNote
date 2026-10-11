# OwnerNote — working rules

## Kai Chat: the text box and the mic are one Kai (owner's rule, 10 Oct 2026)

Every Kai Chat feature or fix must work the same typed and spoken. Both go to `KaiChatSession.send` → `KaiAgent.ask`;
the mic (phone speech-to-text, `ta-IN`) writes Tamil script — Tamil words, English loan words and names alike — and
`KaiSpokenWords.normalize` + `withNames` turn it into the Tanglish Kai's rules read before anything else runs.

When you change what Kai understands:

1. **A new Tanglish / English word in a rule** (a verb, a question word, a unit, a filler…) → add its spoken Tamil form
   to `KaiSpokenWords` (`"குடுத்தான்" to "kuduthaan"`), and the typed → spoken pair to the test helper `KaiMicForm`.
2. **Add the owner's line to `app/src/test/resources/kai/owner-lines.txt`.** `KaiOwnerSweepTest` plays every line twice —
   typed, and as the mic writes it (`KaiMicForm`) — and both must give the same books / stock / reminder result.
   A fix that works only typed fails that test. Multi-turn conversations (`talk(...)` in `conversationLines`) are
   played both ways too — a reply check that looks for a Tanglish word must also accept Kai's Tamil reply to the mic.
3. If the line is something only said aloud, add it in Tamil script as well (`KaiVoiceOwnersTest` and owner-lines).
4. **Product names are never listed one by one.** The mic writes an English product name in Tamil letters
   ("பிரிட்டானியா பிஸ்கட்"); `KaiProductSound` matches it to the shop's own product by sound, as a whole phrase, before
   `normalize`. A name it misses is fixed in that matcher's rules (with a line in `KaiProductSoundTest`), not with a word pair.

When a rule works from a list (places, off-topic subjects, units, payment modes, money words, weekdays), test a word that is
NOT in the list too (owner, 10 Oct 2026: "hardware" was answered, "spa" wasn't). A word Kai can't read is asked about or
said plainly — never turned into a different answer (an unknown day is never "today", an unknown unit never the default unit).

Kai never says "Save aagiduchu" before the database confirms the save; nothing is written before Confirm.
