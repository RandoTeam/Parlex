package com.translive.app

import android.app.Application
import com.translive.app.data.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TransLiveApp : Application() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.setFileLoggingEnabled(settings.fileLoggingEnabled)
        AppLog.i("TransLiveApp", "App started")
    }
}
