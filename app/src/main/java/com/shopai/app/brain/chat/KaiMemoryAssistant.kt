package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.memory.KaiMeaning
import com.shopai.app.brain.memory.KaiMemory
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KaiTeaching
import com.shopai.app.brain.memory.KaiPersonalTeaching
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemoryScope
import com.shopai.app.brain.memory.MemorySource
import com.shopai.app.brain.memory.PersonalMeaning
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
        data class Meaning(
            override val key: String, override val phrase: String, override val original: String, val guess: KaiMeaning?, val options: List<KaiMeaning>,
            val scope: MemoryScope = MemoryScope.BUSINESS, val sourceText: String? = null,
        ) : Question

        /** "Owner, `petti` na box-ah? Packet-ah? Vera meaning-ah?" — a word after a quantity Kai doesn't know as a unit. */
        data class Unit(override val key: String, override val phrase: String, override val original: String) : Question

        /** Kai repeated what the owner taught ("`potti` = 1 box — save pannava?"); saved only after a yes / [Save]. */
        data class Confirm(
            override val key: String, override val phrase: String, override val original: String, val meaning: PersonalMeaning,
            val scope: MemoryScope, val correction: Boolean, val sourceText: String?,
        ) : Question

        /** "`potti`-ku new meaning enna Owner?" */
        data class NewMeaning(override val key: String, override val phrase: String, override val original: String) : Question

        /** "`potti` memory remove pannava?" [Forget] [Cancel] */
        data class ForgetConfirm(override val key: String, override val phrase: String, override val original: String, val memory: KaiMemory) : Question

        /** After the list: "Edha change / marakkanum Owner?" */
        data class Pick(override val key: String, override val phrase: String, override val original: String, val forEdit: Boolean) : Question

        /** "`red paste`-na endha product?" */
        data class Product(override val key: String, override val phrase: String, override val original: String, val candidates: List<KnownEntity>) : Question

        /** "`thooki kudunga`-um `thooki kudu` maadhiri Payment Out-aa?" */
        data class Variant(override val key: String, override val phrase: String, override val original: String, val memory: KaiMemory) : Question

        /** "Today pottudu means stock out" → keep it always? */
        data class Permanent(override val key: String, override val phrase: String, override val original: String, val meaning: KaiMeaning) : Question

        /** "'ramba' nu enna meaning?" — Kai waits for the owner to say what the word means. */
        data class Word(override val key: String, override val phrase: String, override val original: String) : Question

        /** "'ramba' na 'romba' — save pannava?" — saved only after the owner says yes. */
        data class WordConfirm(
            override val key: String, override val phrase: String, override val original: String, val means: String,
            val scope: MemoryScope = MemoryScope.BUSINESS, val sourceText: String? = null,
        ) : Question
    }

    private var asking: Question? = null

    /** Something was saved (the agent records it in the action log — the meaning only, not the conversation). */
    var onLearned: ((KaiMemory) -> Unit)? = null

    private fun learnedMemory(m: KaiMemory): KaiMemory = m.also { runCatching { onLearned?.invoke(it) } }
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
        // "potti" on its own, once taught: what it means to this owner (never read as another word).
        if (mem != null && !text.contains(' ') && text.none(Char::isDigit) && mem.find(text.trim().trimEnd('?', '.', '!')) != null) {
            return explainWord(text.trim().trimEnd('?', '.', '!'), lang, mem)
        }
        // "'ramba' nu enna meaning?" — what the owner taught, or Kai asks to learn it (inline, no separate screen).
        KaiTeaching.wordQuestion(text)?.let { word -> if (mem != null) return explainWord(word, lang, mem) }
        val entities = if (mem != null) access.entities() else emptyList()
        // "potti na enna?", "anna yaaru?" — only this owner's own words (another owner's are never known here).
        if (mem != null) shortQuestion(text, entities, mem)?.let { word -> return explainWord(word, lang, mem) }
        val cmd = KaiTeaching.parse(text, entities)
            ?: return KaiPersonalTeaching.parse(text, entities)?.let { pt -> if (mem != null) personalAsk(pt, "", lang, mem) else null }
        if (mem == null) return reply(lang, KaiMood.CLARIFY,
            ta = "உங்க கடை விவரம் setup ஆன பிறகுதான் உங்க வார்த்தைகளை நினைவில் வைக்க முடியும் ஓனர்.",
            tl = "Owner, unga shop setup aana apram dhaan unga words-a remember panna mudiyum.",
            en = "Once your shop is set up I can remember your words, Owner.")
        return teach(cmd, lang, mem, entities)
    }

    /** A correction the owner taught ("bill kuduthaan" = not a payment) that the last message used. */
    var correctionUsed: KaiMemory? = null
        private set

    /**
     * The owner's references found in the last message, with the exact record each one means ("Kumar House" → c2).
     * The words become the record's name for understanding; the identity travels with them — never the name alone.
     */
    var lastReferences: List<Pair<String, KnownEntity>> = emptyList()
        private set

    /** How the owner names each record they gave a reference to (record id → "Kumar House"), for "which one?" choices. */
    suspend fun referenceLabels(): Map<String, String> {
        val mem = access.current() ?: return emptyMap()
        return mem.usable().filter { (it.memoryType == MemoryType.CUSTOMER_ALIAS || it.memoryType == MemoryType.SUPPLIER_ALIAS) && it.referenceEntityId != null }
            .groupBy { it.referenceEntityId!! }.mapValues { (_, m) -> m.maxByOrNull { it.updatedAt }!!.triggerPhrase.trim() }
    }

    /** The owner said who a reference means (an explicit pick or correction): remembered for this business. */
    suspend fun learnReference(phrase: String, entityId: String): KnownEntity? {
        val mem = access.current() ?: return null
        val e = access.entities().firstOrNull { it.id == entityId } ?: return null
        mem.forget(phrase)
        learnedMemory(mem.teachEntity(phrase, e, MemorySource.OWNER_CONFIRMED))
        lastPhrase = phrase
        return e
    }

    /** "Kumar Anna vera Kumar": the owner says a remembered reference is wrong — it is dropped (asked again, never guessed). */
    suspend fun dropReference(phrase: String): KaiMemory? {
        val mem = access.current() ?: return null
        val m = mem.find(phrase)?.takeIf { it.memoryType == MemoryType.CUSTOMER_ALIAS || it.memoryType == MemoryType.SUPPLIER_ALIAS } ?: return null
        return mem.forget(m.normalizedPhrase)
    }

    /** The owner's confirmed language applied to [text] (words the global core understands). */
    suspend fun apply(text: String): String {
        correctionUsed = null
        lastReferences = emptyList()
        val mem = access.current() ?: return text
        val applied = mem.apply(text, access.entities())
        lastReferences = applied.entities
        correctionUsed = applied.used.firstOrNull { it.memoryType == MemoryType.CORRECTION }
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
    // Never "podu" / "pottu" (put — stock in).
    private val goneWord = Regex("""(?i)^(po(?![dt])|vith|vitt|kett|udanj|damage|out|sold|kammi|kurai)|^போ(?!ட)|^வித்|^கெட்|^உடைஞ்""")

    suspend fun unknown(text: String, original: String, lang: KaiLang, people: List<String>, products: List<String>): KaiTurn? {
        val mem = access.current() ?: return null
        val phrase = KaiTeaching.unknownPhrase(text, people, products) ?: return null
        val person = KaiCommands.personIn(text, people)
        val product = products.firstOrNull { KaiPrivateMemory.contains(text, KaiPrivateMemory.normalize(it)) }
        val amount = KaiTeaching.hasAmount(text)
        val options = when {
            // "Mambalam 30kg poiruchi": a word like "po-" (went), "vith-" (sold), "kett-" (spoilt) is stock going out — asked first.
            product != null && amount && goneWord.containsMatchIn(phrase) -> listOf(KaiMeaning.STOCK_OUT, KaiMeaning.STOCK_IN)
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
    private suspend fun teachWordAsk(
        word: String, means: String, lang: KaiLang, mem: KaiPrivateMemory,
        scope: MemoryScope = MemoryScope.BUSINESS, sourceText: String? = null, original: String = "",
    ): MemoryStep {
        val options = KaiTeaching.actionOptions(means)
        if (options.isNotEmpty()) {
            val q = Question.Meaning(newKey(), word, original, options.first(), options, scope, sourceText)
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
        val wq = Question.WordConfirm(newKey(), word, original, KaiPrivateMemory.normalize(means), scope, sourceText)
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
                        learnedMemory(mem.teachMeaning(q.phrase, action.meaning, MemorySource.OWNER_CONFIRMED, (q as? Question.Meaning)?.scope ?: MemoryScope.BUSINESS, (q as? Question.Meaning)?.sourceText))
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
                learnedMemory(mem.teachEntity(q.phrase, e, MemorySource.OWNER_CONFIRMED))
                lastPhrase = q.phrase
                after(learned(q.phrase, e.name, lang), q.original)
            }
            is KaiAction.LearnWord -> {
                val q = asking?.takeIf { it.key == action.key } ?: return null
                if (mem == null) return null
                when (q) {
                    is Question.WordConfirm -> { asking = null; saveWord(q, mem, lang) }
                    is Question.Confirm -> { asking = null; saveConfirm(q, mem, lang) }
                    is Question.ForgetConfirm -> { asking = null; forgetNow(q, mem, lang) }
                    else -> null
                }
            }
            is KaiAction.PickUnit -> {
                val q = asking?.takeIf { it.key == action.key } as? Question.Unit ?: return null
                if (mem == null) return null
                if (action.unit.isBlank()) {
                    // "Vera meaning": Kai asks what it is.
                    val wq = Question.Word(newKey(), q.phrase, q.original)
                    asking = wq
                    return reply(lang, KaiMood.CLARIFY,
                        ta = "சரி ஓனர், `${q.phrase}`-னா என்ன?", tl = "Seri Owner, `${q.phrase}`-na enna?", en = "Okay Owner, what does `${q.phrase}` mean?")
                }
                MemoryStep.Reply(confirmAsk(q.phrase, PersonalMeaning.Unit(action.unit), q.original, MemoryScope.BUSINESS, false, null, lang, mem))
            }
            is KaiAction.ManageMemory -> {
                if (mem == null) return null
                when (action.op) {
                    "EDIT", "FORGET" -> {
                        val q = Question.Pick(newKey(), "", "", forEdit = action.op == "EDIT")
                        asking = q
                        reply(lang, KaiMood.CLARIFY,
                            ta = if (q.forEdit) "எதை மாத்தணும் ஓனர்? வார்த்தையை சொல்லுங்க." else "எதை மறக்கணும் ஓனர்? வார்த்தையை சொல்லுங்க.",
                            tl = if (q.forEdit) "Edha change pannanum Owner? Word-a sollunga." else "Edha marakkanum Owner? Word-a sollunga.",
                            en = if (q.forEdit) "Which one should I change, Owner? Tell me the word." else "Which one should I forget, Owner? Tell me the word.")
                    }
                    else -> reply(lang, KaiMood.HAPPY, ta = "சரி ஓனர் 👍 எல்லாம் அப்படியே இருக்கு.", tl = "Seri Owner 👍 Ellam appadiye irukku.", en = "Okay Owner 👍 Everything stays as it is.")
                }
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
                learnedMemory(mem.teachEntity(action.phrase, e, MemorySource.OWNER_CONFIRMED))
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

    // ------------------------------------------------------------ personal learning (confirm first)

    /**
     * "Colgate 2 petti vandhiruku" and Kai doesn't know `petti` as a unit: ask
     * (box? packet? something else?) — never guess, never act on it. Null when
     * there's nothing to ask (no product / quantity, or the owner already said no).
     */
    suspend fun unknownUnit(text: String, original: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>): KaiTurn? {
        val mem = access.current() ?: return null
        val word = com.shopai.app.brain.tools.KaiStock.unknownUnitWord(text, products) ?: return null
        if (mem.find(word) != null) return null
        val obs = mem.observe(word, "UNIT")
        if (obs?.rejected?.contains("UNIT") == true) return null
        val q = Question.Unit(newKey(), word, original)
        asking = q
        mem.suggested(word, "UNIT")
        val l = languageOf(original, lang)
        return turn(l, KaiMood.CLARIFY,
            ta = "ஓனர், `$word`-னா box-ஆ? Packet-ஆ? வேற அர்த்தமா?",
            tl = "Owner, `$word` na box-ah? Packet-ah? Vera meaning-ah?",
            en = "Owner, does `$word` mean a box? A packet? Something else?",
            card = KaiCard(emptyList(), listOf(
                KaiButton("Box", KaiAction.PickUnit(q.key, "box"), primary = true),
                KaiButton("Packet", KaiAction.PickUnit(q.key, "packet")),
                KaiButton(pick(l, "பீஸ்", "Piece", "Piece"), KaiAction.PickUnit(q.key, "piece")),
                KaiButton(pick(l, "வேற அர்த்தம்", "Vera meaning", "Something else"), KaiAction.PickUnit(q.key, "")),
            )))
    }

    /**
     * Right after Kai prepared a draft, the owner says it was wrong ("Illai Kai,
     * avan bill mattum kuduthaan"): the words both sentences share are what Kai
     * misread. Kai asks before remembering them as NOT that action. Null: not
     * such a correction.
     */
    suspend fun correctionAfter(said: String, previous: String, kind: String, people: List<String>, lang: KaiLang): KaiTurn? {
        val mem = access.current() ?: return null
        val n = KaiPrivateMemory.normalize(said)
        if (!Regex("""^(illai|illa|ille|no|wrong|thappu|tappu|இல்லை|இல்ல)(\s|$)""").containsMatchIn(n)) return null
        val skip = setOf("illai", "illa", "ille", "no", "wrong", "thappu", "tappu", "kai", "owner", "avan", "aval", "avar", "avanga", "he", "she", "they",
            "mattum", "only", "just", "dhaan", "thaan", "than", "it", "was", "is", "the", "a", "um", "ah", "aa", "இல்லை", "இல்ல") +
            people.flatMap { KaiPrivateMemory.normalize(it).split(' ') }
        val corr = n.split(' ').filter { it.isNotEmpty() && it !in skip && it.none(Char::isDigit) }.toSet()
        val phraseWords = KaiPrivateMemory.normalize(previous).split(' ').filter { it in corr }
        if (phraseWords.isEmpty() || phraseWords.size > 4) return null
        val phrase = phraseWords.joinToString(" ")
        val verbs = if (kind == "PAYMENT") Regex("""^(kuduth\w*|koduth\w*|vangi\w*|vaangi\w*|thandh\w*|pott\w*|paid|pay|gave|received|anupp\w*)$""")
        else Regex("""^(pochu|poachu|vandh\w*|vanth\w*|sold|vithu\w*|out|in|add|eduth\w*)$""")
        val replacement = phraseWords.map { if (verbs.matches(it)) (if (kind == "PAYMENT") "handed over" else "noted") else it }.joinToString(" ")
        if (replacement == phrase) return null
        val l = languageOf(said, lang)
        val q = Question.Confirm(newKey(), phrase, "", PersonalMeaning.NotAction(replacement, kind), MemoryScope.BUSINESS, false, null)
        asking = q
        val what = if (kind == "PAYMENT") "payment" else "stock"
        return turn(l, KaiMood.CLARIFY,
            ta = "புரிஞ்சுது ஓனர். நான் தப்பா புரிஞ்சுகிட்டேன் — அந்த draft சேமிக்கல.\nஇந்த கடையில `$phrase` = $replacement, $what இல்ல-னு நினைவில் வைக்கட்டுமா?",
            tl = "Purinjuchu Owner. Naan thappa purinjukitten — andha draft save aagala.\nIndha business context-la `$phrase` = $replacement, $what illa-nu update pannava?",
            en = "Got it Owner. I misunderstood — that draft was not saved.\nIn this business, should I remember `$phrase` = $replacement, not a $what?",
            card = KaiCard(emptyList(), listOf(
                KaiButton(pick(l, "ஆமா, update", "Aama, update", "Yes, update"), KaiAction.LearnWord(q.key), primary = true),
                KaiButton(pick(l, "இல்ல", "Illa", "No"), KaiAction.NotThis(q.key)),
            )))
    }

    /** "potti na enna?", "anna yaaru?" — a short question about one of the owner's words (never a record's name). */
    private fun shortQuestion(text: String, entities: List<KnownEntity>, mem: KaiPrivateMemory): String? {
        val m = Regex("""(?i)^\s*[`'"‘“]?([\p{L}\p{M}]+(?:\s[\p{L}\p{M}]+)?)[`'"’”]?\s*-?\s*(?:(?:na|nna|naa|ன்னா|னா)\s*(enna|என்ன|yaaru|yaar)|(yaaru|யாரு))\s*\??\s*$""").find(text) ?: return null
        val word = m.groupValues[1].trim()
        val n = KaiPrivateMemory.normalize(word)
        if (entities.any { KaiPrivateMemory.normalize(it.name) == n || KaiPrivateMemory.normalize(it.name).split(' ').first() == n }) return null
        // A word Kai already reads (and the owner never taught) is a normal question.
        if (mem.find(n) == null && n.split(' ').all { KaiLexicon.knows(it) }) return null
        return word
    }

    /** Teaching sentences ("Kai, enga kadaiyila 'potti' na 1 box") → Kai repeats it and asks. */
    private suspend fun personalAsk(pt: com.shopai.app.brain.memory.PersonalTeach, original: String, lang: KaiLang, mem: KaiPrivateMemory): MemoryStep {
        val meaning = pt.meaning.let { m -> if (m is PersonalMeaning.Words) wordsOrAction(m.words) else m }
        val correction = differs(mem, pt.phrase, meaning)
        // Plain words and money / stock meanings keep their own questions (buttons per meaning).
        if (!correction && (meaning is PersonalMeaning.Words || meaning is PersonalMeaning.Action)) {
            val words = (pt.meaning as? PersonalMeaning.Words)?.words ?: (meaning as PersonalMeaning.Action).meaning.canonical
            return teachWordAsk(pt.phrase.trim('`', '\'', '"'), words, lang, mem, pt.scope, pt.sourceText, original)
        }
        return MemoryStep.Reply(confirmAsk(pt.phrase, meaning, original, pt.scope, correction, pt.sourceText, lang, mem))
    }

    /** Words that are one clear money / stock action become that action; anything else stays words. */
    private fun wordsOrAction(words: String): PersonalMeaning =
        KaiTeaching.actionOptions(words).singleOrNull()?.let { PersonalMeaning.Action(it) } ?: PersonalMeaning.Words(words)

    /** The owner already taught [phrase] something else (so this is a correction). */
    private fun differs(mem: KaiPrivateMemory, phrase: String, meaning: PersonalMeaning): Boolean {
        val old = mem.find(phrase) ?: return false
        return when (meaning) {
            is PersonalMeaning.Unit -> old.memoryType != MemoryType.UNIT_ALIAS || !old.meaningValue.equals(meaning.unit, true)
            is PersonalMeaning.ReminderTime -> old.memoryType != MemoryType.REMINDER_TERM || old.meaningValue != meaning.time
            is PersonalMeaning.Entity -> old.referenceEntityId != meaning.entity.id
            is PersonalMeaning.Action -> old.meaningType != meaning.meaning.name
            is PersonalMeaning.Words -> !old.meaningValue.equals(meaning.words, true)
            is PersonalMeaning.NotAction -> old.memoryType != MemoryType.CORRECTION
        }
    }

    private fun display(m: PersonalMeaning, lang: KaiLang): String = when (m) {
        is PersonalMeaning.Unit -> m.unit
        is PersonalMeaning.ReminderTime -> m.time
        is PersonalMeaning.Entity -> m.entity.name
        is PersonalMeaning.Action -> m.meaning.label(lang)
        is PersonalMeaning.Words -> m.words
        is PersonalMeaning.NotAction -> m.replacement
    }

    /** How a saved memory reads in the owner's list. */
    private fun shown(m: KaiMemory, lang: KaiLang): String = when (m.memoryType) {
        MemoryType.CORRECTION -> m.meaningValue + pick(lang, " (${m.meaningType.removePrefix("NOT_").lowercase()} இல்ல)", " (${m.meaningType.removePrefix("NOT_").lowercase()} illa)", " (not a ${m.meaningType.removePrefix("NOT_").lowercase()})")
        else -> m.meaning?.label(lang) ?: m.meaningValue
    }

    /** "Sari Owner 👍 Indha business-ku `potti` = 1 box-nu purinjukitten. Save pannava?" [Save] [Not now]. */
    private fun confirmAsk(phrase: String, meaning: PersonalMeaning, original: String, scope: MemoryScope, correction: Boolean, sourceText: String?, lang: KaiLang, mem: KaiPrivateMemory): KaiTurn {
        val p = phrase.trim().trim('`', '\'', '"', '‘', '’', '“', '”')
        // "Illai, …" about a word Kai hasn't saved yet is just a new teaching.
        val correction = correction && mem.find(p) != null
        val q = Question.Confirm(newKey(), p, original, meaning, scope, correction, sourceText)
        asking = q
        val v = display(meaning, lang)
        val where = if (scope == MemoryScope.OWNER) pick(lang, "உங்க எல்லா கடைக்கும்", "unga ella business-kum", "all your businesses")
        else pick(lang, "இந்த கடைக்கு", "indha business-ku", "this business")
        val text = when {
            correction -> {
                val old = mem.find(p)?.let { shown(it, lang) }.orEmpty()
                pick(lang,
                    ta = "புரிஞ்சுது ஓனர். இப்போ `$p` = $old.\n$where `$p` = $v-னு மாத்தட்டுமா?",
                    tl = "Purinjuchu Owner. Ippo `$p` = $old.\n${where.replaceFirstChar { it.uppercase() }} `$p` = $v-nu update pannava?",
                    en = "Got it Owner. Right now `$p` = $old.\nUpdate it to `$p` = $v for $where?")
            }
            meaning is PersonalMeaning.Unit -> pick(lang,
                ta = "சரி ஓனர் 👍 $where `$p` = 1 $v-னு புரிஞ்சுகிட்டேன். சேமிக்கட்டுமா?",
                tl = "Sari Owner 👍 ${where.replaceFirstChar { it.uppercase() }} `$p` = 1 $v-nu purinjukitten. Save pannava?",
                en = "Okay Owner 👍 For $where, `$p` = 1 $v. Save it?")
            else -> pick(lang,
                ta = "புரிஞ்சுது ஓனர். `$p` = $v-னு $where நினைவில் வைக்கட்டுமா?",
                tl = "Purinjuchu Owner. `$p` = $v-nu $where remember pannava?",
                en = "Got it Owner. Should I remember `$p` = $v for $where?")
        }
        return KaiTurn(ChatReply(text, KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(emptyList(), listOf(
            KaiButton(pick(lang, "சேமி", "Save", "Save"), KaiAction.LearnWord(q.key), primary = true),
            KaiButton(pick(lang, "இப்போ வேண்டாம்", "Not now", "Not now"), KaiAction.NotThis(q.key)),
        )))
    }

    /** The owner said yes: saved for this owner (this business, or all theirs when they said so), then the waiting message is read again. */
    private suspend fun saveConfirm(q: Question.Confirm, mem: KaiPrivateMemory, lang: KaiLang): MemoryStep {
        val src = q.sourceText
        val saved = when (val m = q.meaning) {
            is PersonalMeaning.Unit -> mem.teachUnit(q.phrase, m.unit, MemorySource.OWNER_CONFIRMED, q.scope, src)
            is PersonalMeaning.ReminderTime -> mem.teachReminderTerm(q.phrase, m.time, MemorySource.OWNER_CONFIRMED, q.scope, src)
            is PersonalMeaning.Entity -> mem.teachEntity(q.phrase, m.entity, MemorySource.OWNER_CONFIRMED, src)
            is PersonalMeaning.Action -> mem.teachMeaning(q.phrase, m.meaning, MemorySource.OWNER_CONFIRMED, q.scope, src)
            is PersonalMeaning.Words -> mem.teachWord(q.phrase, m.words, MemorySource.OWNER_CONFIRMED, q.scope, src)
            is PersonalMeaning.NotAction -> mem.teachCorrection(q.phrase, m.replacement, m.kind, MemorySource.OWNER_CONFIRMED, src)
        }
        learnedMemory(saved)
        if (q.original.isNotBlank()) mem.addExample(saved.id, q.original)
        lastPhrase = saved.normalizedPhrase
        val v = display(q.meaning, lang)
        val done = when {
            q.meaning is PersonalMeaning.NotAction -> pick(lang,
                ta = "சரி ஓனர் 👍 இனிமே `${q.phrase}`-ஐ ${(q.meaning as PersonalMeaning.NotAction).kind.lowercase()}-ஆ எடுக்க மாட்டேன்.",
                tl = "Done Owner 👍 Inime `${q.phrase}`-a ${(q.meaning as PersonalMeaning.NotAction).kind.lowercase()}-aa edukka maatten.",
                en = "Done Owner 👍 I won't take `${q.phrase}` as a ${(q.meaning as PersonalMeaning.NotAction).kind.lowercase()} again.")
            q.correction -> pick(lang,
                ta = "சரி ஓனர் 👍 மாத்திட்டேன்: `${q.phrase}` = $v.", tl = "Done Owner 👍 Update panniten: `${q.phrase}` = $v.", en = "Done Owner 👍 Updated: `${q.phrase}` = $v.")
            else -> pick(lang,
                ta = "சரி ஓனர் 👍 இனிமே `${q.phrase}`-னு சொன்னா $v-னு புரிஞ்சுக்குவேன்.",
                tl = "Done Owner 👍 Inime `${q.phrase}` nu sonna $v-nu purinjukkuven.",
                en = "Done Owner 👍 From now on `${q.phrase}` means $v to me.")
        }
        return after(done, q.original)
    }

    /** "potti meaning change pannu": what's the new meaning? */
    private fun changeAsk(phrase: String?, lang: KaiLang, mem: KaiPrivateMemory): MemoryStep {
        val m = phrase?.let { p -> mem.list().firstOrNull { KaiPrivateMemory.normalize(p) in it.phrases } }
            ?: return reply(lang, KaiMood.CLARIFY,
                ta = "ஓனர், அப்படி ஒரு வார்த்தை நான் நினைவில் வெச்சிருக்கல.",
                tl = "Owner, adhu maadhiri edhuvum naan remember pannala.",
                en = "Owner, I don't have that remembered.")
        asking = Question.NewMeaning(newKey(), m.triggerPhrase, "")
        return reply(lang, KaiMood.CLARIFY,
            ta = "`${m.triggerPhrase}`-க்கு புது அர்த்தம் என்ன ஓனர்? (இப்போ: ${shown(m, lang)})",
            tl = "`${m.triggerPhrase}`-ku new meaning enna Owner? (Ippo: ${shown(m, lang)})",
            en = "What's the new meaning of `${m.triggerPhrase}`, Owner? (Now: ${shown(m, lang)})")
    }

    /** "potti meaning marandhudu": asked first — [Forget] [Cancel]. */
    private fun forgetAsk(m: KaiMemory, lang: KaiLang): MemoryStep {
        val q = Question.ForgetConfirm(newKey(), m.triggerPhrase, "", m)
        asking = q
        return MemoryStep.Reply(turn(lang, KaiMood.CLARIFY,
            ta = "சரி ஓனர். `${m.triggerPhrase}` நினைவை நீக்கட்டுமா?",
            tl = "Sure Owner. `${m.triggerPhrase}` memory remove pannava?",
            en = "Sure Owner. Remove what `${m.triggerPhrase}` means?",
            card = KaiCard(emptyList(), listOf(
                KaiButton(pick(lang, "மறந்துடு", "Forget", "Forget"), KaiAction.LearnWord(q.key), primary = true),
                KaiButton(pick(lang, "ரத்து", "Cancel", "Cancel"), KaiAction.NotThis(q.key)),
            ))))
    }

    private suspend fun forgetNow(q: Question.ForgetConfirm, mem: KaiPrivateMemory, lang: KaiLang): MemoryStep {
        val removed = mem.forget(q.memory.normalizedPhrase)
        if (lastPhrase == q.memory.normalizedPhrase) lastPhrase = null
        return if (removed != null) reply(lang, KaiMood.NEUTRAL,
            ta = "சரி ஓனர், `${removed.triggerPhrase}` அர்த்தத்தை மறந்துட்டேன்.",
            tl = "Seri Owner, `${removed.triggerPhrase}` meaning-a marandhutten.",
            en = "Okay Owner, I've forgotten what `${removed.triggerPhrase}` meant.")
        else reply(lang, KaiMood.CLARIFY,
            ta = "ஓனர், அப்படி ஒரு வார்த்தை நான் நினைவில் வெச்சிருக்கல.",
            tl = "Owner, adhu maadhiri edhuvum naan remember pannala.",
            en = "Owner, I don't have that remembered.")
    }

    // ------------------------------------------------------------ internals

    private suspend fun answer(q: Question, text: String, lang: KaiLang, mem: KaiPrivateMemory?): MemoryStep? {
        if (mem == null) {
            asking = null
            return null
        }
        // "potti na box" … (not saved yet) … "Colgate 2 potti vandhudhu": the word is used before the owner said Save.
        // Kai asks to save it first, then reads this sentence with it (a new teaching of the word is not a use of it).
        if (q is Question.Confirm && !text.trim().endsWith("?") && KaiPrivateMemory.contains(text, KaiPrivateMemory.normalize(q.phrase)) &&
            KaiPersonalTeaching.parse(text, access.entities()) == null && KaiTeaching.parse(text, access.entities()) == null &&
            !KaiTeaching.isYes(text) && !KaiTeaching.isNo(text)) {
            val single = KaiPrivateMemory.normalize(text) == KaiPrivateMemory.normalize(q.phrase)
            val pending = if (single) q else q.copy(original = text)
            asking = pending
            val v = display(q.meaning, lang)
            return MemoryStep.Reply(turn(lang, KaiMood.CLARIFY,
                ta = "ஓனர், `${q.phrase}` = $v-னு இன்னும் சேமிக்கல. சேமிக்கட்டுமா?" + if (single) "" else " சேமிச்சா இதை $v-ஆ எடுத்துக்குவேன்.",
                tl = "Owner, `${q.phrase}` = $v-nu innum save pannala. Save pannava?" + if (single) "" else " Save pannina idha $v-aa eduthukkuven.",
                en = "Owner, `${q.phrase}` = $v isn't saved yet. Save it?" + if (single) "" else " Once saved I'll read this with it.",
                card = KaiCard(emptyList(), listOf(
                    KaiButton(pick(lang, "சேமி", "Save", "Save"), KaiAction.LearnWord(pending.key), primary = true),
                    KaiButton(pick(lang, "இப்போ வேண்டாம்", "Not now", "Not now"), KaiAction.NotThis(pending.key)),
                ))))
        }
        // Only a short answer answers Kai's question; a new sentence (or a question) is a new message.
        if (text.trim().endsWith("?") || KaiPrivateMemory.normalize(text).split(' ').size > (if (q is Question.Word) 6 else 4)) {
            asking = null
            return null
        }
        val yes = KaiTeaching.isYes(text) || Regex("""(?i)^\s*(save|save pannu|save pannunga|remember pannu|update pannu|forget|forget pannu|remove pannu|marandhudu)\s*$""").matches(text)
        val no = KaiTeaching.isNo(text) || Regex("""(?i)^\s*(not now|cancel|ippo venam|vendam|venam)\s*$""").matches(text)
        when (q) {
            is Question.Unit -> {
                KaiPersonalTeaching.unitAnswer(text)?.let { unit ->
                    asking = null
                    return MemoryStep.Reply(confirmAsk(q.phrase, PersonalMeaning.Unit(unit), q.original, MemoryScope.BUSINESS, false, null, lang, mem))
                }
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
            }
            is Question.Confirm -> {
                if (yes) {
                    asking = null
                    return saveConfirm(q, mem, lang)
                }
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
            }
            is Question.ForgetConfirm -> {
                if (yes) {
                    asking = null
                    return forgetNow(q, mem, lang)
                }
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
            }
            is Question.NewMeaning -> {
                if (no) {
                    asking = null
                    return declined(q, mem, lang)
                }
                val words = KaiTeaching.wordAnswer(text) ?: KaiPrivateMemory.normalize(text)
                if (words.isNotEmpty() && words.split(' ').size <= 5) {
                    asking = null
                    val meaning = KaiPersonalTeaching.classify(words, access.entities()).let { m -> if (m is PersonalMeaning.Words) wordsOrAction(m.words) else m }
                    return MemoryStep.Reply(confirmAsk(q.phrase, meaning, q.original, MemoryScope.BUSINESS, true, null, lang, mem))
                }
            }
            is Question.Pick -> {
                val w = KaiPrivateMemory.normalize(text).replace(Regex("""\s*(?:meaning|word|a|ah)$"""), "").trim()
                val m = mem.list().firstOrNull { w in it.phrases }
                asking = null
                if (m != null) return if (q.forEdit) changeAsk(m.triggerPhrase, lang, mem) else forgetAsk(m, lang)
                if (!no) return reply(lang, KaiMood.CLARIFY,
                    ta = "ஓனர், `$w`-னு நான் எதுவும் நினைவில் வெச்சிருக்கல.", tl = "Owner, `$w`-nu naan edhuvum remember pannala.", en = "Owner, I don't have `$w` remembered.")
                return declined(q, mem, lang)
            }
            is Question.Meaning -> {
                val said = KaiTeaching.meaningIn(text)
                val meaning = said ?: q.guess.takeIf { yes }
                if (meaning != null) {
                    asking = null
                    learnedMemory(mem.teachMeaning(q.phrase, meaning, MemorySource.OWNER_CONFIRMED, q.scope, q.sourceText))
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
                    learnedMemory(mem.teachEntity(q.phrase, chosen, MemorySource.OWNER_CONFIRMED))
                    lastPhrase = q.phrase
                    return after(learned(q.phrase, chosen.name, lang), q.original)
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
            is Question.ForgetConfirm -> return reply(lang, KaiMood.NEUTRAL,
                ta = "சரி ஓனர், `${q.phrase}` அப்படியே இருக்கு.", tl = "Seri Owner, `${q.phrase}` appadiye irukku.", en = "Okay Owner, `${q.phrase}` stays as it is.")
            is Question.Pick -> return reply(lang, KaiMood.NEUTRAL,
                ta = "சரி ஓனர், எதுவும் மாத்தல.", tl = "Seri Owner, edhuvum maathala.", en = "Okay Owner, nothing changed.")
            is Question.NewMeaning -> return reply(lang, KaiMood.NEUTRAL,
                ta = "சரி ஓனர், `${q.phrase}` பழைய அர்த்தத்துலயே இருக்கு.", tl = "Seri Owner, `${q.phrase}` pazhaya meaning-laye irukku.", en = "Okay Owner, `${q.phrase}` keeps its old meaning.")
            is Question.Confirm -> if (q.correction) return reply(lang, KaiMood.NEUTRAL,
                ta = "சரி ஓனர், எதுவும் மாத்தல.", tl = "Seri Owner, edhuvum update pannala.", en = "Okay Owner, nothing was changed.")
            else -> Unit
        }
        when (q) {
            is Question.Unit -> mem?.rejected(q.phrase, "UNIT")
            is Question.Confirm -> mem?.rejected(q.phrase, "CONFIRM")
            is Question.ForgetConfirm, is Question.NewMeaning, is Question.Pick -> Unit
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
        learnedMemory(mem.teachWord(q.phrase, q.means, MemorySource.OWNER_CONFIRMED, q.scope, q.sourceText))
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
        learnedMemory(mem.teachMeaning(q.phrase, q.meaning, MemorySource.OWNER_CREATED))
        lastPhrase = q.phrase
        return reply(lang, KaiMood.SUCCESS, ta = learned(q.phrase, q.meaning.label(lang), KaiLang.TAMIL), tl = learned(q.phrase, q.meaning.label(lang), KaiLang.TANGLISH), en = learned(q.phrase, q.meaning.label(lang), KaiLang.ENGLISH))
    }

    private suspend fun teach(cmd: TeachCommand, lang: KaiLang, mem: KaiPrivateMemory, entities: List<KnownEntity>): MemoryStep = when (cmd) {
        // Kai repeats it and saves after a yes — unless the owner already said "from now on / remember pannu".
        is TeachCommand.Teach -> if (!cmd.sure) {
            val meaning = when (val t = cmd.target) {
                is TeachTarget.Meaning -> PersonalMeaning.Action(t.meaning)
                is TeachTarget.Entity -> PersonalMeaning.Entity(t.entity)
            }
            MemoryStep.Reply(confirmAsk(cmd.phrase, meaning, "", MemoryScope.BUSINESS, cmd.correction || differs(mem, cmd.phrase, meaning), null, lang, mem))
        } else {
            val (value, saved) = when (val t = cmd.target) {
                is TeachTarget.Meaning -> t.meaning.label(lang) to learnedMemory(mem.teachMeaning(cmd.phrase, t.meaning, MemorySource.OWNER_CREATED))
                is TeachTarget.Entity -> t.entity.name to learnedMemory(mem.teachEntity(cmd.phrase, t.entity, MemorySource.OWNER_CREATED))
            }
            lastPhrase = saved.normalizedPhrase
            asking = null
            if (cmd.correction) reply(lang, KaiMood.SUCCESS,
                ta = "சரி ஓனர். இந்த கடையில `${saved.triggerPhrase}` = $value-னு நினைவில் வெச்சுக்கறேன்.",
                tl = "Okay Owner. Indha business-la `${saved.triggerPhrase}` = $value-nu remember pannikiren.",
                en = "Okay Owner. In this business `${saved.triggerPhrase}` = $value — I'll remember that.")
            else reply(lang, KaiMood.SUCCESS, ta = learned(saved.triggerPhrase, value, KaiLang.TAMIL), tl = learned(saved.triggerPhrase, value, KaiLang.TANGLISH), en = learned(saved.triggerPhrase, value, KaiLang.ENGLISH))
        }
        is TeachCommand.Change -> changeAsk(cmd.phrase ?: lastPhrase, lang, mem)
        is TeachCommand.Correct -> {
            val meaning = KaiPersonalTeaching.classify(cmd.meaning, entities).let { m -> if (m is PersonalMeaning.Words) wordsOrAction(m.words) else m }
            MemoryStep.Reply(confirmAsk(cmd.phrase, meaning, "", MemoryScope.BUSINESS, mem.find(cmd.phrase) != null, null, lang, mem))
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
            val found = phrase?.let { p -> mem.list().firstOrNull { KaiPrivateMemory.normalize(p) in it.phrases } }
            if (found != null) forgetAsk(found, lang)
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
            // Only what this owner confirmed (business words, then their words for all businesses) — never another owner's.
            val list = mem.usable().filter { it.memoryType != MemoryType.PREFERENCE }
            if (list.isEmpty()) reply(lang, KaiMood.NEUTRAL,
                ta = "இன்னும் உங்க கடை வார்த்தை எதுவும் கத்துக்கல ஓனர்.",
                tl = "Owner, innum unga shop words edhuvum kathukkala.",
                en = "I haven't learned any of your shop's words yet, Owner.")
            else {
                val lines = list.take(20).mapIndexed { i, m -> "${i + 1}. `${m.triggerPhrase}` = ${shown(m, lang)}" }
                val head = pick(lang,
                    ta = "ஓனர், நீங்க எனக்கு ${list.size} விஷயம் சொல்லிக்கொடுத்திருக்கீங்க:",
                    tl = "Owner, neenga enakku ${list.size} things teach pannirukeenga:",
                    en = "Owner, you've taught me ${list.size} things:")
                MemoryStep.Reply(KaiTurn(
                    ChatReply(head + "\n\n" + lines.joinToString("\n"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
                    KaiCard(lines, listOf(
                        KaiButton(pick(lang, "மாற்று", "Edit", "Edit"), KaiAction.ManageMemory("EDIT")),
                        KaiButton(pick(lang, "மறந்துடு", "Forget", "Forget"), KaiAction.ManageMemory("FORGET")),
                        KaiButton(pick(lang, "அப்படியே வை", "Keep", "Keep"), KaiAction.ManageMemory("KEEP"), primary = true),
                    )),
                ))
            }
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
