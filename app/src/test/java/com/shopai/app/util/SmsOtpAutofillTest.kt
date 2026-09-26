package com.shopai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsOtpAutofillTest {
    @Test
    fun extractsSixDigitCodeFromFirebaseStyleMessage() {
        val message = "123456 is your verification code for owner-note."
        assertEquals("123456", extractOtpFromMessage(message))
    }

    @Test
    fun acceptsPlainSixDigitMessage() {
        assertEquals("654321", extractOtpFromMessage("654321"))
    }

    @Test
    fun ignoresMessagesWithoutOtp() {
        assertNull(extractOtpFromMessage("Your code will arrive shortly."))
    }

    @Test
    fun ignoresNullOrBlankMessage() {
        assertNull(extractOtpFromMessage(null))
        assertNull(extractOtpFromMessage("   "))
    }
}
