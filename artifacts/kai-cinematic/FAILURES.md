# Failures / open items

| # | Item | Status |
|---|---|---|
| 1 | Pixel 8 run (t1–t7, locked and unlocked, fallback) | **NOT RUN** — no emulator in this environment. Status: INCONCLUSIVE. |
| 2 | GPU blend modes for the arm handover (DST_OUT / ADD in `saveLayerAlpha`) | software-verified only; check on the device |
| 3 | About 0.1 s arm dissolve mid-handover | asset limitation (flattened art) — needs a rigged or layered Kai |
| 4 | No real head rotation, leg steps or new arm poses | asset limitation |
| 5 | Art upscaled about 1.6× (slightly soft) | asset limitation — a ≥ 1500 px source would fix it |
| 6 | First matte cleanup about 200 ms (desktop JVM) | runs off the main thread once per process; Kai fades in from black meanwhile — check that the first ring isn't empty |
| 7 | `NaturalTtsSpeaker.stop()` now drops in-flight requests everywhere | intended; watch Kai Chat / Morning Work for any line that was meant to play after a stop |

The test run found no code failures: 539/539 JVM tests pass.
