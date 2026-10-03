package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.memory.KaiMeaning
import com.shopai.app.brain.memory.KaiMemory
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KaiTeaching
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemorySource
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.memory.TeachCommand
import com.shopai.app.brain.memory.TeachTarget
import com.shopai.app.brain.tools.KaiCommands
import java.util.UUID

/**
 * Gives Kai the SIGNED-IN business's private language. The app implements
 * it over the business id of the current login; tests use a fixed one.
 */
interface KaiMemoryAccess {
    /** The current business's memory, already opened for it (null = no business: no private memory). */
    suspend fun current(): KaiPrivateMemory?

    /** The current business's products / customers / suppliers that nicknames may point to. */
    suspend fun entities(): List<KnownEntity>
}

/** What the memory step decided for one message. */
sealed interface MemoryStep {
    /** Answer the owner with this turn (a question, a confirmation of what was learned…). */
    data class Reply(val turn: KaiTurn) : MemoryStep

    /** Kai learned something; now handle [text] normally and start the reply with [prefix]. */
    data class Rerun(val prefix: String, val text: String) : MemoryStep
}

/**
 * Kai's private-language conversation: teaching, forgetting, asking about
 * an unknown phrase, and applying what the owner confirmed. It only changes
 * the WORDS Kai works with — never a rule, a balance or a transaction; the
 * normal Kai flow (draft → confirm → engine) handles every action after it.
 */
class KaiMemoryAssistant(private val access: KaiMemoryAccess) {

    /** Kai asked the owner about a phrase and waits for the answer. */
    private sealed interface Question {
        val key: String
        val phrase: String
        val original: String

        /** "`thooki kudu`-na Payment Out-aa?" — [guess] first, [options] as buttons. */
        data class Meaning(override val key: String, override val phrase: String, override val original: String, val guess: KaiMeaning?, val options: List<KaiMeaning>) : Question

        /** "`red paste`-na endha product?" */
        data class Product(override val key: String, override val phrase: String, override val original: String, val candidates: List<KnownEntity>) : Question

        /** "`thooki kudunga`-um `thooki kudu` maadhiri Payment Out-aa?" */
        data class Variant(override val key: String, override val phrase: String, override val original: String, val memory: KaiMemory) : Question

        /** "Today pottudu means stock out" → keep it always? */
        data class Permanent(override val key: String, override val phrase: String, override val original: String, val meaning: KaiMeaning) : Question

        /** "'ramba' nu enna meaning?" — Kai waits for the owner to say what the word means. */
        data class Word(override val key: String, override val phrase: String, override val original: String) : Question

        /** "'ramba' na 'romba' — save pannava?" — saved only after the owner says yes. */
        data class WordConfirm(override val key: String, override val phrase: String, override val original: String, val means: String) : Question
    }

    private var asking: Question? = null
    /** For "idha marandhudu": the last phrase Kai learned or used. */
    private var lastPhrase: String? = null

    fun reset() {
        asking = null
        lastPhrase = null
    }

    /** Kai asked the owner something about their words and waits for the answer. */
    val isAsking: Boolean get() = asking != null

    // ------------------------------------------------------------ before the core

    /** Answers to Kai's question, and teaching / forgetting sentences. Null: a normal message. */
    suspend fun before(text: String, chatLang: KaiLang): MemoryStep? {
        val lang = languageOf(text, chatLang)
        val mem = access.current()
        asking?.let { q -> answer(q, text, lang, mem)?.let { return it } }
        // "'ramba' nu enna meaning?" — what the owner taught, or Kai asks to learn it (inline, no separate screen).
        KaiTeaching.wordQuestion(text)?.let { word -> if (mem != null) return explainWord(word, lang, mem) }
        val entities = if (mem != null) access.entities() else emptyList()
        val cmd = KaiTeaching.parse(text, entities)
            ?: return KaiTeaching.wordTeach(text, entities)?.let { (word, means) -> if (mem != null) teachWordAsk(word, means, lang, mem) else null }
        if (mem == null) return reply(lang, KaiMood.CLARIFY,
            ta = "உங்க கடை விவரம் setup ஆன பிறகுதான் உங்க வார்த்தைகளை நினைவில் வைக்க முடியும் ஓனர்.",
            tl = "Owner, unga shop setup aana apram dhaan unga words-a remember panna mudiyum.",
            en = "Once your shop is set up I can remember your words, Owner.")
        return teach(cmd, lang, mem, entities)
    }

