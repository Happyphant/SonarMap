package com.sonarmap.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Human-readable file name for a picked content URI, safe on any failure. */
internal fun audioFileName(context: Context, uriString: String): String {
    if (uriString.isBlank()) return "аудиофайл не выбран"
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
    val fromResolver = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return fromResolver ?: uri.lastPathSegment ?: uriString
}
