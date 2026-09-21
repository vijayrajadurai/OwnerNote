package com.shopai.app.util

fun partyInitialLetter(name: String): String =
    name.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString() ?: "?"