    /** The owner's confirmed language applied to [text] (words the global core understands). */
    suspend fun apply(text: String): String {
        val mem = access.current() ?: return text
        val applied = mem.apply(text, access.entities())
        if (applied.used.isNotEmpty()) mem.markUsed(applied.used)
        (applied.used.map { it.normalizedPhrase } + applied.fromThisConversation).firstOrNull()?.let { lastPhrase = it }
        return applied.text
    }

    // ------------------------------------------------------------ unknown phrases

    /**
     * A message that looks like an action (a person and an amount, or a
     * product and a quantity) with words Kai doesn't know: Kai ASKS — it never
     * guesses a meaning and never acts on it. Null: nothing to ask.
     */
    suspend fun unknown(text: String, original: String, lang: KaiLang, people: List<String>, products: List<String>): KaiTurn? {
        val mem = access.current() ?: return null
        val phrase = KaiTeaching.unknownPhrase(text, people, products) ?: return null
        val person = KaiCommands.personIn(text, people)
        val product = products.firstOrNull { KaiPrivateMemory.contains(text, KaiPrivateMemory.normalize(it)) }
        val amount = KaiTeaching.hasAmount(text)
        val options = when {
            product != null && amount -> listOf(KaiMeaning.STOCK_IN, KaiMeaning.STOCK_OUT)
            person != null && amount -> listOf(KaiMeaning.PAYMENT_OUT, KaiMeaning.PAYMENT_IN)
            else -> {
                mem.observe(phrase, null)
                return null
            }
        }
        val obs = mem.observe(phrase, options.first().name)
        val rejected = obs?.rejected.orEmpty()
        val open = options.filter { it.name !in rejected }
        // A spelling close to one the owner already taught ("thooki kudunga" ~ "thooki kudu"): ask, don't assume.
        mem.similar(phrase)?.let { m ->
            val meaning = m.meaning
            if (meaning != null) {
                val q = Question.Variant(newKey(), phrase, original, m)
                asking = q
                mem.suggested(phrase, meaning.name)
                return turn(lang, KaiMood.CLARIFY,
                    ta = "ஓனர், `$phrase`-உம் `${m.triggerPhrase}` மாதிரி ${meaning.label(lang)}-ஆ?",
                    tl = "Owner, `$phrase`-um `${m.triggerPhrase}` maadhiri ${meaning.label(lang)}-aa?",
                    en = "Owner, does `$phrase` also mean ${meaning.label(lang)}, like `${m.triggerPhrase}`?",
                    card = KaiCard(emptyList(), listOf(
                        KaiButton(pick(lang, "ஆமா", "Aama", "Yes"), KaiAction.LearnMeaning(q.key, meaning), primary = true),
                        KaiButton(pick(lang, "இல்ல", "Illa", "No"), KaiAction.NotThis(q.key)),
                    )))
            }
        }
        if (open.isEmpty()) {
            return turn(lang, KaiMood.CLARIFY,
                ta = "ஓனர், `$phrase`-னா என்னன்னு புரியல. கொஞ்சம் வேற மாதிரி சொல்லுங்க — எதுவும் சேமிக்கல.",
                tl = "Owner, `$phrase`-na enna-nu puriyala. Konjam vera maadhiri sollunga — edhuvum save pannala.",
                en = "Owner, I don't know what `$phrase` means. Could you say it another way? Nothing was saved.")
        }
        val guess = open.first()
        val q = Question.Meaning(newKey(), phrase, original, guess, open)
        asking = q
        mem.suggested(phrase, guess.name)
        // A product and a quantity with a word Kai doesn't know ("Colgate 5 box podu"): one plain yes / no question.
        if (guess == KaiMeaning.STOCK_IN || guess == KaiMeaning.STOCK_OUT) {
            val add = guess == KaiMeaning.STOCK_IN
            return turn(lang, KaiMood.CLARIFY,
                ta = if (add) "ஓனர், `$phrase`-னா stock சேர்க்கறதா?" else "ஓனர், `$phrase`-னா stock குறைக்கறதா?",
                tl = if (add) "Owner, `$phrase` na stock add pannradha?" else "Owner, `$phrase` na stock out pannradha?",
                en = if (add) "Owner, does `$phrase` mean add stock?" else "Owner, does `$phrase` mean stock out?",
                card = KaiCard(emptyList(), listOf(
                    KaiButton(pick(lang, if (add) "ஆமா, stock சேர்" else "ஆமா, stock out", if (add) "Yes, add stock" else "Yes, stock out", if (add) "Yes, add stock" else "Yes, stock out"),
                        KaiAction.LearnMeaning(q.key, guess), primary = true),
                    KaiButton(pick(lang, "இல்ல", "No", "No"), KaiAction.NotThis(q.key)),
                )))
        }
        val buttons = open.mapIndexed { i, m ->
            KaiButton(if (i == 0) pick(lang, "ஆமா, ${m.label(lang)}", "Aama, ${m.label(lang)}", "Yes, ${m.label(lang)}") else m.label(lang),
                KaiAction.LearnMeaning(q.key, m), primary = i == 0)
        } + KaiButton(pick(lang, "இல்ல", "Illa", "No"), KaiAction.NotThis(q.key))
        return turn(lang, KaiMood.CLARIFY,
            ta = "ஓனர், `$phrase`-னா ${guess.label(lang)}-னு சொல்றீங்களா?",
            tl = "Owner, `$phrase`-na ${guess.label(lang)}-nu mean pannureengala?",
            en = "Owner, by `$phrase` do you mean ${guess.label(lang)}?",
            card = KaiCard(emptyList(), buttons))
    }

