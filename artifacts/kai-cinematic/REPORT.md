# Kai Urgent Action Mode — Cinematic Character Experience

Branch: `feature/kai-cinematic-urgent-action` (from `main` @ b5496a1). **Not committed, not pushed.**

## Overall classification

| Area | Result | Evidence |
|---|---|---|
| Code + JVM tests | **PASS** | 539/539 JVM unit tests (Robolectric tests not runnable here) |
| Android type-check | **PASS** | Activity, actor, voice, device test compiled against android-all 34 + Compose stubs |
| Kai scale / layout | **PASS (preview)** | `preview/*.png` — software render of the real mesh/acting code |
| Kai body animation | **PASS (preview), with an asset limitation** | `preview/kai-urgent-preview.gif` |
| Voice loop + stop on action | **PASS (unit tests)** | `KaiUrgentVoiceTest`, `KaiUrgentVoiceScriptTest` |
| **Pixel 8 emulator** | **INCONCLUSIVE — not run** | This cloud environment cannot run Android builds or an emulator (dl.google.com blocked, no /dev/kvm). Run `scripts\kai-reminder-pixel8.ps1` (t1–t7). |

The QA question — *"active character, or a static image with effects?"* — answered from the preview
renders: **active character.** The body moves (head, eyes, brows, shoulders, chest, hips, the arm and
the hand), there is no ring and no particle field, and the only effect is a faint floor light.
**This still has to be confirmed on the Pixel 8.**

## Round 4 — fixes for the R6 Pixel 8 report (43d7340)

R6 confirmed these work:
- unit tests: 665 pass, 0 fail;
- Kai visible at once on the 2nd+ ring (2.35 s on the first ring after install);
- Done → "சரி ஓனர்." in ~0.4 s;
- a smooth dhoti edge;
- the locked ring: full-screen, voice, Done.

| Reported | Cause | Fix |
|---|---|---|
| Ring → first word 5.5 s unlocked, 7.5 s locked | The voice loop started only after the screen's first composition (+1.8 s). MediaPlayer was created on the main thread, so its callbacks waited behind a 2 s main-thread stall while the first frames rendered (HWUI "Davey"). | The voice starts in `KaiReminderActivity.onCreate`, as the screen opens. Playback runs on its own thread ("kai-voice"): the WAV is read, MediaPlayer is created and prepared, and callbacks arrive there, so a busy screen no longer delays the voice. A playback still being set up when stop is pressed never starts. |
| Notification removed at +1.4 s, before the keyguard was occluded | It was removed in `onResume` (resumed ≠ visible over the lock screen). | It is removed in `onWindowFocusChanged(true)`, once Kai's window is really shown. |
| t1/t3: words or buttons "missing" although on screen | The test searched only `rootInActiveWindow`, which is the keyguard over the lock screen, and used old uppercase labels. | It searches every window (`FLAG_RETRIEVE_INTERACTIVE_WINDOWS`) and uses "Call now / Done / Snooze 5 min". |
| t6: "can not be called from the main application thread" | `urgentScreen()` (itself a `runOnMainSync`) was called inside `runOnMainSync`. | The activity is taken first, then recreated on the main thread. |
| t7: "Kai did not appear" with kaiVisibleAfterMs=-39 | Kai visible before the test started waiting counted as a failure. | Counts as 0 ms. t7 also reports `voiceStartAfterMs` (ring → audible). |
| t1 run 1: "no lock screen" | The emulator's keyguard locks a few seconds after sleep. | The test waits up to 6 s for the lock. |

## Round 3 — fixes for the Pixel 8 report (74b0659)

The Pixel 8 run on 74b0659 confirmed these work: the unlocked ring, the locked ring, the
`voice engine=sarvam` lines, the varied voice loop, Done stopping the voice at once, and the glowing stage.

