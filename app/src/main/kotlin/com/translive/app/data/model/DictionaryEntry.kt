package com.translive.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary_entries",
    indices = [
        Index(value = ["sourceLang", "targetLang", "normalizedHeadword"]),
        Index(value = ["sourceLang", "normalizedHeadword"]),
        Index(value = ["sourceLang", "targetLang", "headword"]),
        Index(value = ["isFavorite"])
    ]
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val headword: String,
    val normalizedHeadword: String,
    val sourceLang: String,
    val targetLang: String,
    val partOfSpeech: String? = null,
    val pronunciation: String? = null,
    val definition: String,
    val examples: String? = null,
    val isFavorite: Boolean = false,
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