    /**
     * "'ramba' na romba": Kai asks before saving. A money / stock meaning ("puli = customer payment")
     * is asked as a business meaning with its own buttons — never saved as a plain word.
     */
    private suspend fun teachWordAsk(word: String, means: String, lang: KaiLang, mem: KaiPrivateMemory): MemoryStep {
        val options = KaiTeaching.actionOptions(means)
        if (options.isNotEmpty()) {
            val q = Question.Meaning(newKey(), word, "", options.first(), options)
            asking = q
            mem.suggested(word, options.first().name)
            val label = options.joinToString(" / ") { it.label(lang) }
            return MemoryStep.Reply(turn(lang, KaiMood.CLARIFY,
                ta = "ஓனர், `$word` = $label — இது business அர்த்தம். சேமிக்கட்டுமா?",
                tl = "Owner, `$word` = $label — idhu business meaning-aa save pannava Owner?",
                en = "Owner, `$word` = $label — this is a business meaning. Save it?",
                card = KaiCard(emptyList(), options.mapIndexed { i, o ->
                    KaiButton(pick(lang, "ஆமா, ${o.label(lang)}", "Aama, ${o.label(lang)}", "Yes, ${o.label(lang)}"), KaiAction.LearnMeaning(q.key, o), primary = i == 0)
                } + KaiButton(pick(lang, "இல்ல", "Illa", "No"), KaiAction.NotThis(q.key)))))
        }
        val wq = Question.WordConfirm(newKey(), word, "", KaiPrivateMemory.normalize(means))
        asking = wq
        return MemoryStep.Reply(wordConfirmTurn(wq, lang))
    }

    private fun wordConfirmTurn(q: Question.WordConfirm, lang: KaiLang) = turn(lang, KaiMood.CLARIFY,
        ta = "சரி ஓனர் 😄 `${q.phrase}` = `${q.means}`-னு சேமிக்கட்டுமா?",
        tl = "Seri Owner 😄 `${q.phrase}` = `${q.means}` nu save pannava?",
        en = "Okay Owner 😄 Save `${q.phrase}` = `${q.means}`?",
        card = KaiCard(emptyList(), listOf(
            KaiButton(pick(lang, "ஆமா, சேமி", "Aama, save pannu", "Yes, save"), KaiAction.LearnWord(q.key), primary = true),
            KaiButton(pick(lang, "வேண்டாம்", "Vendam", "No"), KaiAction.NotThis(q.key)),
        )))

    /** After learning: re-read the message that needed the word, or just confirm when there was none. */
    private fun after(prefix: String, original: String): MemoryStep =
        if (original.isBlank()) MemoryStep.Reply(KaiTurn(ChatReply(prefix, KaiMood.SUCCESS, ChatIntent.GENERAL_BUSINESS_QUERY))) else MemoryStep.Rerun(prefix, original)

