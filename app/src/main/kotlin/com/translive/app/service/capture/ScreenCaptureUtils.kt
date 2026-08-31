package com.translive.app.service.capture

data class StrideLayout(
    val rowPaddingBytes: Int,
    val paddedWidth: Int,
    val paddedHeight: Int,
    val requiresCropping: Boolean,
    val cropX: Int,
    val cropY: Int,
    val cropWidth: Int,
    val cropHeight: Int
)

object ScreenCaptureUtils {

    fun computeStrideLayout(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): StrideLayout {
        if (width <= 0 || height <= 0 || pixelStride <= 0) {
            return StrideLayout(
                rowPaddingBytes = 0,
                paddedWidth = maxOf(1, width),
                paddedHeight = maxOf(1, height),
                requiresCropping = false,
                cropX = 0,
                cropY = 0,
                cropWidth = maxOf(1, width),
                cropHeight = maxOf(1, height)
            )
        }

        val rowPadding = maxOf(0, rowStride - pixelStride * width)
        val paddedWidth = width + (rowPadding / pixelStride)
        val requiresCropping = rowPadding > 0

        return StrideLayout(
            rowPaddingBytes = rowPadding,
            paddedWidth = paddedWidth,
            paddedHeight = height,
            requiresCropping = requiresCropping,
            cropX = 0,
            cropY = 0,
            cropWidth = width,
            cropHeight = height
        )
    }
}