| Reported problem | Cause | Fix |
|---|---|---|
| `testDebugUnitTest` failed to compile (0 tests ran) | `KaiMatteTest` used `javax.imageio`, which is not on Android's compile classpath. (My earlier "539 pass" came from a scratch JVM harness, not Gradle's Android unit tests.) | The real-art test was removed. A synthetic "pixel stairs are smoothed" test replaces it. No other test uses javax/awt. |
| Stage empty for ~4 s; Kai appears at +5.7–6.8 s | The cleaned Kai art was first built when the screen opened (slow on the emulator). Kai's arrival had already "played" on an empty stage. | (1) `KaiStageArt.preload` runs at app start; an alarm also starts the app. (2) The cleaned art is saved to `files/kai_stage_v2/` once, so later starts only decode three PNGs. (3) Kai's performance starts when his picture is ready, never mid-intro. (4) `KaiMatte` fill loop made faster. |
| Each Sarvam line took 3.6–8.3 s; first line ~11 s after the ring; "சரி ஓனர்." 3 s after Done | Every line was fetched from the proxy at the moment it was spoken. | `NaturalTtsSpeaker.prefetch` + a disk cache (`cache/tts_cache`, max 120 lines). The reminder's lines, the next attempt's opening and the three short answers are fetched when the reminder is **saved** (`KaiReminderEngine.prepareVoice`) and again when it rings. Kai then speaks at once, and the answer plays instantly. A line still being fetched is awaited, not requested twice. |
| t1/t3/t6/t7 failures (screenshot timing, `kaiBox=Rect(0,0-0,0)`, thread) | Screenshots were taken before Kai was drawn. The Kai node had no accessibility bounds. Voice state was read off the main thread. | Tests wait for `KaiUrgentDebug.kaiVisibleAt` before screenshots (and report `kaiVisibleAfterMs`). Kai's box comes from `KaiUrgentDebug.kaiBounds` (the laid-out stage). Voice/speaker state is read on the main thread. |
| Jagged staircase on the dhoti's left edge | The cut-out's pixel steps were kept; only 2 px were feathered. | The outline is smoothed (blurred mask → smooth alpha). Body notches are closed up to ~20 px. See `preview/04-dhoti-edge-before-after.png`. |

Not reproduced here (no emulator): the exact t3 "accessibility tap" failure. If t3 still fails, its exact
message is needed.

Cost note: prefetching uses about 9 short Sarvam requests per reminder. They are cached, so a repeated
line (the same person or the same answers) is never fetched again.

## Round 2 — after the owner's Pixel 8 test

The owner confirmed these work:
- full screen with the screen on and off;
- the cinematic action;
- Kai saying different lines each time.

They asked for two changes:

1. **Voice robotic → natural Sarvam voice.** Kai now speaks Tamil script to the Sarvam Tamil voice.
   See VOICE_TEST.md for the cause and the fix.
2. **The glowing background is back.** The first version's stage is restored behind the large Kai:
   - a green energy field (breathing with him);
   - a slow orbit arc;
   - 26 drifting light particles;
   - one pulse as he notices the owner;
   - the floor light.

   It is centred on his upper body and sized to the screen width. See `preview/03-glowing-stage.png`.

## What changed

1. **Kai fills the screen.** In portrait he takes everything above the words, head to sandals, never
   cropped. On a Pixel 8 that is about 70 % of the height. Landscape puts Kai on the left and the text
   on the right. He is drawn from the high-resolution full-body art (620×998), not the small 265-px
   "curious" pose.
2. **The body acts** (`KaiActing`, a pure function of the clock):
   - **0–1 s, arrival:** out of the dark, 93 → 100 %, rising 22 dp, landing on both feet. No bounce.
   - **1–2 s, notice:** his eyes reach the owner first, then his head turns; brows and shoulders lift ("Owner…").
   - **2–4 s, reminder:** the open hand comes out toward the owner, the palm moves, he leans in, he nods.
   - **Idle:** breathing (3.7 s); a new head position every 5–8 s; weight from one leg to the other
     every 7–12 s; a hand gesture every 10–16 s; a glance every 6–10 s; blinks every 2.4–5.8 s.
     The timing is seeded per ring, so it is organic but bounded. Each attempt makes him slightly
     more insistent, with a cap.
   - **With the voice:** a nod and raised brows as each line starts, eye contact while speaking, the
     open hand on lines that ask, lip-sync, and small palm movements.