    /** The owner asked what a word means: the meaning they taught, or Kai asks them (and learns after a yes). */
    private suspend fun explainWord(word: String, lang: KaiLang, mem: KaiPrivateMemory): MemoryStep {
        val known = mem.find(word)?.takeIf { it.usable }
        if (known != null) {
            val value = known.meaning?.label(lang) ?: known.meaningValue
            return reply(lang, KaiMood.EXPLAINING,
                ta = "ஓனர், நீங்க சொன்னபடி `${known.triggerPhrase}`-னா `$value` 👍",
                tl = "Owner, neenga sonna maadhiri `${known.triggerPhrase}`-na `$value` 👍",
                en = "Owner, as you taught me: `${known.triggerPhrase}` means `$value` 👍")
        }
        val q = Question.Word(newKey(), word, "")
        asking = q
        mem.observe(word, null)
        return reply(lang, KaiMood.CLARIFY,
            ta = "எனக்கு தெரியல ஓனர் 🙂 `$word`-னா என்ன? சொல்லுங்க, உங்களுக்காக நினைவில் வெச்சுக்கறேன்.",
            tl = "Enakku theriyala Owner 🙂 `$word`-na enna? Sollunga, ungalukkaga nyabagam vechukiren.",
            en = "I don't know that one, Owner 🙂 What does `$word` mean? Tell me and I'll remember it for you.")
    }

    /**
     * A message Kai couldn't understand that has exactly one word Kai doesn't
     * know ("innaiku sales ramba kammi"): Kai asks what the word means — it
     * never guesses. [known] = words Kai already reads. Null: nothing to ask.
     */
    suspend fun unknownWord(text: String, original: String, lang: KaiLang, known: (String) -> Boolean): KaiTurn? {
        val mem = access.current() ?: return null
        val words = KaiPrivateMemory.normalize(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 7 || words.any { w -> w.any(Char::isDigit) }) return null
        val unknown = words.filter { it.length >= 3 && it.all(Char::isLetter) && !known(it) && mem.find(it) == null }.distinct()
        val word = unknown.singleOrNull() ?: return null
        val obs = mem.observe(word, null)
        if (obs?.rejected?.contains("WORD") == true) return null
        val q = Question.Word(newKey(), word, original)
        asking = q
        return turn(lang, KaiMood.CLARIFY,
            ta = "ஓனர், `$word`-னு சொன்னது என்ன அர்த்தத்துல?",
            tl = "Owner, `$word` nu sonnadhu enna meaning-la?",
            en = "Owner, what did you mean by `$word`?")
    }

    /** "red paste 10 add pannu" and no product is called "red paste": which one is it? */
    suspend fun unknownProduct(spoken: String, original: String, lang: KaiLang, products: List<KnownEntity>): KaiTurn? {
        val mem = access.current() ?: return null
        mem.observe(spoken, "PRODUCT")
        val words = KaiPrivateMemory.normalize(spoken).split(' ').filter { it.length >= 3 }
        val candidates = products.filter { p -> words.any { w -> KaiPrivateMemory.normalize(p.name).contains(w) } }
            .ifEmpty { if (products.size <= 6) products else emptyList() }
            .take(6)
        val q = Question.Product(newKey(), spoken, original, candidates)
        asking = q
        mem.suggested(spoken, "PRODUCT")
        if (candidates.size == 1) {
            val c = candidates.single()
            return turn(lang, KaiMood.CLARIFY,
                ta = "ஓனர், `$spoken`-னா ${c.name}-ஆ?", tl = "Owner, `$spoken`-na ${c.name}-aa?", en = "Owner, is `$spoken` ${c.name}?",
                card = KaiCard(emptyList(), listOf(
                    KaiButton(pick(lang, "ஆமா", "Aama", "Yes"), KaiAction.LearnEntity(q.key, c.id), primary = true),
                    KaiButton(pick(lang, "இல்ல", "Illa", "No"), KaiAction.NotThis(q.key)),
                )))
        }
        return turn(lang, KaiMood.CLARIFY,
            ta = "ஓனர், `$spoken`-னா எந்த பொருள்? பெயரை சொல்லுங்க.",
            tl = "Owner, `$spoken`-na endha product? Product name sollunga.",
            en = "Owner, which product is `$spoken`? Tell me its name.",
            card = KaiCard(emptyList(), candidates.map { KaiButton(it.name, KaiAction.LearnEntity(q.key, it.id)) } +
                KaiButton(pick(lang, "ரத்து", "Cancel", "Cancel"), KaiAction.NotThis(q.key))))
    }

