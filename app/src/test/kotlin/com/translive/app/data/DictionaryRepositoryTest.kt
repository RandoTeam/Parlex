package com.translive.app.data

import com.translive.app.data.db.DictionaryDao
import com.translive.app.data.model.DictionaryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DictionaryRepositoryTest {

    private class FakeDictionaryDao : DictionaryDao {
        val entries = mutableListOf<DictionaryEntry>()

        override suspend fun insertEntry(entry: DictionaryEntry): Long {
            entries.add(entry)
            return entries.size.toLong()
        }

        override suspend fun insertAll(newEntries: List<DictionaryEntry>) {
            entries.addAll(newEntries)
        }

        override suspend fun lookup(normalizedWord: String, sourceLang: String, targetLang: String): List<DictionaryEntry> {
            return entries.filter { it.normalizedHeadword == normalizedWord && it.sourceLang == sourceLang && it.targetLang == targetLang }
        }

        override suspend fun lookupAnyTarget(normalizedWord: String, sourceLang: String): List<DictionaryEntry> {
            return entries.filter { it.normalizedHeadword == normalizedWord && it.sourceLang == sourceLang }
        }

        override fun searchPrefix(query: String, sourceLang: String, targetLang: String, limit: Int): Flow<List<DictionaryEntry>> {
            return flowOf(entries.filter { it.normalizedHeadword.startsWith(query) && it.sourceLang == sourceLang && it.targetLang == targetLang }.take(limit))
        }

        override fun searchAllPrefix(query: String, sourceLang: String, limit: Int): Flow<List<DictionaryEntry>> {
            return flowOf(entries.filter { it.normalizedHeadword.startsWith(query) && it.sourceLang == sourceLang }.take(limit))
        }

        override fun getFavorites(): Flow<List<DictionaryEntry>> {
            return flowOf(entries.filter { it.isFavorite })
        }

        override suspend fun setFavorite(id: Long, isFav: Boolean) {
            val idx = entries.indexOfFirst { it.id == id }
            if (idx != -1) {
                entries[idx] = entries[idx].copy(isFavorite = isFav)
            }
        }

        override suspend fun getEntryCount(sourceLang: String, targetLang: String): Int {
            return entries.count { it.sourceLang == sourceLang && it.targetLang == targetLang }
        }

        override suspend fun getTotalEntryCount(): Int = entries.size

        override suspend fun deleteByLanguagePair(sourceLang: String, targetLang: String, onlyCustom: Boolean) {
            entries.removeAll { it.sourceLang == sourceLang && it.targetLang == targetLang && (!onlyCustom || it.isCustom) }
        }

        override suspend fun deleteById(id: Long) {
            entries.removeAll { it.id == id }
        }
    }

    private val fakeDao = FakeDictionaryDao()
    private val repository = DictionaryRepository(fakeDao)

    @Test
    fun `normalizeWord strips punctuation and lowercases`() {
        assertEquals("hello", repository.normalizeWord("Hello!"))
        assertEquals("world", repository.normalizeWord("  ...world???  "))
        assertEquals("привет", repository.normalizeWord("ПРИВЕТ,"))
        assertEquals("c++", repository.normalizeWord("c++"))
    }

    @Test
    fun `ensureSeeded populates core dictionary entries`() = runBlocking {
        assertEquals(0, fakeDao.getTotalEntryCount())
        repository.ensureSeeded()
        assertTrue(fakeDao.getTotalEntryCount() > 0)

        val resultsEnRu = repository.lookupWord("hello", "en", "ru")
        assertTrue(resultsEnRu.isNotEmpty())
        assertEquals("hello", resultsEnRu.first().headword)

        val resultsRuEn = repository.lookupWord("привет", "ru", "en")
        assertTrue(resultsRuEn.isNotEmpty())
        assertEquals("привет", resultsRuEn.first().headword)
    }

    @Test
    fun `importFromTsv parses tab-separated entries correctly`() = runBlocking {
        val tsv = """
            algorithm	алгоритм, пошаговый порядок	noun	/ˈælɡərɪðəm/	Sorting algorithm.
            compute	вычислять, делать расчёты	verb	/kəmˈpjuːt/	Compute hash.
        """.trimIndent()

        val count = repository.importFromTsv(tsv, sourceLang = "en", targetLang = "ru", isCustom = true)
        assertEquals(2, count)

        val found = repository.lookupWord("algorithm", "en", "ru")
        assertEquals(1, found.size)
        assertEquals("алгоритм, пошаговый порядок", found.first().definition)
        assertEquals("noun", found.first().partOfSpeech)
        assertEquals("/ˈælɡərɪðəm/", found.first().pronunciation)
        assertEquals(true, found.first().isCustom)
    }
}
