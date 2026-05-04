package com.translive.app.data.db

import androidx.room.*
import com.translive.app.data.model.DialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.TranslationEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {
    @Insert
    suspend fun insertTranslation(entry: TranslationEntry): Long

    @Update
    suspend fun updateTranslation(entry: TranslationEntry)

    @Delete
    suspend fun deleteTranslation(entry: TranslationEntry)

    @Query("SELECT * FROM translation_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTranslations(limit: Int = 50): Flow<List<TranslationEntry>>

    @Query("SELECT * FROM translation_history WHERE isFavorite = 1 ORDER BY timestamp DESC")
    fun getFavorites(): Flow<List<TranslationEntry>>

    @Query("DELETE FROM translation_history WHERE isFavorite = 0")
    suspend fun clearNonFavoriteHistory()
}

@Dao
interface DialogueDao {
    @Insert
    suspend fun insertSession(session: DialogueSession): Long

    @Insert
    suspend fun insertMessage(message: DialogueMessage): Long

    @Query("SELECT * FROM dialogue_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<DialogueSession>>

    @Query("SELECT * FROM dialogue_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Long): Flow<List<DialogueMessage>>

    @Query("UPDATE dialogue_sessions SET updatedAt = :time WHERE id = :sessionId")
    suspend fun updateSessionTime(sessionId: Long, time: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteSession(session: DialogueSession)

    @Query("DELETE FROM dialogue_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)
}

@Database(
    entities = [TranslationEntry::class, DialogueSession::class, DialogueMessage::class],
    version = 1,
    exportSchema = false
)
abstract class TransLiveDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao
    abstract fun dialogueDao(): DialogueDao
}
