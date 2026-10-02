package com.shopai.app.util

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Stable
data class PickedContact(
    val name: String,
    val phone: String?,
)

@Composable
fun rememberContactPicker(
    onContactPicked: (PickedContact) -> Unit,
    onPickFailed: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val contact = runCatching {
            resolveContact(context.contentResolver, uri)
        }.getOrNull()
        if (contact != null) {
            onContactPicked(contact)
        } else {
            onPickFailed()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pickLauncher.launch(null)
        } else {
            onPickFailed()
        }
    }

    return {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasContactsPermission) {
            pickLauncher.launch(null)
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
}

fun resolveContact(resolver: ContentResolver, uri: Uri): PickedContact? {
    val contactUri = ContactsContract.Contacts.lookupContact(resolver, uri) ?: uri
    val contactId = contactUri.lastPathSegment ?: return null

    var name: String? = null
    runCatching {
        resolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
    }

    val resolvedName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val phone = lookupPhoneForContact(resolver, contactId)
    return PickedContact(name = resolvedName, phone = phone)
}

private fun lookupPhoneForContact(resolver: ContentResolver, contactId: String): String? {
    return runCatching {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@runCatching null
            val phoneIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (phoneIndex < 0) return@runCatching null
            normalizeIndianPhone(cursor.getString(phoneIndex))
        }
    }.getOrNull()
}

fun normalizeIndianPhone(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 10) return raw.trim()
    val last10 = digits.takeLast(10)
    return "+91$last10"
}
