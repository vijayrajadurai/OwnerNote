package com.shopai.app.crash

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.shopai.app.BuildConfig

object CrashReporting {
    fun start(app: Application) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.setCustomKey("version_name", BuildConfig.VERSION_NAME)
        crashlytics.setCustomKey("version_code", BuildConfig.VERSION_CODE)
        crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        crashlytics.setCustomKey("application_id", app.packageName)
    }

    fun setSession(phoneDigits: String?) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        val digits = phoneDigits.orEmpty().filter { it.isDigit() }
        if (digits.length < 4) {
            crashlytics.setUserId("")
            crashlytics.setCustomKey("signed_in", false)
            return
        }
        crashlytics.setUserId("in_${digits.takeLast(4)}")
        crashlytics.setCustomKey("signed_in", true)
    }

    fun log(message: String) {
        FirebaseCrashlytics.getInstance().log(message)
    }

    fun record(throwable: Throwable) {
        FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}
