package com.shopai.app.data.inventory

import com.shopai.app.data.model.InventoryProduct

/**
 * Turns raw voice/typed text into a screen-ready state: parses it with
 * [StockVoiceParser], then resolves the spoken product name against the
 * REAL product list (never auto-creates a product) and — for STOCK_OUT —
 * reuses the existing [canRemoveStock] guard so insufficient stock is
 * caught before the confirmation card is even shown, not just on write.
 */
sealed class StockVoiceResolution {
    data class ReadyToConfirm(
        val intent: StockVoiceIntent,
        val product: InventoryProduct,
        val quantity: Double,
        val unit: String,
    ) : StockVoiceResolution()

    data class InsufficientStock(
        val product: InventoryProduct,
        val requestedQuantity: Double,
        val availableQuantity: Double,
    ) : StockVoiceResolution()

    data class ProductNotFound(val spokenName: String) : StockVoiceResolution()

    object UnitUnknown : StockVoiceResolution()

    object NotUnderstood : StockVoiceResolution()
}

object StockVoiceResolver {
    fun resolve(text: String, products: List<InventoryProduct>): StockVoiceResolution {
        val parsed = StockVoiceParser.parse(text) ?: return StockVoiceResolution.NotUnderstood
        val product = findProductBySpokenName(parsed.productName, products)
            ?: return StockVoiceResolution.ProductNotFound(parsed.productName)

        val unit = parsed.unit ?: product.unit.takeIf { it.isNotBlank() }
            ?: return StockVoiceResolution.UnitUnknown

        if (parsed.intent == StockVoiceIntent.STOCK_OUT) {
            val decision = canRemoveStock(product.currentStock, parsed.quantity)
            if (!decision.ok) {
                return StockVoiceResolution.InsufficientStock(product, parsed.quantity, decision.available)
            }
        }

        return StockVoiceResolution.ReadyToConfirm(parsed.intent, product, parsed.quantity, unit)
    }

    /**
     * Same normalization floor as [findDuplicateProduct] (trim + lowercase),
     * extended with a bidirectional substring check so leftover words the
     * parser couldn't strip out (e.g. "cement stock la irundhu") still
     * resolve against a real product named "Cement". No fuzzy/phonetic or
     * cross-script matching — see [StockVoiceParser]'s documented limits.
     */
    fun findProductBySpokenName(spokenName: String, products: List<InventoryProduct>): InventoryProduct? {
        val normalizedSpoken = normalize(spokenName)
        if (normalizedSpoken.isEmpty()) return null

        products.firstOrNull { normalize(it.name) == normalizedSpoken }?.let { return it }

        return products
            .filter { product ->
                val normalizedName = normalize(product.name)
                normalizedName.isNotEmpty() &&
                    (normalizedSpoken.contains(normalizedName) || normalizedName.contains(normalizedSpoken))
            }
            .maxByOrNull { normalize(it.name).length }
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}
