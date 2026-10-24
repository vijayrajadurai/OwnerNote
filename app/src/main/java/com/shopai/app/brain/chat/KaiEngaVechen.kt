package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.tools.KaiKept
import com.shopai.app.brain.tools.KaiKeptWords
import com.shopai.app.brain.tools.KaiRelations
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.KaiWordSound
import com.shopai.app.brain.tools.KeptItem
import com.shopai.app.brain.tools.KeptPlace
import com.shopai.app.util.NameSound
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * Kai's "Enga Vechen?" (owner, 24 Oct 2026): the owner tells Kai where a thing is kept and asks for it later — typed or by mic, in
 * Tamil, Tanglish or English. [KaiKept] reads the line; this keeps the conversation: a small Confirm card before anything is written
 * (save, move, closed), "Save aagiduchu" only after the table said it saved, a question card for a word of two meanings, the place
 * asked when it wasn't said, "X illa Y" on the open card, and the full list the way the owner's list says
 * (`enga_vechen_words.json` › list_all_answer). Nothing here touches the books.
 */
internal class KaiEngaVechen(private val tools: KaiTools, private val now: () -> LocalDateTime) {

    private enum class Step { SAVE, MOVE, CLOSE }

    private inner class Draft(val key: String, val step: Step, val item: KeptItem, val before: KeptItem?) { val turn = turns }

    /** Lines heard: an open card answers "seri" / "illa" only right after it was shown. */
    private var turns = 0L

    private var draft: Draft? = null
    /** "kannadi" — specs or a mirror: the line, waiting for the owner's pick. */
    private var meaning: Pair<String, KaiKept.Read>? = null
    /** "Passport vechen" with no place, or "Idam maathu": the thing waiting for its place. */
    private var placeFor: Pair<KaiKept.Read, String?>? = null
    /** Kai's last answer was about kept things ("full list" right after is this list). */
    private var recent = false

    fun reset() { draft = null; meaning = null; placeFor = null; recent = false }

    private val confirmWords = Regex("""(?i)^\s*(confirm|confirm pannu|save|save pannu|seri|sari|ok|okay|aama|ama|yes|podu|pannu|ஆமா|சரி|ஓகே|கன்ஃபர்ம்|சேவ் பண்ணு)\s*[.!]?\s*$""")
    private val cancelWords = Regex("""(?i)^\s*(cancel|vendam|venam|venaam|vendaam|stop|no|illa|வேணாம்|வேண்டாம்|கேன்சல்|இல்ல|ஸ்டாப்)\s*[.!]?\s*$""")
    private val swap = Regex("""(?i)^\s*(?:illa\s+)?([\p{L}\p{M}]+(?:\s[\p{L}\p{M}]+)?)\s+(?:illa|illai|alla|not|இல்ல|இல்லை)\s*,?\s+([\p{L}\p{M}\d]+(?:\s[\p{L}\p{M}\d]+){0,2})\s*$""")

    /** The answer to [line] when it is about a kept thing (or the open card's), else null — the line is Kai's other lines'. */
    suspend fun answer(line: String, lang: KaiLang, people: List<String>, products: List<String>): KaiTurn? {
        val wasRecent = recent
        recent = false
        turns++
        draft?.takeIf { turns - it.turn > 2 }?.let { draft = null }
        meaning?.let { (_, r) ->
            meaning = null
            val pick = r.meanings.firstOrNull { name -> said(line, name) }
            if (pick != null) return mine(go(r.copy(itemKey = pick, meanings = emptyList()), lang))
        }
        placeFor?.let { (r, id) ->
            placeFor = null
            if (!cancelWords.matches(line)) {
                val p = KaiKept.read("${r.item} $line vechen", people)
                if (p != null && p.place.isNotEmpty() || p?.given == true) {
                    val r2 = r.copy(place = p!!.place, person = p.person ?: r.person, given = p.given || r.given)
                    val existing = id?.let { i -> kept().firstOrNull { it.id == i } }
                    return mine(if (existing != null) moveDraft(existing, r2, lang) else saveDraft(r2, lang))
                }
            }
        }
        draft?.let { d ->
            if (confirmWords.matches(line)) return mine(confirm(d.key, lang))
            if (cancelWords.matches(line)) return mine(cancel(d.key, lang))
            swap.find(line.trim().trimEnd('.', '!', '?'))?.let { m -> corrected(d, m.groupValues[1], m.groupValues[2], lang)?.let { return mine(it) } }
        }
        val r = KaiKept.read(line, people, recent = wasRecent) ?: return null
        // Asked about a thing that is neither kept nor in the owner's list ("supermarket enga irukku", "Colgate enga irukku"): not this.
        if (r.intent == KaiKept.Intent.ASK && r.itemKey == null && r.meanings.isEmpty() && matches(r, kept()).isEmpty()) return null
        // A shop product said with a verb and no place / person: a books line.
        if (r.intent != KaiKept.Intent.LIST && r.intent != KaiKept.Intent.LIST_ALL && r.itemKey == null && r.place.isEmpty() && !r.given &&
            products.any { p -> r.item?.let { it.equals(p, true) || NameSound.same(it, p) } == true }) return null
        return mine(go(r, lang))
    }

