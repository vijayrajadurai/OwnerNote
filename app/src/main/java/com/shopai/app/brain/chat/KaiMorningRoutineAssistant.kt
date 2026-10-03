package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.memory.KaiTeaching
import com.shopai.app.brain.morning.MorningRoutineParser
import com.shopai.app.brain.morning.MorningRoutines
import com.shopai.app.brain.morning.MorningSection
import com.shopai.app.brain.morning.RoutineRequest
import com.shopai.app.brain.morning.RoutinePlace
import java.util.UUID

/**
 * The owner's Morning Routine conversation ("Morning-la first collections
 * paakanum. Appuram stock. Last-la reminders."). Kai repeats the order and
 * asks; ONLY [Save] (or a clear "aama" right after the question) changes the
 * saved routine. It is kept in the signed-in owner's private Kai memory —
 * never another owner's, never another business's.
 */
class KaiMorningRoutineAssistant(private val access: KaiMemoryAccess) {
    /** A routine Kai proposed; [order] null = back to the default order. */
    private data class Offer(val key: String, val order: List<MorningSection>?)

    private var offer: Offer? = null
    /** The offer was the last thing Kai said (a typed "aama" / "venam" answers it). */
    private var fresh = false
    /** Kai asked "which order?" ("Morning routine change pannu"). */
    private var askedOrder = false

    /** Kai is waiting for the owner's answer about their routine (Pesunga sends it here). */
    val waiting: Boolean get() = fresh || askedOrder

    /** [afterBrief]: Kai's previous answer was the morning brief ("First enakku pending payments kaatu" then means the routine). */
    suspend fun handle(said: String, text: String, chatLang: KaiLang, afterBrief: Boolean, people: List<String>): KaiTurn? {
        // "Stock first, collection next-la …": routine words are English, the sentence is Tanglish — answer in Tanglish.
        val lang = if (chatLang == KaiLang.ENGLISH && (tanglish.containsMatchIn(said) || tanglish.containsMatchIn(text))) KaiLang.TANGLISH else chatLang
        val wasFresh = fresh
        fresh = false
        val o = offer
        if (o != null && wasFresh) {
            if (KaiTeaching.isYes(text) || saveWord(text)) return save(o.key, lang)
            if (KaiTeaching.isNo(text) || notNowWord(text)) return notNow(o.key, lang)
        }
        val memory = runCatching { access.current() }.getOrNull() ?: return null
        val current = MorningRoutines.load(memory)?.orderedSections
        val context = askedOrder || afterBrief
        val req = MorningRoutineParser.parse(said, current, context, people) ?: MorningRoutineParser.parse(text, current, context, people)
        askedOrder = false
        req ?: return null
        return when (req) {
            RoutineRequest.Change -> {
                askedOrder = true
                reply(lang, ta = "சரி ஓனர். Morning Work-ல எந்த order வேணும்?\nஉதா: \"Collections first, அப்புறம் Stock, last-ல Reminders\"",
                    tl = "Seri Owner. Morning Work-la enna order venum?\nEg: \"Collections first, appuram Stock, last-la Reminders\"",
                    en = "Sure Owner. Which order do you want in Morning Work?\nE.g. \"Collections first, then Stock, Reminders last\"")
            }
            RoutineRequest.Reset ->
                if (current == null) reply(lang, ta = "ஓனர், இப்போ default order தான் இருக்கு 👍", tl = "Owner, ippo default order dhaan irukku 👍", en = "Owner, Morning Work already uses the default order 👍")
                else propose(null, lang,
                    ta = "ஓனர், Morning Work-ஐ default order-க்கு மாத்தவா?", tl = "Owner, Morning Work-a default order-ku maathava?", en = "Owner, switch Morning Work back to the default order?")
            is RoutineRequest.Set -> {
                val order = MorningRoutines.complete(req.order)
                if (order == MorningRoutines.complete(current ?: MorningRoutines.DEFAULT)) {
                    reply(lang, ta = "ஓனர், இப்போவே அதுதான் உங்க Morning Routine 👍", tl = "Owner, ippove adhu dhaan unga Morning Routine 👍", en = "Owner, that's already your Morning Routine 👍")
                } else {
                    val moved = req.moved
                    if (moved != null) {
                        val (s, place) = moved
                        val name = s.label(lang)
                        propose(order, lang,
                            ta = "ஓனர், அடுத்த முறை Morning Work-ல $name-ஐ " + when (place) { RoutinePlace.FIRST -> "முதல்ல"; RoutinePlace.LAST -> "கடைசியில"; RoutinePlace.NEXT -> "ரெண்டாவதா" } + " காட்டவா?",
                            tl = "Owner, next time Morning Work-la ${name.lowercase()}-a " + when (place) { RoutinePlace.FIRST -> "first"; RoutinePlace.LAST -> "last-la"; RoutinePlace.NEXT -> "second-aa" } + " kaattava?",
                            en = "Owner, show $name " + when (place) { RoutinePlace.FIRST -> "first"; RoutinePlace.LAST -> "last"; RoutinePlace.NEXT -> "second" } + " in Morning Work from next time?")
                    } else {
                        propose(order, lang,
                            ta = "ஓனர், இதை உங்க Morning Routine-ஆ save பண்ணவா?",
                            tl = "Owner, இதை உங்க Morning Routine-ஆ save பண்ணவா?",
                            en = "Owner, save this as your Morning Routine?")
                    }
                }
            }
        }
    }

