# Kai's hand-note — shop scenes

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
