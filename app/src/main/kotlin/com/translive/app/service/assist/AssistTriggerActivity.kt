package com.translive.app.service.assist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.translive.app.service.ScreenTranslateOverlayService

class AssistTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        Log.i(TAG, "AssistTriggerActivity invoked with action: $action")
        val allowed = action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == "heytap.intent.action.ACTIVATE_SPEECH_ASSIST" ||
            action == "coloros.speech.action.THIRD_PARTY_MESSAGE"
        if (!allowed) {
            finish()
            return
        }

        ScreenTranslateOverlayService.start(this)
        finish()
    }

    companion object {
        private const val TAG = "AssistTriggerActivity"
    }
}
