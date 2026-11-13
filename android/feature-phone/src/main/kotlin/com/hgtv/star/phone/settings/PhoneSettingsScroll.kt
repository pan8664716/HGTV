// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

internal data class ScrollAnchor(val key: String, val offset: Int)

internal fun PhoneSettingsActivity.currentScrollAnchorImpl(): ScrollAnchor? {
    if (!settingsScrollerInitialized || !settingsContainerInitialized) return null
    val top = settingsScroller.scrollY
    val anchors = anchorViewsImpl()
    val target = anchors.firstOrNull { topInContainerImpl(it) + it.height > top }
        ?: anchors.lastOrNull()
    return target?.let { ScrollAnchor(it.tag as String, topInContainerImpl(it) - top) }
}

internal fun PhoneSettingsActivity.anchorViewsImpl(): List<View> {
    val result = ArrayList<View>()
    fun collect(parent: ViewGroup) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val key = child.tag as? String
            if (key != null && (
                    key.startsWith("section:") || key.startsWith("row:") ||
                        key.startsWith("grid:")
                    )
            ) result.add(child)
            if (child is ViewGroup) collect(child)
        }
    }
    collect(settingsContainer)
    return result.sortedBy(::topInContainerImpl)
}

internal fun PhoneSettingsActivity.topInContainerImpl(view: View): Int {
    var top = 0
    var current: View? = view
    while (current != null && current !== settingsContainer) {
        top += current.top
        current = current.parent as? View
    }
    return top
}

internal fun PhoneSettingsActivity.restoreScrollImpl(
    anchor: ScrollAnchor?,
    resetTop: Boolean = false,
) {
    settingsScrollRestoreListener?.let { listener ->
        runCatching {
            settingsScroller.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    settingsScrollRestoreListener = null
    if (anchor == null && !resetTop) return
    val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            val observer = settingsScroller.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
            settingsScrollRestoreListener = null
            if (resetTop || anchor == null) {
                settingsScroller.scrollTo(0, 0)
                return
            }
            val target = anchorViewsImpl().firstOrNull {
                it.tag == anchor.key && it.height > 0
            }
            settingsScroller.scrollTo(
                0,
                target?.let {
                    (topInContainerImpl(it) - anchor.offset).coerceAtLeast(0)
                } ?: 0,
            )
        }
    }
    settingsScrollRestoreListener = listener
    settingsScroller.viewTreeObserver.addOnGlobalLayoutListener(listener)
}
