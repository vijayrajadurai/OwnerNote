package com.shopai.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesStoreTest {
    @Test
    fun appLanguage_fromTag_defaultsToEnglish() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.TAMIL, AppLanguage.fromTag("ta"))
    }

    @Test
    fun appThemeMode_fromValue_defaultsToSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue(null))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromValue("dark"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue("system"))
    }
}