    /** [Save] / [Not now]. */
    suspend fun act(action: KaiAction, lang: KaiLang): KaiTurn? = when (action) {
        is KaiAction.SaveRoutine -> save(action.key, lang)
        is KaiAction.RoutineNotNow -> notNow(action.key, lang)
        else -> null
    }

    fun reset() {
        offer = null
        fresh = false
        askedOrder = false
    }

    private fun propose(order: List<MorningSection>?, lang: KaiLang, ta: String, tl: String, en: String): KaiTurn {
        val key = UUID.randomUUID().toString().take(8)
        offer = Offer(key, order)
        fresh = true
        val lines = order?.let { MorningRoutines.describe(it, lang) } ?: MorningRoutines.describe(MorningRoutines.DEFAULT, lang)
        return KaiTurn(
            ChatReply(pick(lang, ta, tl, en), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(lines, listOf(
                KaiButton("Save", KaiAction.SaveRoutine(key), primary = true),
                KaiButton("Not now", KaiAction.RoutineNotNow(key)),
            )),
        )
    }

    private suspend fun save(key: String, lang: KaiLang): KaiTurn {
        val o = offer?.takeIf { it.key == key }
            ?: return reply(lang, ta = "ஓனர், அந்த routine கேள்வி பழையது — திரும்ப சொல்லுங்க.", tl = "Owner, andha routine question palasu — thirumba sollunga.", en = "Owner, that routine question has expired — please say it again.")
        offer = null
        fresh = false
        val memory = runCatching { access.current() }.getOrNull()
            ?: return reply(lang, ta = "ஓனர், இப்போ save பண்ண முடியல. எதுவும் மாறல.", tl = "Owner, ippo save panna mudiyala. Edhuvum maaralai.", en = "Owner, I couldn't save it now. Nothing changed.")
        if (o.order == null) {
            MorningRoutines.reset(memory)
            return reply(lang, ta = "முடிஞ்சது ஓனர் 👍 Morning Work default order-க்கு மாத்திட்டேன்.", tl = "Done Owner 👍 Morning Work default order-ku maathitten.", en = "Done Owner 👍 Morning Work is back to the default order.")
        }
        val saved = MorningRoutines.save(memory, o.order)?.orderedSections ?: o.order
        val flow = saved.joinToString(" → ") { it.label(lang) }
        return reply(lang,
            ta = "முடிஞ்சது ஓனர் 👍 உங்க Morning Routine save ஆச்சு:\n$flow",
            tl = "Done Owner 👍 Unga Morning Routine save aagiduchu:\n$flow",
            en = "Done Owner 👍 Your Morning Routine is saved:\n$flow")
    }

    private fun notNow(key: String, lang: KaiLang): KaiTurn {
        if (offer?.key == key) offer = null
        fresh = false
        return reply(lang, ta = "சரி ஓனர் 👍 Routine எதுவும் மாத்தல.", tl = "Seri Owner 👍 Routine edhuvum maathala.", en = "Okay Owner 👍 Your routine is unchanged.")
    }

    private fun reply(lang: KaiLang, ta: String, tl: String, en: String) =
        KaiTurn(ChatReply(pick(lang, ta, tl, en), KaiMood.HAPPY, ChatIntent.GENERAL_BUSINESS_QUERY))

    private val tanglish = Regex("""(?i)-la(?![\p{L}])|(?<![\p{L}])(appuram|aprom|venum|venam|vendam|kaatu|kaattu|kaami|podu|paakanum|pakkanum|pannu|enakku|maathu|kadaisi|mudhal\w*|aama|seri)(?![\p{L}])""")

    private fun saveWord(text: String) = Regex("""(?i)^\s*(save|save pannu|save pannunga|podu|seri save pannu)\s*$""").matches(text)
    private fun notNowWord(text: String) = Regex("""(?i)^\s*(not now|ippo venam|ippo vendam|appuram paakalam|later)\s*$""").matches(text)

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }
}
