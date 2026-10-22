package com.translive.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema history for `translive.db`.
 *
 * The database was previously opened with `fallbackToDestructiveMigration()`,
 * which silently erased history, dictionaries and dialogue recordings whenever
 * the schema changed. These migrations preserve user data on upgrade.
 *
 * Tables that gain NOT NULL columns are recreated instead of using
 * `ALTER TABLE ... DEFAULT 0`. Room validates the exact column defaults, and a
 * recreated table keeps the schema byte-for-byte equivalent to a fresh install.
 */
object DatabaseMigrations {

    /** v1 -> v2: dialogue messages gained the favourite flag. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dialogue_messages_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `speaker` TEXT NOT NULL,
                    `originalText` TEXT NOT NULL,
                    `translatedText` TEXT NOT NULL,
                    `originalLanguage` TEXT NOT NULL,
                    `translatedLanguage` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `isFavorite` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `dialogue_messages_new`
                    (`id`, `sessionId`, `speaker`, `originalText`, `translatedText`,
                     `originalLanguage`, `translatedLanguage`, `timestamp`, `isFavorite`)
                SELECT `id`, `sessionId`, `speaker`, `originalText`, `translatedText`,
                       `originalLanguage`, `translatedLanguage`, `timestamp`, 0
                FROM `dialogue_messages`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `dialogue_messages`")
            db.execSQL("ALTER TABLE `dialogue_messages_new` RENAME TO `dialogue_messages`")
        }
    }

    /** v2 -> v3: offline dictionary. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dictionary_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `headword` TEXT NOT NULL,
                    `normalizedHeadword` TEXT NOT NULL,
                    `sourceLang` TEXT NOT NULL,
                    `targetLang` TEXT NOT NULL,
                    `partOfSpeech` TEXT,
                    `pronunciation` TEXT,
                    `definition` TEXT NOT NULL,
                    `examples` TEXT,
                    `isFavorite` INTEGER NOT NULL,
                    `isCustom` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dictionary_entries_sourceLang_targetLang_normalizedHeadword` " +
                    "ON `dictionary_entries` (`sourceLang`, `targetLang`, `normalizedHeadword`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dictionary_entries_sourceLang_normalizedHeadword` " +
                    "ON `dictionary_entries` (`sourceLang`, `normalizedHeadword`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dictionary_entries_sourceLang_targetLang_headword` " +
                    "ON `dictionary_entries` (`sourceLang`, `targetLang`, `headword`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dictionary_entries_isFavorite` " +
                    "ON `dictionary_entries` (`isFavorite`)"
            )
        }
    }

    /** v3 -> v4: offline currency baseline. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `exchange_rates` (
                    `currencyCode` TEXT NOT NULL,
                    `rateToUsd` REAL NOT NULL,
                    `lastUpdatedMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`currencyCode`)
                )
                """.trimIndent()
            )
        }
    }

    /** v4 -> v5: dialogue statistics, audio timeline and LLM summary. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dialogue_sessions_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `languageA` TEXT NOT NULL,
                    `languageB` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `totalTurns` INTEGER NOT NULL,
                    `totalWords` INTEGER NOT NULL,
                    `totalCharacters` INTEGER NOT NULL,
                    `isRecorded` INTEGER NOT NULL,
                    `audioFilePath` TEXT,
                    `audioFormat` TEXT,
                    `summary` TEXT,
                    `summaryTimestamp` INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `dialogue_sessions_new`
                    (`id`, `languageA`, `languageB`, `title`, `createdAt`, `updatedAt`,
                     `durationMs`, `totalTurns`, `totalWords`, `totalCharacters`,
                     `isRecorded`, `audioFilePath`, `audioFormat`, `summary`, `summaryTimestamp`)
                SELECT `id`, `languageA`, `languageB`, `title`, `createdAt`, `updatedAt`,
                       0, 0, 0, 0, 0, NULL, NULL, NULL, NULL
                FROM `dialogue_sessions`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `dialogue_sessions`")
            db.execSQL("ALTER TABLE `dialogue_sessions_new` RENAME TO `dialogue_sessions`")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dialogue_messages_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `speaker` TEXT NOT NULL,
                    `originalText` TEXT NOT NULL,
                    `translatedText` TEXT NOT NULL,
                    `originalLanguage` TEXT NOT NULL,
                    `translatedLanguage` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `isFavorite` INTEGER NOT NULL,
                    `audioStartTimeMs` INTEGER NOT NULL,
                    `audioDurationMs` INTEGER NOT NULL,
                    `wordCount` INTEGER NOT NULL,
                    `characterCount` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `dialogue_messages_new`
                    (`id`, `sessionId`, `speaker`, `originalText`, `translatedText`,
                     `originalLanguage`, `translatedLanguage`, `timestamp`, `isFavorite`,
                     `audioStartTimeMs`, `audioDurationMs`, `wordCount`, `characterCount`)
                SELECT `id`, `sessionId`, `speaker`, `originalText`, `translatedText`,
                       `originalLanguage`, `translatedLanguage`, `timestamp`, `isFavorite`,
                       0, 0, 0, 0
                FROM `dialogue_messages`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `dialogue_messages`")
            db.execSQL("ALTER TABLE `dialogue_messages_new` RENAME TO `dialogue_messages`")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5
    )
}