    private fun mine(t: KaiTurn): KaiTurn { recent = true; return t }

    private suspend fun go(r: KaiKept.Read, lang: KaiLang): KaiTurn {
        val items = kept()
        if (r.meanings.size > 1) {
            // The owner's own word already kept: that one, no question.
            val same = items.filter { it.active && r.item != null && it.item.equals(r.item, true) }
            if (same.isEmpty() || r.intent == KaiKept.Intent.SAVE) return askMeaning(r, lang)
        }
        return when (r.intent) {
            KaiKept.Intent.SAVE -> if (r.place.isEmpty() && !r.given) askPlace(r, null, lang) else saveDraft(r, lang)
            KaiKept.Intent.ASK -> where(r, items, lang)
            KaiKept.Intent.MOVE -> matches(r, items).firstOrNull()?.let { moveDraft(it, r, lang) } ?: saveDraft(r, lang, wasNew = true)
            KaiKept.Intent.TAKEN -> matches(r, items).firstOrNull()?.let { closeDraft(it, lang) }
                ?: plain(lang, ta = "${show(r.item)} லிஸ்ட்-ல இல்ல — எதுவும் மாத்தல.", tl = "${show(r.item)} list-la illa — edhuvum maathala.",
                    en = "${show(r.item)} isn't on your list — nothing changed.")
            KaiKept.Intent.LIST -> inPlace(r.place, items, lang)
            KaiKept.Intent.LIST_ALL -> everything(r.filter ?: KaiKept.Filter(), items, lang)
        }
    }

    // ------------------------------------------------------------------ drafts (nothing written before Confirm)

    private fun newKey() = "kept-" + UUID.randomUUID().toString().take(8)

    private fun saveDraft(r: KaiKept.Read, lang: KaiLang, wasNew: Boolean = false): KaiTurn {
        val at = whenKept(r.time)
        val item = KeptItem(UUID.randomUUID().toString(), r.item.orEmpty().trim(), r.itemKey, r.place, if (r.given) r.person else null,
            if (!r.given) r.person else null, null, at, now(), KeptItem.Status.ACTIVE)
        val d = Draft(newKey(), Step.SAVE, item, null)
        draft = d
        val first = if (wasNew) pick(lang, ta = "${show(item.item)} முன்னாடி save பண்ணல — புதுசா வைக்கவா? ", tl = "${show(item.item)} munnaadi save pannala — pudhusa vekkava? ",
            en = "${show(item.item)} wasn't saved before — save it now? ") else ""
        return card(d, lang, first + pick(lang, ta = "${show(item.item)} — ${where(item)}. Confirm பண்ணுங்க, ஞாபகம் வச்சுக்கறேன்.",
            tl = "${show(item.item)} — ${where(item)}. Confirm pannunga, nyabagam vechukkaren.",
            en = "${show(item.item)} — ${where(item)}. Confirm and I'll remember it."))
    }

