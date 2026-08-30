package com.translive.app.data.db

import androidx.room.*
import com.translive.app.data.model.DictionaryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DictionaryEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    @Query("""
        SELECT * FROM dictionary_entries 
        WHERE sourceLang = :sourceLang 
          AND targetLang = :targetLang 
          AND normalizedHeadword = :normalizedWord
        ORDER BY id ASC
    """)
    suspend fun lookup(normalizedWord: String, sourceLang: String, targetLang: String): List<DictionaryEntry>

    @Query("""
        SELECT * FROM dictionary_entries 
        WHERE sourceLang = :sourceLang 
          AND normalizedHeadword = :normalizedWord
        ORDER BY id ASC
    """)
    suspend fun lookupAnyTarget(normalizedWord: String, sourceLang: String): List<DictionaryEntry>

    @Query("""
        SELECT * FROM dictionary_entries 
        WHERE sourceLang = :sourceLang 
          AND targetLang = :targetLang 
          AND normalizedHeadword LIKE :query || '%'
        ORDER BY LENGTH(headword) ASC, headword ASC
        LIMIT :limit
    """)
    fun searchPrefix(query: String, sourceLang: String, targetLang: String, limit: Int = 20): Flow<List<DictionaryEntry>>

    @Query("""
        SELECT * FROM dictionary_entries 
        WHERE sourceLang = :sourceLang 
          AND normalizedHeadword LIKE :query || '%'
        ORDER BY LENGTH(headword) ASC, headword ASC
        LIMIT :limit
    """)
    fun searchAllPrefix(query: String, sourceLang: String, limit: Int = 20): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavorites(): Flow<List<DictionaryEntry>>

    @Query("UPDATE dictionary_entries SET isFavorite = :isFav WHERE id = :id")
    suspend fun setFavorite(id: Long, isFav: Boolean)

    @Query("SELECT COUNT(*) FROM dictionary_entries WHERE sourceLang = :sourceLang AND targetLang = :targetLang")
    suspend fun getEntryCount(sourceLang: String, targetLang: String): Int

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    suspend fun getTotalEntryCount(): Int

    @Query("DELETE FROM dictionary_entries WHERE sourceLang = :sourceLang AND targetLang = :targetLang AND isCustom = :onlyCustom")
    suspend fun deleteByLanguagePair(sourceLang: String, targetLang: String, onlyCustom: Boolean = false)

    @Query("DELETE FROM dictionary_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}
