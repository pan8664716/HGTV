// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.CategoryGenres

internal object LibraryPreferenceCodec {
    fun decodeCsv(value: String, allowed: Collection<String>, excluded: Set<String> = emptySet()): Set<String> =
        value.split(',').filter { it in allowed && it !in excluded }.toSet()

    fun encodeCsv(value: Set<String>, allowed: Collection<String>, excluded: Set<String> = emptySet()): Set<String> =
        value.filter { it in allowed && it !in excluded }.toSet()

    fun sanitizeCursor(value: String, maxLength: Int): String =
        value.take(maxLength).filter { !it.isISOControl() }

    fun normalizeDeviceForm(value: String): String =
        when(value) { "phone","tablet","tv" -> value; else -> "auto" }

    fun decodeGenreOff(value: String): Set<String> =
        value.split(',').filter { it in CategoryGenres.recommendableIds }.toSet()

    fun encodeGenreOff(value: Set<String>): String {
        val clean=value.filter { it in CategoryGenres.recommendableIds }.toSet()
        return CategoryGenres.recommendableIds.filter { it in clean }.joinToString(",")
    }
}
