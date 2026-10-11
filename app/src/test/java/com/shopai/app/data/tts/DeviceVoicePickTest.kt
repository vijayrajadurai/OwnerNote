package com.shopai.app.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceVoicePickTest {

    private fun o(name: String, lang: String = "ta", country: String = "IN", features: Set<String> = emptySet(), net: Boolean = false) =
        DeviceVoicePick.Option(name, lang, country, features, net)

    @Test fun aVoiceMarkedMaleIsPicked() {
        val voices = listOf(o("ta-in-x-tad-local", features = setOf("female")), o("ta-in-x-tag-local", features = setOf("male")))
        assertEquals("ta-in-x-tag-local", DeviceVoicePick.male(voices, "ta", "IN"))
    }

    @Test fun femaleIsNeverTakenForMale() {
        assertNull(DeviceVoicePick.male(listOf(o("ta-IN-female-1"), o("x", features = setOf("gender=female"))), "ta", "IN"))
    }

    @Test fun genderInTheNameOrAsAKeyValue() {
        assertEquals("ta-IN-Male-2", DeviceVoicePick.male(listOf(o("ta-IN-Female-1"), o("ta-IN-Male-2")), "ta", "IN"))
        assertEquals("v1", DeviceVoicePick.male(listOf(o("v1", features = setOf("gender=male"))), "ta", "IN"))
    }

    @Test fun sameLanguageOfflineFirst() {
        val voices = listOf(o("en-male", lang = "en", features = setOf("male")), o("ta-male-net", features = setOf("male"), net = true),
            o("ta-male-local", features = setOf("male")))
        assertEquals("ta-male-local", DeviceVoicePick.male(voices, "ta", "IN"))
    }

    @Test fun noneMarkedMeansNullAndTheCallerDeepensThePitch() {
        assertNull(DeviceVoicePick.male(listOf(o("ta-in-x-tac-local"), o("ta-in-x-tad-local")), "ta", "IN"))
    }
}
