package com.translive.app.service.assist

import android.service.voice.VoiceInteractionService
import android.util.Log

class ParlexVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "ParlexVoiceInteractionService is ready as active digital assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "ParlexVoiceInteractionService is shutting down")
    }

    companion object {
        private const val TAG = "ParlexVoiceAssist"
    }
}
