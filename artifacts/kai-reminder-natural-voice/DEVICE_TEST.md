# Real device test (A / B / C): Pixel 8 emulator or a real phone

> I could not run this from the cloud: there is no Android SDK and no device here. Run it on the PC and send me the results.

## 0. Before you start

1. The branch must be on the PC. Today it is **only in the cloud and not pushed**, because the review comes first.
   - After you say "push pannu", I will push the feature branch only. main and master stay untouched.
   - Then, in Android Studio's Terminal:
     ```
     git fetch origin
     git switch feature/kai-reminder-natural-voice
     ```
2. Install the app: Run ▶, or `.\gradlew :app:installDebug`. App data is kept.
3. Turn the phone's media volume up.
4. Optional: keep the log running in a second terminal.
   ```
   adb logcat -s KaiReminder:I NaturalTtsSpeaker:I
   ```
   - Lines to watch for:
     - `voice turn: 3 sentences joined in … ms` means the three sentences were joined into one clip.
     - `voice turn engine=sarvam` means the natural voice was used. `device` means the fallback was used.

## A. Kumar call reminder in 2 minutes

1. In Kai Chat, type or say: `Kumar-ku 2 minutes-la call panna remind pannu`
2. Kai asks "… reminder set pannalama?". Press **Confirm**.
3. Lock the phone and wait 2 minutes.
4. **Listen:**
   - "Owner... Kumar-ku call panna vendiya neram aachu." → short breath (~0.4 s) → "Call pannunga." → a little pause (~1 s) → "Call pannalama?"
   - ✅ No long silence between the sentences. It should not sound like the voice restarted.
   - ✅ About 6–7 s later: "Seekiram call pannunga Owner." The name is not repeated.
5. While a sentence is being spoken, press **CALL NOW**.
   - ✅ The voice stops at once.
   - ✅ Only "Seri Owner, Kumar-ku call screen open pannuren." is heard.
   - ✅ No sentence from before comes back afterwards.

## B. Son's school pickup in 2 minutes

1. Say: `2 minutes-la paiyana school-la irundhu kootitu vara nyabagam paduthu`
2. Check the confirmation card: the task is "paiyana school-la irundhu kootitu vara". Press **Confirm**.
3. **Listen** when it rings:
   - "Owner... neram aachu." → "Paiyana school-la irundhu kootitu vara vendiya neram." → "Kelambalama?"
   - With a clock time instead ("4 manikku"), the first sentence is "Owner... 4 mani aachu."
4. Press **DONE** in the middle.
   - ✅ It stops at once.
   - ✅ Only "Seri Owner." is heard.
   - ✅ Nothing plays after that.

## C. Amma call in 5 minutes

1. Say: `5 minutes-la Amma-ku call panna remind pannu` and press **Confirm**.
2. When it rings, listen to the first turn: one clip, natural, with short breaths.
3. Press **SNOOZE 5 MIN**.
   - ✅ The voice stops at once.
   - ✅ Only "Seri Owner, 5 minutes-ku remind pannuren." is heard.
4. Let it ring again after the snooze. If it isn't answered, a later attempt says it more directly, for example "Owner, Amma-ku innum call pannala."
   - The number of retries and the time between them are the same as before.

## Send me these results

| Test | Long silence between sentences? (yes/no) | Did Call/Done/Snooze stop it at once? | Did anything play afterwards? | `engine=` |
|---|---|---|---|---|
| A | | | | |
| B | | | | |
| C | | | | |

## Also tell me

- Does the voice sound choppy at the joins between sentences?
- Does any word sound odd (an English-style reading)? Write down those words.
