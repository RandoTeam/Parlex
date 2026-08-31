package com.translive.app.service

import com.translive.app.service.capture.CaptureDisplayMetrics
import com.translive.app.service.capture.ScreenCaptureController
import com.translive.app.service.capture.ScreenCaptureState
import com.translive.app.service.capture.ScreenCaptureUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureControllerTest {

    @Test
    fun initialState_isUnattached() {
        val controller = ScreenCaptureController()
        assertEquals(ScreenCaptureState.UNATTACHED, controller.state)
        assertFalse(controller.isReady)
        assertFalse(controller.isCapturing)
        controller.release()
    }

    @Test
    fun computeStrideLayout_withZeroPadding_requiresNoCrop() {
        val layout = ScreenCaptureUtils.computeStrideLayout(
            width = 1080,
            height = 1920,
            rowStride = 4320,
            pixelStride = 4
        )

        assertEquals(0, layout.rowPaddingBytes)
        assertEquals(1080, layout.paddedWidth)
        assertEquals(1920, layout.paddedHeight)
        assertFalse(layout.requiresCropping)
        assertEquals(0, layout.cropX)
        assertEquals(0, layout.cropY)
        assertEquals(1080, layout.cropWidth)
        assertEquals(1920, layout.cropHeight)
    }

    @Test
    fun computeStrideLayout_withRowPadding_calculatesCorrectPaddedDimensionsAndCropRect() {
        val layout = ScreenCaptureUtils.computeStrideLayout(
            width = 1080,
            height = 2400,
            rowStride = 4608,
            pixelStride = 4
        )

        assertEquals(288, layout.rowPaddingBytes)
        assertEquals(1152, layout.paddedWidth)
        assertEquals(2400, layout.paddedHeight)
        assertTrue(layout.requiresCropping)
        assertEquals(0, layout.cropX)
        assertEquals(0, layout.cropY)
        assertEquals(1080, layout.cropWidth)
        assertEquals(2400, layout.cropHeight)
    }

    @Test
    fun computeStrideLayout_withInvalidPixelStride_fallsBackSafelyWithoutException() {
        val layout = ScreenCaptureUtils.computeStrideLayout(
            width = 1080,
            height = 1920,
            rowStride = 4320,
            pixelStride = 0
        )

        assertEquals(1080, layout.paddedWidth)
        assertEquals(1920, layout.paddedHeight)
        assertFalse(layout.requiresCropping)
    }

    @Test
    fun updateMetrics_withZeroOrNegativeDimensions_rejectsChange() {
        val controller = ScreenCaptureController()
        val invalid = controller.updateDisplayMetrics(-100, 2400, 420)
        assertFalse(invalid)
        controller.release()
    }
}
