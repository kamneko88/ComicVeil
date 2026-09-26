package com.kamneko88.comicveil.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldRelockOnResumeTest {

    @Test
    fun `within grace period does not relock`() {
        assertFalse(shouldRelockOnResume(backgroundedAt = 0L, resumedAt = LOCK_GRACE_PERIOD_MS - 1))
    }

    @Test
    fun `exactly at grace period relocks`() {
        assertTrue(shouldRelockOnResume(backgroundedAt = 0L, resumedAt = LOCK_GRACE_PERIOD_MS))
    }

    @Test
    fun `beyond grace period relocks`() {
        assertTrue(shouldRelockOnResume(backgroundedAt = 0L, resumedAt = LOCK_GRACE_PERIOD_MS + 1))
    }

    @Test
    fun `null backgroundedAt does not relock`() {
        assertFalse(shouldRelockOnResume(backgroundedAt = null, resumedAt = 1_000_000L))
    }
}
