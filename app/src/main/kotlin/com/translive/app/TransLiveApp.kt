package com.translive.app

import android.app.Application
import com.translive.app.engine.SystemTtsEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TransLiveApp : Application() {

    @Inject
    lateinit var systemTts: SystemTtsEngine

    override fun onCreate() {
        super.onCreate()
        // TTS is used by text, camera, dictionary and dialogue surfaces. Initialise it
        // once for the process so no screen depends on another screen having been opened.
        systemTts.initialize()
    }
}
