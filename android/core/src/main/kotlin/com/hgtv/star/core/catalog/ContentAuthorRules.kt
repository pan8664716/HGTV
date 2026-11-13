// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.heatValueImpl(raw: String): Long =
    ContentCatalogParser.heatValue(raw)

internal fun ContentRepository.sameAuthorImpl(left: String, right: String): Boolean {
    fun norm(value: String)=value.filterNot { it.isWhitespace() }.lowercase()
    return norm(left).isNotEmpty() && norm(left)==norm(right)
}

internal fun ContentRepository.authorUnknownOrSameImpl(
    left: String,
    right: String,
): Boolean {
    fun norm(value: String)=value.filterNot { it.isWhitespace() }.lowercase()
    val name=norm(left)
    return name.isEmpty() || name==norm(right)
}
