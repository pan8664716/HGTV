package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PlaybackSpeedTest {
    @Test fun invalidSavedSpeedsResetToNormal() {
        // 2.5 从来不是档位；3 曾经也不是，2026-11 起手机 / 平板加了这一档，
        // 因此它现在必须被 [PlaybackSpeed.normalize] 认下来（见下面那两条断言）。
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-1f,2.5f)) {
            assertEquals(1f,PlaybackSpeed.normalize(value))
        }
        assertEquals(1.5f,PlaybackSpeed.normalize(1.5f))
        assertEquals(3f,PlaybackSpeed.normalize(3f))
    }

    /** 3× 只进手机 / 平板的选择列表，校验认它、电视的列表里没有它。 */
    @Test fun tripleSpeedIsPhoneOnlyButAlwaysValid() {
        assertTrue(PlaybackSpeed.phoneOptions.contains(3f))
        assertFalse(PlaybackSpeed.options.contains(3f))
        assertTrue(PlaybackSpeed.isValid(3f))
        assertEquals("3×",PlaybackSpeed.label(3f))
    }

    @Test fun labelsRemainReadableAcrossDeviceLocales() {
        val original=Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1×",PlaybackSpeed.label(1f))
            assertEquals("1.25×",PlaybackSpeed.label(1.25f))
        } finally { Locale.setDefault(original) }
    }

    @Test fun missingOrUnknownContentPreferenceDefaultsToComic() {
        assertEquals(ContentType.COMIC,ContentType.fromStored(null))
        assertEquals(ContentType.COMIC,ContentType.fromStored("unknown"))
        assertEquals(ContentType.SHORT,ContentType.fromStored("short"))
    }
}
