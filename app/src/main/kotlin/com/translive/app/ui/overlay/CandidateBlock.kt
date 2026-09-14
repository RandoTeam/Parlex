package com.translive.app.ui.overlay

import android.graphics.Rect

data class CandidateBlock(
    val rawText: String,
    val boundingBox: Rect
)