    /** A button: learn / decline / keep. */
    suspend fun act(action: KaiAction, lang: KaiLang): MemoryStep? {
        val mem = access.current()
        return when (action) {
            is KaiAction.LearnMeaning -> {
                val q = asking?.takeIf { it.key == action.key } ?: return null
                if (mem == null) return null
                asking = null
                when (q) {
                    is Question.Variant -> {
                        mem.addVariant(q.memory.id, q.phrase)
                        lastPhrase = q.phrase
                        MemoryStep.Rerun(learned(q.phrase, action.meaning.label(lang), lang), q.original)
                    }
                    is Question.Permanent -> keepForever(q, mem, lang)
                    else -> {
                        mem.teachMeaning(q.phrase, action.meaning, MemorySource.OWNER_CONFIRMED)
                        lastPhrase = q.phrase
                        after(learned(q.phrase, action.meaning.label(lang), lang), q.original)
                    }
                }
            }
            is KaiAction.LearnEntity -> {
                val q = asking?.takeIf { it.key == action.key } as? Question.Product ?: return null
                if (mem == null) return null
                val e = access.entities().firstOrNull { it.id == action.entityId } ?: return null
                asking = null
                mem.teachEntity(q.phrase, e, MemorySource.OWNER_CONFIRMED)
                lastPhrase = q.phrase
                MemoryStep.Rerun(learned(q.phrase, e.name, lang), q.original)
            }
            is KaiAction.LearnWord -> {
                val q = asking?.takeIf { it.key == action.key } as? Question.WordConfirm ?: return null
                if (mem == null) return null
                asking = null
                saveWord(q, mem, lang)
            }
            is KaiAction.NotThis -> {
                val q = asking?.takeIf { it.key == action.key } ?: return null
                asking = null
                declined(q, mem, lang)
            }
            is KaiAction.OnlyNow -> {
                val q = asking?.takeIf { it.key == action.key } as? Question.Permanent ?: return null
                asking = null
                onlyNow(q, lang)
            }
            is KaiAction.LearnAlias -> {
                if (mem == null) return null
                val e = access.entities().firstOrNull { it.id == action.entityId } ?: return null
                mem.teachEntity(action.phrase, e, MemorySource.OWNER_CONFIRMED)
                lastPhrase = action.phrase
                reply(lang, KaiMood.SUCCESS, ta = learned(action.phrase, e.name, KaiLang.TAMIL), tl = learned(action.phrase, e.name, KaiLang.TANGLISH), en = learned(action.phrase, e.name, KaiLang.ENGLISH))
            }
            else -> null
        }
    }

    /** After a payment the owner placed by choosing who it was: offer to remember the nickname. */
    fun aliasButton(said: String?, chosen: KnownEntity, lang: KaiLang): KaiButton? {
        val s = said?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (KaiPrivateMemory.normalize(s) == KaiPrivateMemory.normalize(chosen.name)) return null
        return KaiButton(pick(lang, "நினைவில் வை: `$s` = ${chosen.name}", "Remember: `$s` = ${chosen.name}", "Remember: `$s` = ${chosen.name}"),
            KaiAction.LearnAlias(s, chosen.id))
    }

    // ------------------------------------------------------------ internals

