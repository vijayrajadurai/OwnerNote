package com.shopai.app.brain.note

/**
 * The shop behind Kai's notebook, by the owner's shop category (owner, 17 Oct 2026: "shop category ennavo athukethamari
 * background"). Read from the business profile ([com.shopai.app.data.model.BusinessCategory] api value) — never guessed from a
 * product or a sentence. Each scene is one picture kept in the app (res/drawable-nodpi/[image].webp, no download); a picture not
 * there yet shows Kai's emerald-to-warm-white gradient. The scene is decoration: it never changes what Kai understands or saves.
 */
enum class KaiShopScene(val image: String) {
    GROCERY("kai_scene_grocery"),
    PHARMACY("kai_scene_pharmacy"),
    HARDWARE("kai_scene_hardware"),
    ELECTRICAL("kai_scene_electrical"),
    TEXTILE("kai_scene_textile"),
    MOBILE_ACCESSORIES("kai_scene_mobile"),
    AUTO_PARTS("kai_scene_auto_parts"),
    FURNITURE("kai_scene_furniture"),
    FOOTWEAR("kai_scene_footwear"),
    STATIONERY("kai_scene_stationery"),
    RESTAURANT_FOOD("kai_scene_restaurant"),
    BEAUTY_SALON("kai_scene_salon"),
    /** No category, an unknown one, or OTHER: a warm shop counter. */
    NEUTRAL("kai_scene_counter");

    companion object {
        /** The scene for a business category's api value ("GROCERY", "PHARMACY" …); anything else is the neutral counter. */
        fun of(category: String?): KaiShopScene {
            val c = category?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it != NEUTRAL && it.name == c } ?: NEUTRAL
        }
    }
}
