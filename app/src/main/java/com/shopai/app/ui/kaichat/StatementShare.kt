package com.shopai.app.ui.kaichat

import android.content.Context
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiStatement
import com.shopai.app.data.AppContainer
import com.shopai.app.util.PartyStatementPdf
import com.shopai.app.util.partyStatement
import com.shopai.app.util.statementTxns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Kai's statement button: the person's entries and payments read from the books (the same records Kai answered from), the
 * statement drawn as a PDF on the phone, then WhatsApp (or the share sheet) opened with it. Nothing is written or sent by itself.
 */
suspend fun shareStatement(container: AppContainer, context: Context, a: KaiAction.ShareStatement, lang: KaiLang) {
    val today = LocalDate.now()
    val (phone, txns) = if (a.customer) container.partyRepository.getCustomer(a.partyId).let { it.phone to it.transactions.statementTxns() }
    else container.partyRepository.getSupplier(a.partyId).let { it.phone to it.transactions.statementTxns() }
    val shop = runCatching { container.businessRepository.getMyBusiness() }.getOrNull()
    val st = partyStatement(a.name, phone?.takeIf { it.isNotBlank() }, a.customer, txns, a.from, a.to, today)
    val place = listOfNotNull(shop?.areaLabel?.takeIf { it.isNotBlank() }, shop?.city?.takeIf { it.isNotBlank() }).distinct().joinToString(", ")
    val file = withContext(Dispatchers.IO) {
        PartyStatementPdf.render(context, st, shop?.businessName.orEmpty(), shop?.phone, place, tamil = lang == KaiLang.TAMIL, today = today)
    }
    PartyStatementPdf.share(context, file, KaiStatement.caption(st, shop?.businessName.orEmpty(), lang), st.phone, a.whatsapp)
}

fun pdfFailed(lang: KaiLang): String = when (lang) {
    KaiLang.TAMIL -> "PDF ரெடி பண்ண முடியல ஓனர் — மறுபடி முயற்சி பண்ணுங்க."
    KaiLang.TANGLISH -> "PDF ready panna mudiyala Owner — marupadi try pannunga."
    KaiLang.ENGLISH -> "Couldn't make the PDF, Owner — please try again."
}
