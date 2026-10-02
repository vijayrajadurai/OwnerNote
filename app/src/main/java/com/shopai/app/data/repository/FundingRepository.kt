package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.FundingOpportunity
import com.shopai.app.data.model.LeadQualificationInput
import com.shopai.app.data.model.LoanLead

class FundingRepository(private val api: ShopAiApi) {
    suspend fun getOpportunities(): List<FundingOpportunity> =
        api.getFundingOpportunities().data

    suspend fun getOpportunity(id: String): FundingOpportunity =
        api.getFundingOpportunity(id).data

    suspend fun markInterested(id: String): FundingOpportunity =
        api.markFundingInterested(id).data

    suspend fun markNotNow(id: String): FundingOpportunity =
        api.markFundingNotNow(id).data

    suspend fun createLead(id: String, input: LeadQualificationInput): LoanLead =
        api.createLead(id, input).data
}
