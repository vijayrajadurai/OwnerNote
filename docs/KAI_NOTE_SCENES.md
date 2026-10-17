# Kai's hand-note — shop scenes and Kai's writing pose

Kai writes each draft into a notebook while the digital card follows (`ui/kai/KaiNoteScene.kt`). The picture behind them is the
owner's own kind of shop, picked from the business profile's category (`brain/note/KaiShopScene.kt`). Nothing is downloaded: each
scene is one picture inside the app. Until a picture is added, Kai's emerald-to-warm-white gradient shows.

## Pictures to add — `app/src/main/res/drawable-nodpi/`

| Shop category | File | What the picture shows |
|---|---|---|
| GROCERY | `kai_scene_grocery.webp` | Warm kirana / super shop: rice bags, dal and biscuit packets, oil bottles, steel weighing scale on the counter |
| PHARMACY | `kai_scene_pharmacy.webp` | Clean white-and-green medical shop: medicine racks, strips and bottles, glass counter |
| HARDWARE | `kai_scene_hardware.webp` | Pipes, paint tins, cement bags, tool wall, bolts in bins |
| ELECTRICAL | `kai_scene_electrical.webp` | Wire coils, switches, bulbs and fans on display |
| TEXTILE | `kai_scene_textile.webp` | Saree and fabric rolls on wooden shelves, folded shirts |
| MOBILE_ACCESSORIES | `kai_scene_mobile.webp` | Glass display of phone covers, chargers, earphones |
| AUTO_PARTS | `kai_scene_auto_parts.webp` | Spare-part racks, tyres, oil cans |
| FURNITURE | `kai_scene_furniture.webp` | Showroom with chairs, sofa, wooden tables |
| FOOTWEAR | `kai_scene_footwear.webp` | Shoe racks, slipper wall, shoe boxes |
| STATIONERY | `kai_scene_stationery.webp` | Notebook and pen shelves, files, colour boxes |
| RESTAURANT_FOOD | `kai_scene_restaurant.webp` | Hotel billing counter, tiffin items behind glass |
| BEAUTY_SALON | `kai_scene_salon.webp` | Salon mirror with lights, styling chair, product shelf |
| OTHER / none | `kai_scene_counter.webp` | A neutral warm shop counter with a cash drawer |

Each picture:
- 1080 px wide, landscape, WebP, about 150 KB or less;
- the same warm 3D style as Kai, with no Kai, no people and no text in it;
- busy detail away from the centre, because the app blurs it and lays a light veil on top so the notebook and the card stay readable.

The file name must be exactly as above. A missing or misspelt one simply shows the gradient.

## Kai writing — `app/src/main/res/drawable-nodpi/kai_writing.png`

While the notebook is being written, the app shows this picture of Kai. Until it is added, his pointing picture (`kai_point.png`) shows.

- Same Kai as `kai_full.png`: chubby, smiling, white shirt with a pen in the pocket, green towel with a gold border on the left shoulder, black hair.
- Upper body, seen from the front, slightly turned to his left. He looks down at his hands, smiling.
- His right hand holds a black fountain pen, writing at the bottom right of the picture, as if on a desk just below the frame.
- No desk, no notebook, no text. **Transparent background** (PNG with alpha).
- 1024 × 1024 px; Kai fills the frame from the waist up.

Prompt to generate it (same tool as the owner's reference picture):

> 3D Pixar-style render of the same chubby smiling Tamil shopkeeper mascot "Kai" (black hair, white half-sleeve shirt with a pen in
> the pocket, green towel with gold border on his left shoulder), upper body from the waist up, looking down happily, his right
> hand holding a black fountain pen as if writing on a desk just below the frame, no desk, no notebook, no text, isolated on a fully
> transparent background, soft studio light, 1024×1024.

Prompt for a shop picture (change the shop for each category):

> Photorealistic interior of a small Indian grocery / kirana shop, warm evening light, wooden shelves full of rice bags, dal and
> biscuit packets, oil bottles, steel weighing scale, shallow depth of field (background softly blurred), no people, no text,
> no logos, landscape 1080×1350.
