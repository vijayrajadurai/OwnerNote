# Visual test

The preview renders are **not device screenshots**. A software renderer ran the real `KaiActing`
and `KaiMesh` code and the real `KaiMatte` cleanup on the real art, using the same compositing as
`KaiUrgentActor`: FULL, then the arm mask (DST_OUT), then POINT's arm (ADD). The text and buttons
are a mock of the Compose layout.

| Check | Preview result | Device |
|---|---|---|
| Kai head-to-sandals, about 70 % of the height (412×915 dp frame) | ✅ `preview/01-*.png` | t7 asserts ≥ 55 % |
| Head and feet not cropped | ✅ | screenshot t7-1 |
| Arrival out of the dark, no pop or bounce | ✅ frames 0–1 s | video needed |
| Eyes, then head, to the owner; brows and shoulders lift | ✅ (subtle; best seen in the GIF) | video needed |
| Open hand comes out toward the owner (body, not background) | ✅ 1.6–2.6 s | screenshot t7 |
| Idle: breathing, weight shift, head moves, gestures, glances | ✅ `preview/02-*.png`, GIF | t7 `kaiBodyMotion > 0` |
| No large ring, no particles, no neon | ✅ only a faint floor light | — |
| Cut-out clean on black (no pale ring, floor patch or notches) | ✅ after `KaiMatte` | screenshot |
| Controls compact, one row, 48 dp | ✅ | t7 asserts 44–64 dp and one row |
| Reduced motion: Kai still (open hand), lip-sync only | unit-tested | manual |

Known visual issues:
1. About 0.1 s mid-handover dissolve of the arm (an asset limitation).
2. Slight softness at about 1.6× upscale.
3. The GPU blend modes (DST_OUT and ADD inside `saveLayerAlpha`) have only been checked in software.
   **Verify on the Pixel 8** that the arm handover shows no dark halo.