3. **New mesh motion** (`KaiMesh`): head turn (the face slides across the head), brow lift, shoulder
   lift, weight shift over planted feet, forearm swing around the elbow, and palm wave around the
   wrist. All are zero by default, so the other Kai screens are unchanged.
4. **Real arm gesture:** the hands-in-pockets art (FULL) hands over to the open-hand art (POINT) only
   inside the arm's region (`KaiArt.gestureMask`). Both arms rotate while they hand over. The head and
   body stay as one picture.
5. **Matte cleanup** (`KaiMatte`, runs once in memory; the art files are not touched): removes the pale
   studio edge ring, specks, the opaque floor patch under the sandals, and notches in the sleeve and
   dhoti. It also adds a soft 2-px edge. On black, the raw cut-out looked jagged when shown large.
6. **Compact controls:** `[☎ Call now] [✓ Done] [⏰ Snooze 5 min]` sit in one row as 48 dp pills.
   Call is green, Done is dark and Snooze is quiet. The text is smaller: headline 22 sp, question 15 sp.
7. **The voice keeps reminding** (`KaiUrgentVoice` + `KaiUrgentVoiceScript`). See VOICE_TEST.md.
8. **Stop means stop** (`NaturalTtsSpeaker`): `stop()` now also drops requests still being fetched, so
   queued audio can never play after Done. Effect on other screens: their `stop()` is now stricter in
   the same way.
9. **Removed:** the orbit ring and particle field (`KaiUrgentStage.kt`).

## Asset limitation — stated honestly

Kai is two **flattened** images (FULL and POINT). They have no layers, no skeleton and no Rive file
(`res/raw/kai.riv` is absent, although `KaiCharacter` already supports one; see `docs/KAI_RIVE_SPEC.md`).

- **Possible now, and done:** head turn and tilt, nod, brows, glance, blink, jaw and lip-sync, shoulders,
  breathing, weight shift, sway, lean, the arm moving between pocket and open hand, and palm movement.
- **Not possible with this asset:**
  - a real 3D head turn or a profile view;
  - arm poses other than these two (waving, pointing at a phone);
  - finger articulation;
  - leg steps (walking in);
  - facial expressions beyond brows and mouth (the face always smiles).
- **Visible compromises:**
  - About 0.1 s in the middle of the arm handover is a dissolve between the two arms, not true limb motion.
  - The 620×998 art is shown about 1.6× on a Pixel 8, so it is slightly soft.
- **For truly "movie-level" acting:** a rigged Kai (Rive with bones, per `KAI_RIVE_SPEC.md`), or a
  layered source (head, torso, upper arm, forearm, hand, legs, towel; ≥ 1500 px tall), or pre-rendered
  alpha video loops for arrive / gesture / idle.

## Files

New:
- `ui/reminder/KaiActing.kt`
- `ui/reminder/KaiUrgentActor.kt`
- `ui/reminder/KaiUrgentVoice.kt`
- `ui/reminder/NaturalVoiceOut.kt`
- `ui/kai/KaiMatte.kt`
- `brain/tools/KaiUrgentVoiceScript.kt`
- tests: `KaiActingTest`, `KaiUrgentVoiceTest`, `KaiUrgentVoiceScriptTest`, `KaiMatteTest`

Changed:
- `KaiMesh.kt`
- `KaiCharacter.kt` (`drawLid` made internal)
- `KaiUrgentMotion.kt` (+ its test)
- `KaiReminderActivity.kt`
- `NaturalTtsSpeaker.kt`
- `AppContainer.kt`
- `KaiMeshTest.kt`
- `KaiReminderPixelTest.kt` (new t7)
- `scripts/kai-reminder-pixel8.ps1`

Deleted:
- `ui/reminder/KaiUrgentStage.kt`
