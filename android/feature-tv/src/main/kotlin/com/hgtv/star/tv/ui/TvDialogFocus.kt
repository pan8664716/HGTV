// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

internal fun TvDialog.wireListExitImpl() {
    val firstButton = buttons.firstOrNull { it.text.toString() == "取消" }
        ?: buttons.firstOrNull()
        ?: return
    val count = items.childCount
    if (count == 0) return
    val lastItem = items.getChildAt(count - 1)
    val contentInFocusChain = content.firstFocusable() != null || contentScroll.isFocusable
    if (!contentInFocusChain) {
        buttons.firstOrNull()?.nextFocusUpId = lastItem.id
    }
    lastItem.nextFocusDownId = firstButton.id
    for (i in 0 until count) {
        val item = items.getChildAt(i)
        val isFirst = i == 0
        val isLast = i == count - 1
        item.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (isLast) { firstButton.requestFocus(); true } else false
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (isFirst) {
                        val up = content.firstFocusable()
                        if (up != null) { up.requestFocus(); true } else true
                    } else false
                else -> false
            }
        }
    }
}

internal fun ViewGroup.firstChildOrNull(): View? = if (childCount > 0) getChildAt(0) else null

internal fun View.anyFocusable(): Boolean {
    if (visibility != View.VISIBLE) return false
    if (isFocusable) return true
    val group = this as? ViewGroup ?: return false
    return (0 until group.childCount).any { group.getChildAt(it).anyFocusable() }
}

internal fun View.firstFocusable(): View? {
    if (visibility != View.VISIBLE) return null
    if (isFocusable) return this
    val group = this as? ViewGroup ?: return null
    for (i in 0 until group.childCount) group.getChildAt(i).firstFocusable()?.let { return it }
    return null
}
