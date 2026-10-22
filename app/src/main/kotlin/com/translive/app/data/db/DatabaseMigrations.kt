package com.translive.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema history for `translive.db`.
 *
 * The database was previously opened with `fallbackToDestructiveMigration()`,
 * which silently erased history, dictionaries and dialogue recordings whenever
 * the schema changed. These migrations preserve user data on upgrade.
 */
object DatabaseMigrations {

    /** v1 -> v2: dialogue messages gained the favourite flag. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `dialogue_messages` ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0"
            )
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
                    `isFavorite` INTEGER NOT NULL DEFAULT 0,
                    `isCustom` INTEGER NOT NULL DEFAULT 0,
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
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `totalTurns` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `totalWords` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `totalCharacters` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `isRecorded` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `audioFilePath` TEXT")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `audioFormat` TEXT")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `summary` TEXT")
            db.execSQL("ALTER TABLE `dialogue_sessions` ADD COLUMN `summaryTimestamp` INTEGER")

            db.execSQL("ALTER TABLE `dialogue_messages` ADD COLUMN `audioStartTimeMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_messages` ADD COLUMN `audioDurationMs` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_messages` ADD COLUMN `wordCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `dialogue_messages` ADD COLUMN `characterCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5
    )
}
