package com.shopai.app.ui.more

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.shopai.app.R
import com.shopai.app.ui.navigation.Routes

enum class MoreMenuIcon {
    Profile,
    Categories,
    Transactions,
    Reports,
    Reminders,
    Notifications,
    Settings,
    Help,
    About,
    Privacy,
    AskBusiness,
    GroupBuying,
    Handwritten,
    Voice,
    Subscription,
    Hsn,
    BooksSettings,
    Offers,
}

data class MoreMenuEntry(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val contentDescriptionRes: Int,
    val icon: MoreMenuIcon,
    val tint: Color,
    val route: String,
)

object MoreMenuPages {
    const val Help = "help"
    const val About = "about"
    const val Privacy = "privacy"
    const val Notifications = "notifications"
}

object MoreMenuCatalog {
    val primary: List<MoreMenuEntry> = listOf(
        MoreMenuEntry(
            id = "profile",
            labelRes = R.string.profile_title,
            contentDescriptionRes = R.string.cd_more_profile,
            icon = MoreMenuIcon.Profile,
            tint = Color(0xFF1E6B4E),
            route = Routes.Profile,
        ),
        MoreMenuEntry(
            id = "categories",
            labelRes = R.string.more_categories,
            contentDescriptionRes = R.string.cd_more_categories,
            icon = MoreMenuIcon.Categories,
            tint = Color(0xFF2E7D8A),
            route = Routes.Inventory,
        ),
        MoreMenuEntry(
            id = "transactions",
            labelRes = R.string.more_transactions,
            contentDescriptionRes = R.string.cd_more_transactions,
            icon = MoreMenuIcon.Transactions,
            tint = Color(0xFF3D6BCC),
            route = Routes.Billing,
        ),
        MoreMenuEntry(
            id = "reports",
            labelRes = R.string.more_reports,
            contentDescriptionRes = R.string.cd_more_reports,
            icon = MoreMenuIcon.Reports,
            tint = Color(0xFF6B4C9A),
            route = Routes.AiInsights,
        ),
        MoreMenuEntry(
            id = "reminders",
            labelRes = R.string.more_reminders,
            contentDescriptionRes = R.string.cd_more_reminders,
            icon = MoreMenuIcon.Reminders,
            tint = Color(0xFFC4841E),
            route = Routes.Reminders,
        ),
        MoreMenuEntry(
            id = "notifications",
            labelRes = R.string.more_notifications,
            contentDescriptionRes = R.string.cd_more_notifications,
            icon = MoreMenuIcon.Notifications,
            tint = Color(0xFFD9793C),
            route = Routes.moreInfo(MoreMenuPages.Notifications),
        ),
        MoreMenuEntry(
            id = "settings",
            labelRes = R.string.more_settings,
            contentDescriptionRes = R.string.cd_more_settings,
            icon = MoreMenuIcon.Settings,
            tint = Color(0xFF5A6B64),
            route = Routes.Settings,
        ),
        MoreMenuEntry(
            id = "help",
            labelRes = R.string.more_help_support,
            contentDescriptionRes = R.string.cd_more_help,
            icon = MoreMenuIcon.Help,
            tint = Color(0xFF1A7A5C),
            route = Routes.moreInfo(MoreMenuPages.Help),
        ),
        MoreMenuEntry(
            id = "about",
            labelRes = R.string.more_about_us,
            contentDescriptionRes = R.string.cd_more_about,
            icon = MoreMenuIcon.About,
            tint = Color(0xFF154F39),
            route = Routes.moreInfo(MoreMenuPages.About),
        ),
        MoreMenuEntry(
            id = "privacy",
            labelRes = R.string.privacy_policy,
            contentDescriptionRes = R.string.cd_more_privacy,
            icon = MoreMenuIcon.Privacy,
            tint = Color(0xFF3A5A8C),
            route = Routes.moreInfo(MoreMenuPages.Privacy),
        ),
    )

    val tools: List<MoreMenuEntry> = listOf(
        MoreMenuEntry(
            id = "ask_business",
            labelRes = R.string.more_ask_business,
            contentDescriptionRes = R.string.cd_more_ask_business,
            icon = MoreMenuIcon.AskBusiness,
            tint = Color(0xFF2E9F6E),
            route = Routes.AskBusiness,
        ),
        MoreMenuEntry(
            id = "group_buying",
            labelRes = R.string.more_group_buying,
            contentDescriptionRes = R.string.cd_more_group_buying,
            icon = MoreMenuIcon.GroupBuying,
            tint = Color(0xFF3D6BCC),
            route = Routes.GroupBuying,
        ),
        MoreMenuEntry(
            id = "handwritten",
            labelRes = R.string.more_handwritten,
            contentDescriptionRes = R.string.cd_more_handwritten,
            icon = MoreMenuIcon.Handwritten,
            tint = Color(0xFFC4841E),
            route = Routes.HandwrittenNotes,
        ),
        MoreMenuEntry(
            id = "voice",
            labelRes = R.string.more_voice,
            contentDescriptionRes = R.string.cd_more_voice,
            icon = MoreMenuIcon.Voice,
            tint = Color(0xFFD6503C),
            route = Routes.VoiceEntry,
        ),
        MoreMenuEntry(
            id = "subscription",
            labelRes = R.string.more_subscription,
            contentDescriptionRes = R.string.cd_more_subscription,
            icon = MoreMenuIcon.Subscription,
            tint = Color(0xFF6B4C9A),
            route = Routes.Subscription,
        ),
        MoreMenuEntry(
            id = "hsn",
            labelRes = R.string.books_hsn_master,
            contentDescriptionRes = R.string.cd_more_hsn,
            icon = MoreMenuIcon.Hsn,
            tint = Color(0xFF2E7D8A),
            route = Routes.HsnMaster,
        ),
        MoreMenuEntry(
            id = "books_settings",
            labelRes = R.string.books_settings,
            contentDescriptionRes = R.string.cd_more_books_settings,
            icon = MoreMenuIcon.BooksSettings,
            tint = Color(0xFF1E6B4E),
            route = Routes.BooksSettings,
        ),
        MoreMenuEntry(
            id = "offers",
            labelRes = R.string.more_local_offers,
            contentDescriptionRes = R.string.cd_more_offers,
            icon = MoreMenuIcon.Offers,
            tint = Color(0xFFD9793C),
            route = Routes.LocalOffers,
        ),
    )
}