    private fun moveDraft(old: KeptItem, r: KaiKept.Read, lang: KaiLang): KaiTurn {
        val moved = old.copy(placeParts = r.place, person = if (r.given) r.person else null, updatedAt = now(),
            history = old.history + KeptPlace(old.placeParts, old.person, now()))
        val d = Draft(newKey(), Step.MOVE, moved, old)
        draft = d
        return card(d, lang, pick(lang, ta = "${show(old.item)} இப்போ ${where(moved)}. முன்னாடி ${at(old.placeParts, old.person, lang)} இருந்துச்சு. மாத்தவா?",
            tl = "${show(old.item)} ippo ${where(moved)}. Munnaadi ${at(old.placeParts, old.person, lang)} irundhuchu. Maathava?",
            en = "${show(old.item)} is now ${where(moved)}. It was ${where(old)} before. Change it?"))
    }

    private fun closeDraft(old: KeptItem, lang: KaiLang): KaiTurn {
        val d = Draft(newKey(), Step.CLOSE, old.copy(status = KeptItem.Status.CLOSED, updatedAt = now()), old)
        draft = d
        return card(d, lang, pick(lang, ta = "${show(old.item)} (${where(old)}) — லிஸ்ட்-ல இருந்து எடுக்கவா?",
            tl = "${show(old.item)} (${where(old)}) — list-la irundhu edukkava?", en = "${show(old.item)} (${where(old)}) — take it off your list?"))
    }

