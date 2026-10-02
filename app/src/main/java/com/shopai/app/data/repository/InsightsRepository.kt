package com.shopai.app.data.repository

import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.integration.LegacyBridge
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.AiInsight
import com.shopai.app.data.model.AskAnswer
import com.shopai.app.data.model.AskBusinessRequest
import com.shopai.app.data.model.BusinessHealth
import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.PriorityItem
import com.shopai.app.data.model.SeasonalInsightItem

class InsightsRepository(private val api: ShopAiApi, private val books: BooksModule? = null) {
    /** Receivable / payable and what falls due — from the books once they are in use. */
    suspend fun getCashFlow(): CashFlowSummary =
        books?.session()?.let { LegacyBridge(it).cashFlow() } ?: api.getCashFlow().data

    suspend fun getBusinessHealth(): BusinessHealth = api.getBusinessHealth().data

    suspend fun getPriorities(): List<PriorityItem> = api.getPriorities().data

    suspend fun getAiInsights(): List<AiInsight> = api.getAiInsights().data

    suspend fun dismissInsight(id: String): AiInsight = api.dismissInsight(id).data

    suspend fun getSeasonalInsights(): List<SeasonalInsightItem> = api.getSeasonalInsights().data

    suspend fun askMyBusiness(question: String): AskAnswer =
        api.askMyBusiness(AskBusinessRequest(question)).data
}
