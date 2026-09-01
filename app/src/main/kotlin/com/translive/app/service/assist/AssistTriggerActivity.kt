package com.translive.app.service.assist

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.translive.app.service.ScreenTranslateOverlayService

class AssistTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "AssistTriggerActivity invoked with action: ${intent?.action}")

        ScreenTranslateOverlayService.start(this)
        finish()
    }

    companion object {
        private const val TAG = "AssistTriggerActivity"
    }
}
