package com.translive.app.data

import com.translive.app.data.db.DictionaryDao
import com.translive.app.data.model.DictionaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictionaryRepository @Inject constructor(
    private val dictionaryDao: DictionaryDao
) {

    private val lookupCache = object : LinkedHashMap<String, List<DictionaryEntry>>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DictionaryEntry>>?): Boolean {
            return size > 200
        }
    }

    /**
     * Normalizes a search term or extracted word by stripping punctuation and lowercasing.
     */
    fun normalizeWord(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("^[.,!?:;\"'()\\[\\]{}<>«»…—\\-\\s]+|[.,!?:;\"'()\\[\\]{}<>«»…—\\-\\s]+$"), "")
    }

    suspend fun lookupWord(
        rawWord: String,
        sourceLang: String,
        targetLang: String? = null
    ): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        ensureSeeded()
        val normalized = normalizeWord(rawWord)
        if (normalized.isBlank()) return@withContext emptyList()

        val cacheKey = "$sourceLang:${targetLang ?: "any"}:$normalized"
        synchronized(lookupCache) {
            val cached = lookupCache[cacheKey]
            if (cached != null) return@withContext cached
        }

        val results = if (targetLang != null) {
            val directMatches = dictionaryDao.lookup(normalized, sourceLang, targetLang)
            if (directMatches.isNotEmpty()) {
                directMatches
            } else {
                dictionaryDao.lookupAnyTarget(normalized, sourceLang)
            }
        } else {
            dictionaryDao.lookupAnyTarget(normalized, sourceLang)
        }

        synchronized(lookupCache) {
            lookupCache[cacheKey] = results
        }

        results
    }

    fun searchPrefix(
        query: String,
        sourceLang: String,
        targetLang: String? = null,
        limit: Int = 20
    ): Flow<List<DictionaryEntry>> {
        val normalized = normalizeWord(query)
        return if (targetLang != null) {
            dictionaryDao.searchPrefix(normalized, sourceLang, targetLang, limit)
        } else {
            dictionaryDao.searchAllPrefix(normalized, sourceLang, limit)
        }
    }

    suspend fun getFavorites(): Flow<List<DictionaryEntry>> = withContext(Dispatchers.IO) {
        dictionaryDao.getFavorites()
    }

    suspend fun toggleFavorite(entry: DictionaryEntry) = withContext(Dispatchers.IO) {
        dictionaryDao.setFavorite(entry.id, !entry.isFavorite)
    }

    suspend fun getEntryCount(sourceLang: String, targetLang: String): Int = withContext(Dispatchers.IO) {
        dictionaryDao.getEntryCount(sourceLang, targetLang)
    }

    suspend fun getTotalEntryCount(): Int = withContext(Dispatchers.IO) {
        dictionaryDao.getTotalEntryCount()
    }

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        if (dictionaryDao.getTotalEntryCount() == 0) {
            val coreEntries = CoreDictionarySeeder.getCoreEntries()
            dictionaryDao.insertAll(coreEntries)
        }
    }

    /**
     * Imports entries from TSV/CSV format:
     * Line format: `headword\tdefinition\t[partOfSpeech]\t[pronunciation]\t[examples]`
     */
    suspend fun importFromTsv(
        tsvContent: String,
        sourceLang: String,
        targetLang: String,
        isCustom: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        val lines = tsvContent.lines().filter { it.isNotBlank() }
        val entries = mutableListOf<DictionaryEntry>()

        for (line in lines) {
            val tokens = line.split("\t")
            if (tokens.isNotEmpty()) {
                val headword = tokens[0].trim()
                if (headword.isNotBlank()) {
                    val definition = tokens.getOrNull(1)?.trim() ?: ""
                    val pos = tokens.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
                    val ipa = tokens.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() }
                    val examples = tokens.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() }

                    entries.add(
                        DictionaryEntry(
                            headword = headword,
                            normalizedHeadword = normalizeWord(headword),
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            partOfSpeech = pos,
                            pronunciation = ipa,
                            definition = definition,
                            examples = examples,
                            isCustom = isCustom
                        )
                    )
                }
            }
        }

        if (entries.isNotEmpty()) {
            dictionaryDao.insertAll(entries)
            synchronized(lookupCache) {
                lookupCache.clear()
            }
        }

        entries.size
    }

    suspend fun deleteCustomEntriesForPair(sourceLang: String, targetLang: String) = withContext(Dispatchers.IO) {
        dictionaryDao.deleteByLanguagePair(sourceLang, targetLang, onlyCustom = true)
        synchronized(lookupCache) {
            lookupCache.clear()
        }
    }
}