    private suspend fun answer(q: Question, text: String, lang: KaiLang, mem: KaiPrivateMemory?): MemoryStep? {
        if (mem == null) {
            asking = null
            return null
        }
        // Only a short answer answers Kai's question; a new sentence is a new message.
        if (KaiPrivateMemory.normalize(text).split(' ').size > (if (q is Question.Word) 6 else 4)) {
            asking = null
            return null
        }
        val yes = KaiTeaching.isYes(text)
        val no = KaiTeaching.isNo(text)
        when (q) {
            is Question.Meaning -> {
                val said = KaiTeaching.meaningIn(text)
                val meaning = said ?: q.guess.takeIf { yes }
                if (meaning != null) {
                    asking = null
                    mem.teachMeaning(q.phrase, meaning, MemorySource.OWNER_CONFIRMED)
                    lastPhrase = q.phrase
                    return after(learned(q.phrase, meaning.label(lang), lang), q.original)
                }
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
            }
            is Question.Variant -> {
                if (yes) {
                    asking = null
                    mem.addVariant(q.memory.id, q.phrase)
                    lastPhrase = q.phrase
                    return MemoryStep.Rerun(learned(q.phrase, q.memory.meaning?.label(lang) ?: q.memory.meaningValue, lang), q.original)
                }
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
            }
            is Question.Product -> {
                val products = access.entities().filter { it.type == MemoryType.PRODUCT_ALIAS }
                val said = KaiPrivateMemory.normalize(text).replace(Regex("""\s+(aa|ah|thaan|dhaan|than)$"""), "")
                val chosen = products.firstOrNull { KaiPrivateMemory.normalize(it.name) == said }
                    ?: q.candidates.singleOrNull()?.takeIf { yes }
                if (chosen != null) {
                    asking = null
                    mem.teachEntity(q.phrase, chosen, MemorySource.OWNER_CONFIRMED)
                    lastPhrase = q.phrase
                    return MemoryStep.Rerun(learned(q.phrase, chosen.name, lang), q.original)
                }
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
            }
            is Question.Word -> {
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
                val means = KaiTeaching.wordAnswer(text)
                if (means != null && means != KaiPrivateMemory.normalize(q.phrase)) {
                    // A money / stock meaning is never a plain word: it needs the action question (and its own confirmation).
                    KaiTeaching.actionMeaningOf(means)?.let { meaning ->
                        val mq = Question.Meaning(newKey(), q.phrase, q.original, meaning, listOf(meaning))
                        asking = mq
                        mem.suggested(q.phrase, meaning.name)
                        return MemoryStep.Reply(turn(lang, KaiMood.CLARIFY,
                            ta = "ஓனர், `${q.phrase}`-னா ${meaning.label(lang)}-னு சொல்றீங்களா?",
                            tl = "Owner, `${q.phrase}`-na ${meaning.label(lang)}-nu mean pannureengala?",
                            en = "Owner, by `${q.phrase}` do you mean ${meaning.label(lang)}?",
                            card = KaiCard(emptyList(), listOf(
                                KaiButton(pick(lang, "ஆமா, ${meaning.label(lang)}", "Aama, ${meaning.label(lang)}", "Yes, ${meaning.label(lang)}"), KaiAction.LearnMeaning(mq.key, meaning), primary = true),
                                KaiButton(pick(lang, "இல்ல", "Illa", "No"), KaiAction.NotThis(mq.key)),
                            ))))
                    }
                    val wq = Question.WordConfirm(newKey(), q.phrase, q.original, means)
                    asking = wq
                    return MemoryStep.Reply(wordConfirmTurn(wq, lang))
                }
            }
            is Question.WordConfirm -> {
                if (yes) {
                    asking = null
                    return saveWord(q, mem, lang)
                }
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
            }
            is Question.Permanent -> {
                if (yes || KaiTeaching.parse(text, emptyList()) == TeachCommand.RememberThat) {
                    asking = null
                    return keepForever(q, mem, lang)
                }
                if (no) {
                    asking = null
                    return onlyNow(q, lang)
                }
            }
        }
        // Something else: the question is dropped and the message is handled normally.
        asking = null
        return null
    }

    private suspend fun declined(q: Question, mem: KaiPrivateMemory?, lang: KaiLang): MemoryStep {
        when (q) {
            is Question.Meaning -> mem?.rejected(q.phrase, q.guess?.name)
            is Question.Product -> mem?.rejected(q.phrase, "PRODUCT")
            is Question.Variant -> mem?.rejected(q.phrase, q.memory.meaningType)
            is Question.Permanent -> Unit
            is Question.Word, is Question.WordConfirm -> mem?.rejected(q.phrase, "WORD")
        }
        return reply(lang, KaiMood.NEUTRAL,
            ta = "சரி ஓனர், எதுவும் நினைவில் வைக்கல, எதுவும் சேமிக்கல. `${q.phrase}`-னா என்னன்னு சொன்னா கத்துக்கறேன்.",
            tl = "Seri Owner, edhuvum remember pannala, edhuvum save pannala. `${q.phrase}`-na enna-nu sonna kathukkuren.",
            en = "Okay Owner, I didn't remember or save anything. Tell me what `${q.phrase}` means and I'll learn it.")
    }

    /** The owner said yes: the word is saved for this owner only, and Kai reads the original message again with it. */
    private suspend fun saveWord(q: Question.WordConfirm, mem: KaiPrivateMemory, lang: KaiLang): MemoryStep {
        mem.teachWord(q.phrase, q.means, MemorySource.OWNER_CONFIRMED)
        lastPhrase = KaiPrivateMemory.normalize(q.phrase)
        val done = pick(lang,
            ta = "சரி ஓனர் 👍 இனிமே நீங்க `${q.phrase}` சொன்னா `${q.means}`-னு புரிஞ்சுக்குவேன்.",
            tl = "Done Owner 👍 Inime neenga `${q.phrase}` sonna `${q.means}` nu purinjukuren.",
            en = "Done Owner 👍 From now on when you say `${q.phrase}` I'll understand `${q.means}`.")
        return after(done, q.original)
    }

