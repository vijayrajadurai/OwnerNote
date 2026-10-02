package com.shopai.app.ui.books

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.shopai.app.R
import com.shopai.app.books.data.HsnSacEntity
import com.shopai.app.books.data.PartyEntity
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.BooksErrorCode
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.OpeningStockInput
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ProductInput
import com.shopai.app.books.integration.BooksRejectedException
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.CodeKind
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.TaxType
import com.shopai.app.books.tax.HsnRules
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopTextField
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Success
import com.shopai.app.ui.theme.Warning
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Product Master: create or edit one product (the same products the Inventory screen lists). */
@Composable
fun ProductFormScreen(container: AppContainer, productId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val books = rememberBooks(container)
    DetailScaffold(
        title = stringResource(if (productId == null) R.string.books_product_new else R.string.books_product_edit),
        onBack = onBack,
    ) { modifier ->
        when (books) {
            is BooksState.Ready -> ProductForm(books.session, productId, modifier, onSaved)
            else -> BooksNotReady(books, modifier)
        }
    }
}

@Composable
private fun ProductForm(s: BooksSession, productId: String?, modifier: Modifier, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var units by remember { mutableStateOf(emptyList<String>()) }
    var slabs by remember { mutableStateOf(emptyList<Int>()) }
    var suppliers by remember { mutableStateOf(emptyList<PartyEntity>()) }
    var businessBatches by remember { mutableStateOf(false) }
    var inventoryOn by remember { mutableStateOf(true) }

    var name by remember { mutableStateOf("") }
    var isService by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("") }
    var subCategory by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var hsn by remember { mutableStateOf("") }
    var hsnConfirmed by remember { mutableStateOf(false) }
    var hsnVerdict by remember { mutableStateOf<HsnRules.Verdict?>(null) }
    var hsnSuggestions by remember { mutableStateOf(emptyList<HsnSacEntity>()) }
    var taxType by remember { mutableStateOf(TaxType.GST) }
    var gstBp by remember { mutableStateOf(0) }
    var cess by remember { mutableStateOf("") }
    var primaryUnit by remember { mutableStateOf("PCS") }
    var secondaryUnit by remember { mutableStateOf("") }
    var conversion by remember { mutableStateOf("") }
    var purchase by remember { mutableStateOf("") }
    var selling by remember { mutableStateOf("") }
    var mrp by remember { mutableStateOf("") }
    var wholesale by remember { mutableStateOf("") }
    var retail by remember { mutableStateOf("") }
    var minSelling by remember { mutableStateOf("") }
    var includesTax by remember { mutableStateOf(false) }
    var openingQty by remember { mutableStateOf("") }
    var openingValue by remember { mutableStateOf("") }
    var minStock by remember { mutableStateOf("") }
    var reorder by remember { mutableStateOf("") }
    var supplierId by remember { mutableStateOf<String?>(null) }
    var batchTracked by remember { mutableStateOf(false) }
    var batchNo by remember { mutableStateOf("") }
    var mfg by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    var backendId by remember { mutableStateOf<String?>(null) }

    var errors by remember { mutableStateOf(emptyList<BooksError>()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(productId) {
        val dao = s.dao
        val business = dao.business(s.ctx.businessId)
        units = dao.units(s.ctx.businessId).map { it.code }
        slabs = business?.gstRatesBp?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        businessBatches = business?.batchTracking == true
        inventoryOn = business?.businessType?.let { BusinessType.valueOf(it).inventory } ?: true
        suppliers = dao.parties(s.ctx.businessId, PartyKind.SUPPLIER.name)
        productId?.let { dao.product(it) }?.let { p ->
            name = p.name; isService = p.isService
            category = p.categoryId?.let { dao.category(it)?.name }.orEmpty()
            subCategory = p.subCategoryId?.let { dao.category(it)?.name }.orEmpty()
            brand = p.brandId?.let { dao.brand(it)?.name }.orEmpty()
            sku = p.sku.orEmpty(); barcode = p.barcode.orEmpty()
            hsn = p.hsnCode.orEmpty(); hsnConfirmed = p.hsnConfirmedByUser
            taxType = TaxType.valueOf(p.taxType); gstBp = p.gstBp; cess = if (p.cessBp == 0) "" else bpText(p.cessBp)
            primaryUnit = p.primaryUnit; secondaryUnit = p.secondaryUnit.orEmpty(); conversion = qtyText(p.conversionMilli)
            purchase = paiseText(p.purchasePricePaise); selling = paiseText(p.sellingPricePaise); mrp = paiseText(p.mrpPaise)
            wholesale = paiseText(p.wholesalePricePaise); retail = paiseText(p.retailPricePaise); minSelling = paiseText(p.minSellingPricePaise)
            includesTax = p.priceIncludesTax; minStock = qtyText(p.minStockMilli); reorder = qtyText(p.reorderLevelMilli)
            supplierId = p.supplierId; batchTracked = p.batchTracked; description = p.description.orEmpty()
            imagePath = p.imagePath; active = !p.archived; backendId = p.backendId
        }
        loaded = true
    }

    // HSN / SAC: verified only when it is in the master; otherwise the owner must confirm it.
    LaunchedEffect(hsn, isService) {
        val code = HsnRules.normalize(hsn)
        val kind = if (isService) CodeKind.SAC else CodeKind.HSN
        hsnVerdict = if (code.isEmpty()) null else HsnRules.verdict(code, kind, s.dao.hsn(code, kind.name) != null)
        hsnSuggestions = if (hsn.trim().length >= 2) s.dao.searchHsn(hsn.trim(), 5).filter { it.code != code } else emptyList()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { imagePath = copyProductImage(context, it) ?: imagePath }
    }

    fun save() {
        scope.launch {
            saving = true
            val parseErrors = mutableListOf<BooksError>()
            fun money(text: String, field: String) = parseRupees(text).getOrElse { parseErrors += BooksError(BooksErrorCode.INVALID_PRICE, "Check the amount", field); null }
            fun quantity(text: String, field: String) = parseQty(text).getOrElse { parseErrors += BooksError(BooksErrorCode.INVALID_QUANTITY, "Check the quantity", field); null }
            fun day(text: String, field: String): LocalDate? = text.trim().takeIf { it.isNotEmpty() }?.let {
                runCatching { LocalDate.parse(it) }.getOrElse { _ -> parseErrors += BooksError(BooksErrorCode.INVALID_DATE, "Use YYYY-MM-DD", field); null }
            }
            val cessBp = parsePercentBp(cess).getOrElse { parseErrors += BooksError(BooksErrorCode.INVALID_GST, "Check the cess %", "cessBp"); null } ?: 0
            val openQty = quantity(openingQty, "openingQty")
            val openValue = money(openingValue, "openingValue")
            val input = ProductInput(
                name = name, isService = isService, primaryUnit = primaryUnit,
                secondaryUnit = secondaryUnit.trim().uppercase().takeIf { it.isNotEmpty() },
                conversionMilli = quantity(conversion, "conversionMilli"),
                sku = sku, barcode = barcode, hsnCode = hsn, hsnConfirmed = hsnConfirmed,
                taxType = taxType, gstBp = if (taxType == TaxType.GST) gstBp else 0, cessBp = if (taxType == TaxType.GST) cessBp else 0,
                purchasePricePaise = money(purchase, "purchasePricePaise"), sellingPricePaise = money(selling, "sellingPricePaise"),
                mrpPaise = money(mrp, "mrpPaise"), wholesalePricePaise = money(wholesale, "wholesalePricePaise"),
                retailPricePaise = money(retail, "retailPricePaise"), minSellingPricePaise = money(minSelling, "minSellingPricePaise"),
                priceIncludesTax = includesTax, minStockMilli = quantity(minStock, "minStockMilli"),
                reorderLevelMilli = quantity(reorder, "reorderLevelMilli"), supplierId = supplierId,
                batchTracked = batchTracked && !isService, imagePath = imagePath, description = description, backendId = backendId,
            )
            val mfgDay = day(mfg, "mfg")
            val expiryDay = day(expiry, "expiry")
            if (parseErrors.isNotEmpty()) {
                errors = parseErrors
                saving = false
                return@launch
            }
            try {
                var savedId = ""
                s.db.withTransaction {
                    val categoryId = s.masters.categoryId(category)
                    val full = input.copy(
                        primaryUnit = s.masters.ensureUnit(primaryUnit) ?: primaryUnit,
                        secondaryUnit = input.secondaryUnit?.let { s.masters.ensureUnit(it) },
                        categoryId = categoryId,
                        subCategoryId = s.masters.categoryId(subCategory, parentId = categoryId),
                        brandId = s.masters.brandId(brand),
                    )
                    val result = if (productId == null) s.masters.createProduct(full) else s.masters.updateProduct(productId, full)
                    val product = when (result) {
                        is MasterResult.Ok -> result.value
                        is MasterResult.Rejected -> throw BooksRejectedException(result.errors)
                    }
                    if (productId != null && product.archived == active) {
                        (s.masters.setProductArchived(product.id, archived = !active) as? MasterResult.Rejected)?.let { throw BooksRejectedException(it.errors) }
                    }
                    if (productId == null && (openQty ?: 0) > 0 && !isService && inventoryOn) {
                        val posted = s.engine.postOpeningStock(
                            OpeningStockInput(
                                productId = product.id, date = LocalDate.now(), qtyMilli = openQty!!, valuePaise = openValue,
                                batchNo = batchNo.takeIf { it.isNotBlank() }, mfgDate = mfgDay, expiryDate = expiryDay,
                                meta = PostMeta(clientKey = UUID.randomUUID().toString()),
                            ),
                        )
                        if (posted is PostResult.Rejected) throw BooksRejectedException(posted.errors)
                    }
                    savedId = product.id
                }
                errors = emptyList()
                onSaved(savedId)
            } catch (e: BooksRejectedException) {
                errors = e.errors
            }
            saving = false
        }
    }

    if (!loaded) {
        BooksNotReady(BooksState.Loading, modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BooksErrors(errors)
        SectionCard(stringResource(R.string.books_section_basic)) {
            ShopTextField(stringResource(R.string.books_product_name), name, { name = it }, error = errors.messageFor("name"))
            SwitchRow(stringResource(R.string.books_is_service), isService, { isService = it }, stringResource(R.string.books_is_service_caption))
            ShopTextField(stringResource(R.string.inv_category), category, { category = it })
            ShopTextField(stringResource(R.string.inv_sub_category), subCategory, { subCategory = it })
            ShopTextField(stringResource(R.string.inv_brand), brand, { brand = it })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                imagePath?.let { path ->
                    val bitmap = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
                    bitmap?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) }
                }
                OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(if (imagePath == null) R.string.books_add_photo else R.string.books_change_photo))
                }
            }
            ShopTextField(stringResource(R.string.books_description), description, { description = it }, singleLine = false)
            if (productId != null) SwitchRow(stringResource(R.string.books_active), active, { active = it }, stringResource(R.string.books_active_caption))
        }

        SectionCard(stringResource(R.string.books_section_codes)) {
            ShopTextField(stringResource(R.string.inv_sku), sku, { sku = it }, error = errors.messageFor("sku"))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopTextField(stringResource(R.string.inv_barcode), barcode, { barcode = it }, error = errors.messageFor("barcode"), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { scanBarcode(context) { code -> code?.let { barcode = it } } }) { Text(stringResource(R.string.books_scan)) }
            }
            ShopTextField(
                stringResource(if (isService) R.string.books_sac_code else R.string.books_hsn_code), hsn,
                { hsn = it.filter { ch -> ch.isDigit() || ch == ' ' }; hsnConfirmed = false }, error = errors.messageFor("hsnCode"),
            )
            hsnSuggestions.forEach { row ->
                TextButton(onClick = { hsn = row.code; hsnConfirmed = false }) {
                    Text("${row.code} · ${row.description}${row.gstBp?.let { " · ${bpText(it)}%" }.orEmpty()}", maxLines = 1)
                }
            }
            when (hsnVerdict) {
                HsnRules.Verdict.VERIFIED -> Text(stringResource(R.string.books_hsn_verified), color = Success, style = MaterialTheme.typography.bodySmall)
                HsnRules.Verdict.INVALID_FORMAT -> Text(stringResource(if (isService) R.string.books_sac_format else R.string.books_hsn_format), color = Danger, style = MaterialTheme.typography.bodySmall)
                HsnRules.Verdict.VERIFICATION_REQUIRED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hsnConfirmed, onCheckedChange = { hsnConfirmed = it })
                    Column {
                        Text(HsnRules.VERIFICATION_REQUIRED_MESSAGE, color = Warning, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.books_hsn_confirm), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
                    }
                }
                null -> Unit
            }
            Text(stringResource(R.string.books_tax_type), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
            ChoiceChips(TaxType.entries, taxType, { taxTypeLabel(it) }) { taxType = it }
            if (taxType == TaxType.GST) {
                Text(stringResource(R.string.inv_gst_rate), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
                ChoiceChips(slabs, gstBp, { "${bpText(it)}%" }) { gstBp = it }
                errors.messageFor("gstBp")?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
                DecimalField(stringResource(R.string.books_cess), cess, { cess = it })
            }
        }

        SectionCard(stringResource(R.string.books_section_units)) {
            ShopTextField(stringResource(R.string.books_primary_unit), primaryUnit, { primaryUnit = it.uppercase() }, error = errors.messageFor("primaryUnit"))
            ChoiceChips(units, primaryUnit, { it }) { primaryUnit = it }
            ShopTextField(stringResource(R.string.books_secondary_unit), secondaryUnit, { secondaryUnit = it.uppercase() })
            if (secondaryUnit.isNotBlank()) {
                DecimalField(stringResource(R.string.books_conversion, secondaryUnit.uppercase(), primaryUnit), conversion, { conversion = it })
            }
        }

        SectionCard(stringResource(R.string.books_section_prices)) {
            DecimalField(stringResource(R.string.inv_purchase_price), purchase, { purchase = it }, errors.messageFor("purchasePricePaise"))
            DecimalField(stringResource(R.string.inv_selling_price), selling, { selling = it }, errors.messageFor("sellingPricePaise"))
            DecimalField(stringResource(R.string.inv_mrp), mrp, { mrp = it }, errors.messageFor("mrpPaise"))
            DecimalField(stringResource(R.string.books_wholesale_price), wholesale, { wholesale = it })
            DecimalField(stringResource(R.string.books_retail_price), retail, { retail = it })
            DecimalField(stringResource(R.string.books_min_selling_price), minSelling, { minSelling = it }, errors.messageFor("minSellingPricePaise"))
            SwitchRow(stringResource(R.string.books_price_includes_tax), includesTax, { includesTax = it })
        }

        if (!isService && inventoryOn) {
            SectionCard(stringResource(R.string.books_section_stock)) {
                if (productId == null) {
                    DecimalField(stringResource(R.string.inv_opening_stock), openingQty, { openingQty = it }, errors.messageFor("qtyMilli"))
                    DecimalField(stringResource(R.string.books_opening_value), openingValue, { openingValue = it }, errors.messageFor("valuePaise"))
                }
                DecimalField(stringResource(R.string.inv_minimum_stock), minStock, { minStock = it })
                DecimalField(stringResource(R.string.books_reorder_level), reorder, { reorder = it })
                if (businessBatches) {
                    SwitchRow(stringResource(R.string.books_batch_tracked), batchTracked, { batchTracked = it })
                    if (batchTracked && productId == null) {
                        ShopTextField(stringResource(R.string.books_batch_no), batchNo, { batchNo = it }, error = errors.messageFor("batchNo"))
                        ShopTextField(stringResource(R.string.books_mfg_date), mfg, { mfg = it }, placeholder = "YYYY-MM-DD", error = errors.messageFor("mfg"))
                        ShopTextField(stringResource(R.string.books_expiry_date), expiry, { expiry = it }, placeholder = "YYYY-MM-DD", error = errors.messageFor("expiry") ?: errors.messageFor("expiryDate"))
                    }
                }
            }
        }

        SectionCard(stringResource(R.string.inv_supplier)) {
            ChoiceChips(listOf<PartyEntity?>(null) + suppliers, suppliers.firstOrNull { it.id == supplierId }, { it?.name ?: "—" }) { supplierId = it?.id }
        }

        PrimaryButton(label = stringResource(R.string.save), loading = saving, enabled = name.isNotBlank(), onClick = ::save)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun taxTypeLabel(t: TaxType): String = stringResource(
    when (t) {
        TaxType.GST -> R.string.books_tax_gst
        TaxType.EXEMPT -> R.string.books_tax_exempt
        TaxType.NIL_RATED -> R.string.books_tax_nil
        TaxType.NON_GST -> R.string.books_tax_non_gst
    },
)

/** Keeps a private copy of the chosen photo (the picker's Uri does not last). */
private fun copyProductImage(context: android.content.Context, uri: Uri): String? = runCatching {
    val dir = File(context.filesDir, "products").apply { mkdirs() }
    val file = File(dir, "${UUID.randomUUID()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
    file.absolutePath
}.getOrNull()
