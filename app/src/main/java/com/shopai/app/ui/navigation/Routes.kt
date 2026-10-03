package com.shopai.app.ui.navigation

import android.net.Uri

object Routes {
    const val Splash = "splash"
    const val Login = "login"
    const val Otp = "otp"
    const val UserGuide = "user_guide"
    const val BusinessSetup = "business_setup"
    const val Home = "home"
    const val Discover = "discover"
    const val Customers = "customers"
    const val CustomerDetail = "customer_detail/{customerId}"
    const val Suppliers = "suppliers"
    const val SupplierDetail = "supplier_detail/{supplierId}"
    const val More = "more"
    const val AddCredit =
        "add_credit?customerId={customerId}&customerName={customerName}&customerPhone={customerPhone}"
    const val AddDebit =
        "add_debit?supplierId={supplierId}&supplierName={supplierName}&supplierPhone={supplierPhone}"
    const val VoiceEntry = "voice_entry"
    /** Kai Chat: business conversation with KAI (text; no AI service). */
    const val KaiChat = "kai_chat"
    /** Kai — Do My Morning Work. [mode] = voice / text (how the owner asked); [start] = go straight to the first task. */
    const val MorningWork = "morning_work?mode={mode}&start={start}"
    const val Reminders = "reminders"
    const val ReminderDetail = "reminder_detail/{reminderId}"
    const val AiInsights = "ai_insights"
    const val DailyCashNote = "daily_cash_note"
    const val Settings = "settings"
    const val Profile = "profile"
    const val MoreInfo = "more_info/{page}"
    const val Subscription = "subscription"
    const val FundingQualification = "funding_qualification/{opportunityId}"
    const val GroupBuying = "group_buying"
    const val GroupBuyingNew = "group_buying_new"
    const val GroupBuyingResult = "group_buying_result/{requestId}"
    const val Inventory = "inventory"
    const val ProductDetail = "product_detail/{productId}"
    const val VoiceStockEntry = "voice_stock_entry"
    const val LocalOffers = "local_offers"
    const val AskBusiness = "ask_business"
    const val HandwrittenNotes = "handwritten_notes"
    const val HandwrittenScan = "handwritten_scan?billId={billId}"
    const val HandwrittenPerson = "handwritten_person/{name}"

    // OwnerNote Books masters.
    const val ProductForm = "product_form?productId={productId}"
    const val PartyForm = "party_form/{kind}?partyId={partyId}"
    const val HsnMaster = "hsn_master"
    const val BooksSettings = "books_settings"

    // OwnerNote Books billing.
    const val Billing = "billing"
    const val BillEditor = "bill_editor/{kind}?partyId={partyId}&draftId={draftId}"
    const val BooksDocument = "books_document/{txnId}"
    const val ReturnEditor = "return_editor/{txnId}"
    const val Payment = "payment/{direction}?partyId={partyId}&docId={docId}"

    /** [kind] = SALE or PURCHASE. */
    fun billEditor(kind: String, partyId: String? = null, draftId: String? = null): String =
        "bill_editor/$kind?partyId=${partyId?.let(Uri::encode).orEmpty()}&draftId=${draftId?.let(Uri::encode).orEmpty()}"

    fun booksDocument(txnId: String): String = "books_document/${Uri.encode(txnId)}"

    fun returnEditor(txnId: String): String = "return_editor/${Uri.encode(txnId)}"

    /** [direction] = IN (from a customer) or OUT (to a supplier). */
    fun payment(direction: String, partyId: String? = null, docId: String? = null): String =
        "payment/$direction?partyId=${partyId?.let(Uri::encode).orEmpty()}&docId=${docId?.let(Uri::encode).orEmpty()}"

    fun morningWork(voice: Boolean = false, start: Boolean = false): String =
        "morning_work?mode=${if (voice) "voice" else "text"}&start=$start"

    fun productForm(productId: String? = null): String = "product_form?productId=${productId?.let(Uri::encode).orEmpty()}"

    /** [kind] = CUSTOMER or SUPPLIER. */
    fun partyForm(kind: String, partyId: String? = null): String = "party_form/$kind?partyId=${partyId?.let(Uri::encode).orEmpty()}"

    /** Scan/upload a handwritten note; [billId] links its rows to a saved shop bill. */
    fun handwrittenScan(billId: Long? = null): String = "handwritten_scan?billId=${billId ?: -1}"

    fun handwrittenPerson(name: String): String = "handwritten_person/${Uri.encode(name)}"

    fun moreInfo(page: String): String = "more_info/${Uri.encode(page)}"

    fun fundingQualification(opportunityId: String): String =
        "funding_qualification/${Uri.encode(opportunityId)}"

    fun groupBuyingResult(requestId: String): String =
        "group_buying_result/${Uri.encode(requestId)}"

    fun reminderDetail(reminderId: String): String = "reminder_detail/${Uri.encode(reminderId)}"

    fun customerDetail(customerId: String): String = "customer_detail/$customerId"

    fun supplierDetail(supplierId: String): String = "supplier_detail/$supplierId"

    fun productDetail(productId: String): String = "product_detail/${Uri.encode(productId)}"

    fun addCredit(
        customerId: String? = null,
        customerName: String? = null,
        customerPhone: String? = null,
    ): String {
        val id = customerId ?: ""
        val name = customerName?.let(Uri::encode) ?: ""
        val phone = customerPhone?.let(Uri::encode) ?: ""
        return "add_credit?customerId=$id&customerName=$name&customerPhone=$phone"
    }

    fun addDebit(
        supplierId: String? = null,
        supplierName: String? = null,
        supplierPhone: String? = null,
    ): String {
        val id = supplierId ?: ""
        val name = supplierName?.let(Uri::encode) ?: ""
        val phone = supplierPhone?.let(Uri::encode) ?: ""
        return "add_debit?supplierId=$id&supplierName=$name&supplierPhone=$phone"
    }
}
