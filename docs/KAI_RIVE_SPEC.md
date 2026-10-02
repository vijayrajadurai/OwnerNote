# KAI — Rive rig specification

KAI is OwnerNote's AI Business Companion. This is the brief for the animator
who builds KAI's Rive file. The app code is already done: drop the finished
file into the app as `app/src/main/res/raw/kai.riv`, and KAI goes live
everywhere with no code changes. Until the file is there, the app shows KAI's
portrait with whole-body motion only.

## Art — do not redesign

Rig the **existing** KAI exactly as he is in the character sheet and
`app/src/main/res/drawable-nodpi/img_splash_welcome.webp`:

- same face, black hair and hairstyle
- same round body proportions
- white shirt, green vest with the OwnerNote chart logo

Do not create a different character. Default pose: front / slight 3/4 view,
full body (head to shoes, as in the turnaround), transparent background,
portrait artboard 4:5 (e.g. 800×1000) — the app shows KAI in a 4:5 frame.

Split into parts for bones/meshes: head, hair, eyes (whites, irises, lids),
eyebrows, mouth, torso, left arm + hand, right arm + hand, tablet.

## File contract (names must match exactly)

| Item | Name | Type | Values |
|---|---|---|---|
| Artboard | `KAI` (or the default artboard) | — | — |
| State machine | `KAI` | — | — |
| Input | `state` | Number | 0–18, see table below |
| Input | `mouth` | Number | 0–100: voice loudness, updated about 30× per second |
| Input | `lookX` | Number | −30…30: glance left/right (small) |
| Input | `blink` | Trigger | fired every 2.4–5.6 s |
| Input | `reducedMotion` | Boolean | true → keep poses, drop looping motion |

### `state` values

| # | State | Face | Body / hands | Loop |
|---|---|---|---|---|
| 0 | IDLE | neutral-friendly, occasional eye drift | breathing, tiny head/body sway | yes |
| 1 | GREETING | smile | friendly wave or open hand (as on the splash) | once, then settles |
| 2 | LISTENING | focused eyes, slight smile | leans slightly forward, hand near ear | yes |
| 3 | PROCESSING | eyes up/side, one brow up | head tilt, hand to chin | yes, slow |
| 4 | SPEAKING | mouth driven by `mouth`, smile, brows move | small explaining hand gesture, head moves | yes |
| 5 | CREDIT | happy + confident, brow raise | hand moves upward, green ₹ sign | once, hold |
| 6 | DEBIT | calm, explanatory, slightly serious (never an error face) | hand forward/down, orange ₹ sign | once, hold |
| 7 | SUCCESS | smile + blink | thumbs up, small celebratory move | once |
| 8 | REMINDER | friendly-serious | turns slightly, raises one finger, bell | once, hold |
| 9 | INSIGHT | confident, brow raise | points to the side (at a chart) | once, hold |
| 10 | FUNDING | confident | small "idea" gesture, points to the side | once, hold |
| 11 | CLARIFY | concerned-but-friendly | small head shake, open palm | once |
| 12 | HAPPY | big smile, proud | positive open-hand gesture | once, hold |
| 13 | CONFIDENT | calm confident smile | hands in pockets, small nod | once, hold |
| 14 | SURPRISED | brows up, round mouth | short step back, hands open | once, then explains |
| 15 | THOUGHTFUL | eyes up, brow raised | hand to chin, slow head tilt | loop |
| 16 | CONCERNED | gentle worried brows | slight head tilt, gentle hand | once, hold |
| 17 | SERIOUS | steady, direct eye contact (never angry) | still body, small hand emphasis | once, hold |
| 18 | ERROR | nervous, apologetic | hands together | once |

The app returns KAI to IDLE after GREETING (3.2 s), SUCCESS (1.8 s),
CLARIFY (2.6 s) and REMINDER/INSIGHT/FUNDING (5 s). Make those states read
well within that time.

### Lip-sync (`mouth`)

Blend four mouth shapes from `mouth`:

- 0–12: closed
- 12–38: slight open
- 38–66: medium open
- 66–100: wide open

Keep the transitions soft (a 60–80 ms blend) so the mouth doesn't flicker.
`mouth` is only above 0 while KAI's voice is audible.

### Blink and gaze

- `blink`: close and open the lids in about 150 ms. It must work in every state.
- `lookX`: move the irises only, a few pixels.

Blink and gaze must layer on top of every state (use a separate layer in the
state machine).

### Micro-expressions

These must combine (layer) with the states:

- blink
- look left/right
- brow raise/lower
- smile/neutral
- wink
- nod yes
- shake no
- slight surprise

## Quality bar

- Premium and calm: no bouncing, spinning, neon or particles, never childish.
- Transitions of 250–450 ms, eased. No hard cuts between states.
- 60 fps on a mid-range Android phone. Keep meshes light; the target file
  size is under 400 KB.
- With `reducedMotion` = true: poses only, no loops, and blinks at most.

## Test checklist (in the Rive editor)

- Step `state` through 0–11. Every state reads clearly on a 56 dp and a 190 dp view.
- Sweep `mouth` 0→100 while in SPEAKING: 4 clean mouth shapes.
- Fire `blink` in every state.
- Set `reducedMotion` in IDLE: no looping motion.