    private fun onlyNow(q: Question.Permanent, lang: KaiLang): MemoryStep = reply(lang, KaiMood.NEUTRAL,
        ta = "சரி ஓனர், இந்த பேச்சுக்கு மட்டும் `${q.phrase}` = ${q.meaning.label(lang)}.",
        tl = "Seri Owner, indha conversation-ku mattum `${q.phrase}` = ${q.meaning.label(lang)}.",
        en = "Okay Owner, only for this conversation: `${q.phrase}` = ${q.meaning.label(lang)}.")

    private suspend fun keepForever(q: Question.Permanent, mem: KaiPrivateMemory, lang: KaiLang): MemoryStep {
        mem.teachMeaning(q.phrase, q.meaning, MemorySource.OWNER_CREATED)
        lastPhrase = q.phrase
        return reply(lang, KaiMood.SUCCESS, ta = learned(q.phrase, q.meaning.label(lang), KaiLang.TAMIL), tl = learned(q.phrase, q.meaning.label(lang), KaiLang.TANGLISH), en = learned(q.phrase, q.meaning.label(lang), KaiLang.ENGLISH))
    }

    private suspend fun teach(cmd: TeachCommand, lang: KaiLang, mem: KaiPrivateMemory, entities: List<KnownEntity>): MemoryStep = when (cmd) {
        is TeachCommand.Teach -> {
            val (value, saved) = when (val t = cmd.target) {
                is TeachTarget.Meaning -> t.meaning.label(lang) to mem.teachMeaning(cmd.phrase, t.meaning, MemorySource.OWNER_CREATED)
                is TeachTarget.Entity -> t.entity.name to mem.teachEntity(cmd.phrase, t.entity, MemorySource.OWNER_CREATED)
            }
            lastPhrase = saved.normalizedPhrase
            asking = null
            if (cmd.correction) reply(lang, KaiMood.SUCCESS,
                ta = "சரி ஓனர். இந்த கடையில `${saved.triggerPhrase}` = $value-னு நினைவில் வெச்சுக்கறேன்.",
                tl = "Okay Owner. Indha business-la `${saved.triggerPhrase}` = $value-nu remember pannikiren.",
                en = "Okay Owner. In this business `${saved.triggerPhrase}` = $value — I'll remember that.")
            else reply(lang, KaiMood.SUCCESS, ta = learned(saved.triggerPhrase, value, KaiLang.TAMIL), tl = learned(saved.triggerPhrase, value, KaiLang.TANGLISH), en = learned(saved.triggerPhrase, value, KaiLang.ENGLISH))
        }
        is TeachCommand.ForNow -> {
            mem.overrideForConversation(cmd.phrase, cmd.meaning)
            val q = Question.Permanent(newKey(), cmd.phrase, cmd.phrase, cmd.meaning)
            asking = q
            lastPhrase = KaiPrivateMemory.normalize(cmd.phrase)
            MemoryStep.Reply(turn(lang, KaiMood.CLARIFY,
                ta = "சரி ஓனர், இப்போதைக்கு `${cmd.phrase}` = ${cmd.meaning.label(lang)}. இனிமேலும் இதே அர்த்தமா?",
                tl = "Seri Owner, ippothaikku `${cmd.phrase}` = ${cmd.meaning.label(lang)}. Inimel eppovume idhe meaning-aa?",
                en = "Okay Owner, for now `${cmd.phrase}` = ${cmd.meaning.label(lang)}. Should it always mean this?",
                card = KaiCard(emptyList(), listOf(
                    KaiButton(pick(lang, "எப்போதும்", "Eppovume", "Always"), KaiAction.LearnMeaning(q.key, cmd.meaning), primary = true),
                    KaiButton(pick(lang, "இப்போ மட்டும்", "Ippo mattum", "Only now"), KaiAction.OnlyNow(q.key)),
                ))))
        }
        is TeachCommand.Forget -> {
            val phrase = cmd.phrase ?: lastPhrase
            val removed = phrase?.let { mem.forget(it) }
            if (removed != null) reply(lang, KaiMood.NEUTRAL,
                ta = "சரி ஓனர், `${removed.triggerPhrase}` அர்த்தத்தை மறந்துட்டேன்.",
                tl = "Seri Owner, `${removed.triggerPhrase}` meaning-a marandhutten.",
                en = "Okay Owner, I've forgotten what `${removed.triggerPhrase}` meant.")
            else reply(lang, KaiMood.CLARIFY,
                ta = "ஓனர், அப்படி ஒரு வார்த்தை நான் நினைவில் வெச்சிருக்கல.",
                tl = "Owner, adhu maadhiri edhuvum naan remember pannala.",
                en = "Owner, I don't have that remembered.")
        }
        TeachCommand.RememberThat -> {
            val q = asking
            when {
                q is Question.Permanent -> { asking = null; keepForever(q, mem, lang) }
                q is Question.Meaning && q.guess != null -> {
                    asking = null
                    mem.teachMeaning(q.phrase, q.guess, MemorySource.OWNER_CONFIRMED)
                    lastPhrase = q.phrase
                    after(learned(q.phrase, q.guess.label(lang), lang), q.original)
                }
                else -> {
                    val now = lastPhrase?.let { p -> mem.conversationMeaning(p)?.let { p to it } }
                    if (now != null) {
                        mem.teachMeaning(now.first, now.second, MemorySource.OWNER_CREATED)
                        reply(lang, KaiMood.SUCCESS, ta = learned(now.first, now.second.label(lang), KaiLang.TAMIL), tl = learned(now.first, now.second.label(lang), KaiLang.TANGLISH), en = learned(now.first, now.second.label(lang), KaiLang.ENGLISH))
                    } else reply(lang, KaiMood.CLARIFY,
                        ta = "எதை நினைவில் வைக்கணும் ஓனர்? உதாரணம்: \"thooki kudu na payment out\".",
                        tl = "Edha remember pannanum Owner? Example: \"thooki kudu na payment out\".",
                        en = "What should I remember, Owner? For example: \"thooki kudu means payment out\".")
                }
            }
        }
        TeachCommand.ShowMemory -> {
            val list = mem.usable()
            if (list.isEmpty()) reply(lang, KaiMood.NEUTRAL,
                ta = "இன்னும் உங்க கடை வார்த்தை எதுவும் கத்துக்கல ஓனர்.",
                tl = "Owner, innum unga shop words edhuvum kathukkala.",
                en = "I haven't learned any of your shop's words yet, Owner.")
            else MemoryStep.Reply(KaiTurn(
                ChatReply(pick(lang, "உங்க கடை மொழி ஓனர்:", "Owner, unga shop language:", "Your shop language, Owner:"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
                KaiCard(list.take(20).map { m -> "`${m.triggerPhrase}` → ${m.meaning?.label(lang) ?: m.meaningValue}" }, emptyList()),
            ))
        }
    }

    /** "Got it Owner 👍 In this shop: `thooki kudu` = Payment Out. I'll remember this for your business." */
    private fun learned(phrase: String, value: String, lang: KaiLang) = pick(lang,
        ta = "சரி ஓனர் 👍 இந்த கடையில: `$phrase` = $value. உங்க கடைக்கு மட்டும் இதை நினைவில் வெச்சுக்கறேன்.",
        tl = "Got it Owner 👍 Indha shop-la: `$phrase` = $value. Unga business-ku mattum idha remember pannikiren.",
        en = "Got it Owner 👍 In this shop: `$phrase` = $value. I'll remember this for your business.")

    private fun reply(lang: KaiLang, mood: KaiMood, ta: String, tl: String, en: String) =
        MemoryStep.Reply(KaiTurn(ChatReply(pick(lang, ta, tl, en), mood, ChatIntent.GENERAL_BUSINESS_QUERY)))

    private fun turn(lang: KaiLang, mood: KaiMood, ta: String, tl: String, en: String, card: KaiCard? = null) =
        KaiTurn(ChatReply(pick(lang, ta, tl, en), mood, ChatIntent.GENERAL_BUSINESS_QUERY), card)

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }

    private fun newKey() = UUID.randomUUID().toString().take(8)

    /** "Illai, pottudu-na stock IN" reads as English to the chat detector; Tanglish words decide. */
    private fun languageOf(text: String, chatLang: KaiLang): KaiLang {
        if (chatLang != KaiLang.ENGLISH) return chatLang
        val words = KaiPrivateMemory.normalize(text).split(' ').toSet()
        val tanglish = setOf("illai", "illa", "na", "nu", "nna", "marandhudu", "maranthudu", "pannu", "pannunga", "kudu", "aama", "sollu", "enna",
            "kathukitta", "idha", "adha", "inimel", "vechuko", "ku", "kitta")
        return if (words.any { it in tanglish }) KaiLang.TANGLISH else chatLang
    }
}
