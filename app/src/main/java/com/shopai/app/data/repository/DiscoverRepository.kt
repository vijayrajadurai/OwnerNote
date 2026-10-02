package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.DiscoverItem

class DiscoverRepository(private val api: ShopAiApi) {
    suspend fun getFeed(): List<DiscoverItem> = api.getDiscoverFeed().data
}
