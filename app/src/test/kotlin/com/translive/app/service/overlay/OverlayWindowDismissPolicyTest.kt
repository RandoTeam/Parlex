package com.translive.app.service.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWindowDismissPolicyTest {

    private val own = "com.translive.app"

    @Test
    fun keepsOverlayWhenSamePackageWindowChanges() {
        assertFalse(
            OverlayWindowDismissPolicy.shouldDismiss(
                sourcePackage = "com.example.app",
                eventPackage = "com.example.app",
                eventClassName = "com.example.app.Dialog",
                ownPackage = own,
                msSinceShown = 2_000L
            )
        )
    }

    @Test
    fun dismissesWhenAnotherAppComesToFront() {
        assertTrue(
            OverlayWindowDismissPolicy.shouldDismiss(
                sourcePackage = "com.example.app",
                eventPackage = "com.other.app",
                ownPackage = own,
                msSinceShown = 2_000L
            )
        )
    }

    @Test
    fun ignoresOwnOverlayWindow() {
        assertFalse(
            OverlayWindowDismissPolicy.shouldDismiss(
                sourcePackage = "com.example.app",
                eventPackage = own,
                ownPackage = own,
                msSinceShown = 2_000L
            )
        )
    }

    @Test
    fun ignoresImeWindow() {
        assertFalse(
            OverlayWindowDismissPolicy.shouldDismiss(
                sourcePackage = "com.example.app",
                eventPackage = "com.google.android.inputmethod.latin",
                eventClassName = "com.android.inputmethod.latin.LatinIME",
                ownPackage = own,
                msSinceShown = 2_000L
            )
        )
        assertFalse(
            OverlayWindowDismissPolicy.shouldDismiss(
                sourcePackage = "com.example.app",
                eventPackage = "com.samsung.android.honeyboard",
                ownPackage = own,
                msSinceShown = 2_000L
            )
        )
    }

    @Test
    fun keepsOverlayDuringInitialGuard() {
        assertFalse(
            OverlayWindowDismissPolicy.shouldDismiss(
                sourcePackage = "com.example.app",
                eventPackage = "com.other.app",
                ownPackage = own,
                msSinceShown = 100L
            )
        )
    }

    @Test
    fun keepsOverlayWhenSourceIsUnknown() {
        assertFalse(
            OverlayWindowDismissPolicy.shouldDismiss(
                sourcePackage = null,
                eventPackage = "com.other.app",
                ownPackage = own,
                msSinceShown = 2_000L
            )
        )
    }
}
