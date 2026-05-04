package com.translive.app.di

import android.content.Context
import androidx.room.Room
import com.translive.app.data.db.DialogueDao
import com.translive.app.data.db.TransLiveDatabase
import com.translive.app.data.db.TranslationDao
import com.translive.app.engine.TranslationEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TransLiveDatabase {
        return Room.databaseBuilder(
            context,
            TransLiveDatabase::class.java,
            "translive.db"
        ).build()
    }

    @Provides
    fun provideTranslationDao(db: TransLiveDatabase): TranslationDao = db.translationDao()

    @Provides
    fun provideDialogueDao(db: TransLiveDatabase): DialogueDao = db.dialogueDao()

    @Provides
    @Singleton
    fun provideTranslationEngine(): TranslationEngine = TranslationEngine()
}
