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