    private fun card(d: Draft, lang: KaiLang, text: String): KaiTurn {
        val i = d.item
        val lines = buildList {
            add(pick(lang, ta = "பொருள்: ", tl = "Porul: ", en = "Item: ") + show(i.item))
            if (i.placeParts.isNotEmpty()) add(pick(lang, ta = "இடம்: ", tl = "Idam: ", en = "Place: ") + placeText(i.placeParts))
            i.person?.let { add(pick(lang, ta = "யார் கிட்ட: ", tl = "Yaar kitta: ", en = "With: ") + who(it, lang)) }
            i.keptBy?.let { add(pick(lang, ta = "யாரோடது: ", tl = "Yaaroda: ", en = "Whose: ") + who(it, lang)) }
            d.before?.takeIf { d.step == Step.MOVE }?.let { add(pick(lang, ta = "முன்னாடி: ", tl = "Munnaadi: ", en = "Before: ") + placeOrPerson(it.placeParts, it.person, lang)) }
        }
        val headline = when (d.step) {
            Step.SAVE -> pick(lang, ta = "ஞாபகம் வச்சுக்கவா?", tl = "Nyabagam vechukkava?", en = "Remember this?")
            Step.MOVE -> pick(lang, ta = "இடம் மாத்தவா?", tl = "Idam maathava?", en = "Change the place?")
            Step.CLOSE -> pick(lang, ta = "எடுத்தாச்சா?", tl = "Eduthaachaa?", en = "Taken out?")
        }
        return KaiTurn(ChatReply(text, KaiMood.NEUTRAL, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(lines, listOf(
            KaiButton(pick(lang, ta = "Confirm", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmKept(d.key), primary = true),
            KaiButton(pick(lang, ta = "ரத்து", tl = "Cancel", en = "Cancel"), KaiAction.CancelKept(d.key)),
        ), headline = headline))
    }

    /** "almirah illa cupboard" on the open card: that word changed, the card shown again (still nothing written). */
    private fun corrected(d: Draft, wrong: String, right: String, lang: KaiLang): KaiTurn? {
        val i = d.item
        val parts = i.placeParts.toMutableList()
        val at = parts.indexOfFirst { same(it, wrong) }
        val changed = when {
            at >= 0 -> i.copy(placeParts = parts.also { it[at] = right })
            same(i.item, wrong) || i.item.split(' ').any { same(it, wrong) } -> i.copy(item = if (same(i.item, wrong)) right else i.item.split(' ').joinToString(" ") { if (same(it, wrong)) right else it },
                itemKey = KaiKept.read("$right almirah la vechen")?.itemKey)
            i.person != null && same(i.person, wrong) -> i.copy(person = right.replaceFirstChar { it.titlecase(Locale.ROOT) })
            else -> return null
        }
        val nd = Draft(newKey(), d.step, changed, d.before)
        draft = nd
        return card(nd, lang, pick(lang, ta = "சரி, $wrong இல்ல — $right. ${show(changed.item)} — ${where(changed)}. Confirm பண்ணுங்க.",
            tl = "Seri, $wrong illa — $right. ${show(changed.item)} — ${where(changed)}. Confirm pannunga.",
            en = "Okay, not $wrong — $right. ${show(changed.item)} — ${where(changed)}. Please confirm."))
    }

    suspend fun act(action: KaiAction, lang: KaiLang): KaiTurn? = when (action) {
        is KaiAction.ConfirmKept -> mine(confirm(action.key, lang))
        is KaiAction.CancelKept -> mine(cancel(action.key, lang))
        is KaiAction.KeptMeaning -> meaning?.takeIf { it.first == action.key }?.let { (_, r) -> meaning = null; mine(go(r.copy(itemKey = action.name, meanings = emptyList()), lang)) }
        is KaiAction.KeptClose -> kept().firstOrNull { it.id == action.id && it.active }?.let { mine(closeDraft(it, lang)) }
        is KaiAction.KeptMove -> kept().firstOrNull { it.id == action.id && it.active }?.let { k ->
            mine(askPlace(KaiKept.Read(KaiKept.Intent.MOVE, k.item, k.itemKey), k.id, lang))
        }
        else -> null
    }

    private suspend fun confirm(key: String, lang: KaiLang): KaiTurn {
        val d = draft?.takeIf { it.key == key } ?: return plain(lang, ta = "அந்த card பழசு — திரும்ப சொல்லுங்க.", tl = "Andha card pazhasu — thirumba sollunga.",
            en = "That card is old — please say it again.")
        draft = null
        val ok = runCatching { tools.saveKept(d.item) }.getOrDefault(false)
        if (!ok) return KaiTurn(ChatReply(pick(lang, ta = "Save ஆகல — எதுவும் மாறல. இன்னொரு தடவ try பண்ணுங்க.", tl = "Save aagala — edhuvum maaRala. Innoru thadava try pannunga.",
            en = "It didn't save — nothing changed. Please try again."), KaiMood.ERROR, ChatIntent.GENERAL_BUSINESS_QUERY))
        val i = d.item
        val text = when (d.step) {
            Step.SAVE -> pick(lang, ta = "Save ஆயிடுச்சு ✓ ${show(i.item)} — ${where(i)}. “${show(i.item)} எங்க?”னு கேட்டா சொல்லுவேன்.",
                tl = "Save aagiduchu ✓ ${show(i.item)} — ${where(i)}. “${show(i.item)} enga?” nu ketta solluven.",
                en = "Saved ✓ ${show(i.item)} — ${where(i)}. Ask “where is ${show(i.item)}?” any time.")
            Step.MOVE -> pick(lang, ta = "மாத்திட்டேன் ✓ ${show(i.item)} இப்போ ${where(i)}.", tl = "Maathitten ✓ ${show(i.item)} ippo ${where(i)}.",
                en = "Changed ✓ ${show(i.item)} is now ${where(i)}.")
            Step.CLOSE -> pick(lang, ta = "சரி ✓ ${show(i.item)} லிஸ்ட்-ல இருந்து எடுத்துட்டேன். “பழைய லிஸ்ட் காட்டு”னு சொன்னா பாக்கலாம்.",
                tl = "Seri ✓ ${show(i.item)} list-la irundhu eduthutten. “Pazhaya list kaattu” nu sonna paakalaam.",
                en = "Done ✓ ${show(i.item)} is off your list. Say “show old list” to see it.")
        }
        return KaiTurn(ChatReply(text, KaiMood.SUCCESS, ChatIntent.GENERAL_BUSINESS_QUERY))
    }

    private fun cancel(key: String, lang: KaiLang): KaiTurn {
        if (draft?.key == key) draft = null
        return plain(lang, ta = "சரி, எதுவும் save பண்ணல.", tl = "Seri, edhuvum save pannala.", en = "Okay, nothing was saved.")
    }

    // ------------------------------------------------------------------ questions

    private fun askMeaning(r: KaiKept.Read, lang: KaiLang): KaiTurn {
        val key = newKey()
        meaning = key to r
        val opts = r.meanings.joinToString(pick(lang, ta = "-ஆ, ", tl = "-aa, ", en = " or ")) { meaningName(it) }
        return KaiTurn(ChatReply(pick(lang, ta = "“${r.item}” — $opts-ஆ?", tl = "“${r.item}” — $opts-aa?", en = "“${r.item}” — $opts?"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), r.meanings.map { KaiButton(meaningName(it), KaiAction.KeptMeaning(key, it)) }))
    }

    private fun askPlace(r: KaiKept.Read, id: String?, lang: KaiLang): KaiTurn {
        placeFor = r to id
        return KaiTurn(ChatReply(pick(lang, ta = "${show(r.item)} எங்க வச்சீங்க? இடம் சொல்லுங்க — உதா: “அலமாரி மேல drawer-ல”.",
            tl = "${show(r.item)} enga vecheenga? Idam sollunga — eg: “almirah mela drawer la”.",
            en = "Where did you keep ${show(r.item)}? Say the place — e.g. “almirah top drawer”."), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY))
    }

    private fun where(r: KaiKept.Read, items: List<KeptItem>, lang: KaiLang): KaiTurn {
        val found = matches(r, items)
        if (found.isEmpty()) {
            // Not kept: said plainly, with the kept things that sound closest.
            val close = items.filter { it.active }.filter { k -> k.item.split(' ').any { w -> r.item.orEmpty().split(' ').any { a -> KaiWordSound.near(w, a) || NameSound.same(w, a) } } ||
                (r.itemKey != null && KaiKeptWords.groupOf(k.itemKey) != null && KaiKeptWords.groupOf(k.itemKey) == KaiKeptWords.groupOf(r.itemKey)) }.take(3)
            val text = pick(lang, ta = "${show(r.item)} எங்க வச்சீங்கன்னு இன்னும் சொல்லல.", tl = "${show(r.item)} enga vecheenga nu innum sollala.",
                en = "You haven't told me where ${show(r.item)} is yet.") + if (close.isNotEmpty())
                pick(lang, ta = " பக்கத்துல இருக்கறது: ", tl = " Kittatha irukkuradhu: ", en = " Closest: ") + close.joinToString(", ") { show(it.item) } + "." else ""
            return KaiTurn(ChatReply(text, KaiMood.NEUTRAL, ChatIntent.GENERAL_BUSINESS_QUERY),
                close.takeIf { it.isNotEmpty() }?.let { c -> KaiCard(emptyList(), c.map { KaiButton(show(it.item), KaiAction.Say("${it.item} enga")) }) })
        }
        val k = found.first()
        val text = found.joinToString("\n") { line(it, lang) }
        val before = k.history.lastOrNull()?.let {
            "\n" + pick(lang, ta = "(முன்னாடி ${at(it.parts, it.person, lang)} இருந்துச்சு)", tl = "(Munnaadi ${at(it.parts, it.person, lang)} irundhuchu)",
                en = "(It was ${placeOrPerson(it.parts, it.person, lang)} before)")
        }.orEmpty()
        return KaiTurn(ChatReply(text + before, KaiMood.NEUTRAL, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(emptyList(), itemButtons(k, lang)))
    }

    private fun itemButtons(k: KeptItem, lang: KaiLang) = listOf(
        KaiButton(pick(lang, ta = "இடம் மாத்து", tl = "Idam maathu", en = "Move"), KaiAction.KeptMove(k.id)),
        KaiButton(pick(lang, ta = "எடுத்துட்டேன்", tl = "Eduthuten", en = "Taken"), KaiAction.KeptClose(k.id)),
        KaiButton(pick(lang, ta = "Photo சேர்", tl = "Photo add", en = "Add photo"), KaiAction.OpenRecord("KEPT", k.id)),
    )

    /** "Passport: Bedroom almirah, mela drawer — 12 Aug vechinga" / "Laptop Ravi kitta — 12 naal aachu". */
    private fun line(k: KeptItem, lang: KaiLang): String {
        val date = date(k.updatedAt.takeIf { k.history.isNotEmpty() } ?: k.createdAt, lang)
        return if (k.person != null && k.placeParts.isEmpty()) {
            val days = ChronoUnit.DAYS.between(k.createdAt.toLocalDate(), now().toLocalDate())
            pick(lang, ta = "${show(k.item)} ${who(k.person, lang)} கிட்ட இருக்கு — $date குடுத்தீங்க ($days நாள் ஆச்சு).",
                tl = "${show(k.item)} ${who(k.person, lang)} kitta irukku — $date kuduthinga ($days naal aachu).",
                en = "${show(k.item)} is with ${who(k.person, lang)} — given on $date ($days days ago).")
        } else pick(lang, ta = "${show(k.item)}: ${placeOrPerson(k.placeParts, k.person, lang)} — $date வச்சீங்க.",
            tl = "${show(k.item)}: ${placeOrPerson(k.placeParts, k.person, lang)} — $date vechinga.",
            en = "${show(k.item)}: ${placeOrPerson(k.placeParts, k.person, lang)} — kept on $date.")
    }

    private fun inPlace(place: List<String>, items: List<KeptItem>, lang: KaiLang): KaiTurn {
        val here = items.filter { k -> k.active && place.all { p -> k.placeParts.any { same(it, p) } } }.sortedByDescending { it.updatedAt }
        val name = placeText(place)
        if (here.isEmpty()) return plain(lang, ta = "$name-ல எதுவும் வச்சதா சொல்லல.", tl = "$name la edhuvum vechadha sollala.", en = "Nothing kept in $name that you told me.")
        return KaiTurn(ChatReply(pick(lang, ta = "$name-ல ${here.size} பொருள்: ", tl = "$name la ${here.size} porul: ", en = "${here.size} in $name: ") +
            here.joinToString(", ") { show(it.item) } + ".", KaiMood.NEUTRAL, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(here.map { "${show(it.item)} — ${placeText(it.placeParts)} — ${date(it.createdAt, lang)}" }, listOf(fullList(lang))))
    }

    // ------------------------------------------------------------------ the full list (list_all_answer)

    private fun everything(f: KaiKept.Filter, items: List<KeptItem>, lang: KaiLang): KaiTurn {
        if (items.none { it.active } && !f.closed) return plain(lang,
            ta = "இன்னும் எதுவும் ஞாபகம் வச்சிக்க சொல்லல. உதாரணம்: “கை, பாஸ்போர்ட் அலமாரி ல வச்சேன்”னு சொல்லுங்க.",
            tl = "Innum edhuvum nyabagam vechikka sollala. Example: \"Kai, passport almirah la vechen\" nu sollunga.",
            en = "You haven't asked me to remember anything yet. Example: say \"Kai, passport almirah la vechen\".")
        val today = now().toLocalDate()
        val shown = items.filter { k ->
            (f.closed || k.active) &&
                (f.person == null || listOfNotNull(k.person, k.keptBy).any { samePerson(it, f.person) }) &&
                (!f.givenToPeople || k.person != null) &&
                (f.type == null || KaiKeptWords.groupOf(k.itemKey) == f.type) &&
                f.room.all { r -> k.placeParts.any { same(it, r) } } &&
                inTime(k.createdAt.toLocalDate(), f.time, today)
        }.sortedByDescending { it.updatedAt }
        if (shown.isEmpty()) return plain(lang, ta = "அப்படி எதுவும் இல்ல — மொத்தம் ${items.count { it.active }} பொருள் இருக்கு.",
            tl = "Appadi edhuvum illa — motham ${items.count { it.active }} porul irukku.", en = "Nothing like that — ${items.count { it.active }} things in all.")
        // Groups: "Aatkal kitta" first, then each top place; inside, newest first.
        val groups = com.shopai.app.brain.tools.KeptGroups.of(shown)
        val people = groups.firstOrNull { it.withPeople }?.items.orEmpty()
        val placed = groups.filter { !it.withPeople }
        val counts = placed.map { g -> "${g.items.size} ${g.top?.lowercase(Locale.ROOT) ?: pick(lang, ta = "இடம் சொல்லாதது", tl = "idam sollaadhadhu", en = "no place")}" }
        val persons = people.mapNotNull { it.person?.lowercase(Locale.ROOT) }.distinct().size
        val summary = pick(lang,
            ta = "நீங்க ${shown.size} பொருள் ஞாபகம் வச்சிக்க சொன்னீங்க — " + (counts + listOfNotNull(if (persons > 0) "$persons பேர் கிட்ட குடுத்தது" else null)).joinToString(", ") + ".",
            tl = "Neenga ${shown.size} porul nyabagam vechikka sonneenga — " + (counts + listOfNotNull(if (persons > 0) "$persons per kitta kuduthadhu" else null)).joinToString(", ") + ".",
            en = "You asked me to remember ${shown.size} things — " + (counts + listOfNotNull(if (persons > 0) "given to $persons ${if (persons == 1) "person" else "people"}" else null)).joinToString(", ") + ".")
        val long = shown.size > 10
        val lines = buildList {
            if (people.isNotEmpty()) {
                add(pick(lang, ta = "▾ ஆட்கள் கிட்ட (${people.size})", tl = "▾ Aatkal kitta (${people.size})", en = "▾ With people (${people.size})"))
                if (!long) people.forEach { k ->
                    val days = com.shopai.app.brain.tools.KeptGroups.daysWith(k, today)
                    val old = if (days >= com.shopai.app.brain.tools.KeptGroups.OLD_DAYS) "⚠ " else ""
                    add(old + pick(lang, ta = "${who(k.person!!, lang)} கிட்ட ${show(k.item)} — $days நாள் ஆச்சு", tl = "${who(k.person!!, lang)} kitta ${show(k.item)} — $days naal aachu",
                        en = "${show(k.item)} with ${who(k.person!!, lang)} — $days days") + closedMark(k, lang))
                }
            }
            for (g in placed) {
                add("▾ " + (g.top?.let { cap(it) } ?: pick(lang, ta = "இடம் சொல்லாதது", tl = "Idam sollaadhadhu", en = "No place")) + " (${g.items.size})")
                if (!long) g.items.forEach { k -> add("${show(k.item)} — ${placeText(k.placeParts)} — ${date(k.createdAt, lang)}" + (k.keptBy?.let { " · ${who(it, lang)}" } ?: "") + closedMark(k, lang)) }
            }
        }
        return KaiTurn(ChatReply(summary, KaiMood.NEUTRAL, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(lines, listOf(fullList(lang)),
            headline = pick(lang, ta = "எங்க வச்சேன்?", tl = "Enga Vechen?", en = "Where did I keep it?")))
    }

    private fun closedMark(k: KeptItem, lang: KaiLang) = if (k.active) "" else pick(lang, ta = " (எடுத்தாச்சு)", tl = " (eduthaachu)", en = " (taken)")

    private fun fullList(lang: KaiLang) = KaiButton(pick(lang, ta = "முழு லிஸ்ட் பாரு", tl = "Full list paaru", en = "See full list"), KaiAction.OpenRecord("KEPT"), primary = true)

    private fun inTime(d: LocalDate, time: String?, today: LocalDate): Boolean = when (time) {
        null -> true
        "today" -> d == today
        "yesterday" -> d == today.minusDays(1)
        "last week" -> !d.isBefore(today.minusDays(7))
        "last month" -> today.minusMonths(1).let { m -> d.year == m.year && d.month == m.month }
        else -> true
    }

    private fun whenKept(time: String?): LocalDateTime = now().let { n ->
        when (time) { "yesterday" -> n.minusDays(1); "last week" -> n.minusWeeks(1); "last month" -> n.minusMonths(1); else -> n }
    }

    // ------------------------------------------------------------------ words

    private suspend fun kept(): List<KeptItem> = runCatching { tools.keptItems() }.getOrNull().orEmpty()

    /** The kept things [r] asks for: the list's name, else the owner's own word (by sound, either script). */
    private fun matches(r: KaiKept.Read, items: List<KeptItem>): List<KeptItem> = items.filter { k ->
        k.active && (r.itemKey != null && k.itemKey == r.itemKey || r.item != null && same(k.item, r.item) ||
            r.meanings.isNotEmpty() && k.itemKey in r.meanings && r.item != null && k.item.equals(r.item, true))
    }.sortedByDescending { it.updatedAt }

    private fun same(a: String, b: String): Boolean = a.equals(b, true) || KaiWordSound.near(a.replace(" ", ""), b.replace(" ", "")) && a.length >= 4 ||
        NameSound.same(a, b)

    private fun samePerson(a: String, b: String) = a.equals(b, true) || NameSound.same(a, b) || (KaiRelations.shown(a) != null && KaiRelations.shown(a) == KaiRelations.shown(b))

    /** The owner's pick of a meaning ("specs", "kannadi table"): the name or one of its words said. */
    private fun said(line: String, name: String): Boolean {
        val l = line.trim().lowercase(Locale.ROOT)
        return l == name.lowercase(Locale.ROOT) || name.lowercase(Locale.ROOT).split(' ').any { w -> w.length >= 3 && l.split(' ').any { it == w || KaiWordSound.near(it, w) } }
    }

    private fun meaningName(name: String) = if (name == "dressing table") "mirror (dressing table)" else name

    private fun where(i: KeptItem) = placeOrPerson(i.placeParts, i.person, KaiLang.TANGLISH)

    private fun placeOrPerson(parts: List<String>, person: String?, lang: KaiLang): String = listOfNotNull(
        parts.takeIf { it.isNotEmpty() }?.let { placeText(it) },
        person?.let { pick(lang, ta = "${who(it, lang)} கிட்ட", tl = "${who(it, lang)} kitta", en = "with ${who(it, lang)}") },
    ).joinToString(", ").ifEmpty { "?" }

    /** "almirah la" / "அலமாரி-ல" / "in almirah"; "Ravi kitta" for a person. */
    private fun at(parts: List<String>, person: String?, lang: KaiLang): String = if (parts.isEmpty()) placeOrPerson(parts, person, lang)
        else placeText(parts).replaceFirstChar { it.lowercase(Locale.ROOT) }.let { p -> pick(lang, ta = "$p-ல", tl = "$p la", en = "in $p") }

    private val positionWords = setOf("mela", "keezha", "naduvula", "left", "right", "kadaisi", "top", "middle", "bottom", "first", "second", "third", "last",
        "1st", "2nd", "3rd", "pakkathula", "pinnadi", "munnadi", "adiyila", "moolaila")

    /** "Bedroom almirah, mela drawer": a position word starts a new part. */
    fun placeText(parts: List<String>): String {
        val groups = mutableListOf<MutableList<String>>()
        for (p in parts) {
            if (groups.isEmpty() || p.lowercase(Locale.ROOT) in positionWords && groups.last().any { it.lowercase(Locale.ROOT) !in positionWords }) groups += mutableListOf(p)
            else groups.last() += p
        }
        return cap(groups.joinToString(", ") { it.joinToString(" ") })
    }

    private fun who(p: String, lang: KaiLang) = KaiRelations.shown(p)?.let { r -> if (lang == KaiLang.TAMIL) KaiRelations.inTamil(r) else cap(r) } ?: p

    private fun show(item: String?) = cap(item.orEmpty())

    private fun cap(s: String) = s.replaceFirstChar { it.titlecase(Locale.ROOT) }

    private val tamilMonths = listOf("ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்", "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்")

    private fun date(at: LocalDateTime, lang: KaiLang): String = if (lang == KaiLang.TAMIL) "${at.dayOfMonth} ${tamilMonths[at.monthValue - 1]}"
        else "${at.dayOfMonth} ${at.month.name.take(3).lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }}"

    private fun plain(lang: KaiLang, ta: String, tl: String, en: String) = KaiTurn(ChatReply(pick(lang, ta, tl, en), KaiMood.NEUTRAL, ChatIntent.GENERAL_BUSINESS_QUERY))

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }
}
