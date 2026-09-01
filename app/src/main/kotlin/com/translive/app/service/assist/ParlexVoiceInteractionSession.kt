package com.translive.app.service.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.translive.app.service.ScreenTranslateOverlayService

class ParlexVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "ParlexVoiceInteractionSession onShow with flags: $showFlags")
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        Log.i(TAG, "onHandleScreenshot received: ${screenshot?.width}x${screenshot?.height}")
        if (screenshot != null) {
            ScreenTranslateOverlayService.translateScreenshot(context, screenshot)
        } else {
            ScreenTranslateOverlayService.start(context)
        }
        hide()
    }

    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        Log.i(TAG, "onHandleAssist invoked with structure: ${structure != null}")
    }

    companion object {
        private const val TAG = "ParlexVoiceSession"
    }
}
