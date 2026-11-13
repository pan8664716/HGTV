package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PlaybackSpeedTest {
    @Test fun invalidSavedSpeedsResetToNormal() {
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-1f,3.5f)) {
            assertEquals(1f,PlaybackSpeed.normalize(value))
        }
        assertEquals(1.5f,PlaybackSpeed.normalize(1.5f))
        assertEquals(2.5f,PlaybackSpeed.normalize(2.5f))
        assertEquals(3f,PlaybackSpeed.normalize(3f))
    }

    @Test fun speedRangeUsesFiveHundredthsStepsAcrossAllPlatforms() {
        assertEquals(51,PlaybackSpeed.options.size)
        assertTrue(PlaybackSpeed.options.contains(0.5f))
        assertTrue(PlaybackSpeed.options.contains(1.05f))
        assertEquals(listOf(0.5f,1f,1.5f,2f,2.5f,3f),PlaybackSpeed.quickOptions)
        assertTrue(PlaybackSpeed.isValid(0.5f))
        assertTrue(PlaybackSpeed.isValid(1.05f))
        assertFalse(PlaybackSpeed.isValid(1.01f))
        assertTrue(PlaybackSpeed.isValid(3f))
        assertEquals("3.00×",PlaybackSpeed.label(3f))
    }

    @Test fun labelsRemainReadableAcrossDeviceLocales() {
        val original=Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.50×",PlaybackSpeed.label(0.5f))
            assertEquals("1.05×",PlaybackSpeed.label(1.05f))
        } finally { Locale.setDefault(original) }
    }

    @Test fun missingOrUnknownContentPreferenceDefaultsToComic() {
        assertEquals(ContentType.COMIC,ContentType.fromStored(null))
        assertEquals(ContentType.COMIC,ContentType.fromStored("unknown"))
        assertEquals(ContentType.SHORT,ContentType.fromStored("short"))
    }
}
