package com.translive.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_history")
data class TranslationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceText: String,
    val translatedText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)

@Entity(tableName = "dialogue_sessions")
data class DialogueSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val languageA: String,
    val languageB: String,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "dialogue_messages")
data class DialogueMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val speaker: String, // "A" or "B"
    val originalText: String,
    val translatedText: String,
    val originalLanguage: String,
    val translatedLanguage: String,
    val timestamp: Long = System.currentTimeMillis()
)
