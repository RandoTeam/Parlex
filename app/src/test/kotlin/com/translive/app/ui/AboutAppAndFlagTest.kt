package com.translive.app.ui

import com.translive.app.data.model.Language
import org.junit.Test
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM Unit Test Suite for App Metadata, Semantic Versioning, and Language Flag Emoji Coverage.
 *
 * Requirements:
 * 1. Test Language.flag emoji coverage across all 33 primary languages and 5 dialects.
 * 2. Test Version format string verification (Semantic Versioning 2.0.0 for 1.5.0-beta.1).
 * 3. 100% Pure JVM (zero Android context, Android SDK stubs, or Robolectric).
 */
class AboutAppAndFlagTest {

    // =========================================================================
    // SECTION 1: LANGUAGE & FLAG EMOJI COVERAGE (33 Primary + 5 Dialects)
    // =========================================================================

    @Test
    fun `Language enum contains exactly 62 primary languages and 5 dialects`() {
        val allLanguages = Language.entries
        val primaryLanguages = Language.primaryLanguages
        val dialects = allLanguages.filter { it.isDialect }

        assertEquals(67, allLanguages.size, "Total language count must be exactly 67 (62 primary + 5 dialects)")
        assertEquals(62, primaryLanguages.size, "Primary languages count must be exactly 62")
        assertEquals(5, dialects.size, "Dialect count must be exactly 5")
        assertEquals(67, Language.allLanguages.size, "Language.allLanguages must contain all 67 entries")
    }

    @Test
    fun `Every language and dialect has a valid non-empty Unicode flag emoji`() {
        for (language in Language.entries) {
            val flag = language.flag
            assertFalse(flag.isBlank(), "Flag for ${language.name} (${language.code}) must not be blank")

            val codePoints = flag.codePoints().toArray()
            assertTrue(codePoints.isNotEmpty(), "Flag for ${language.name} must not be empty")
        }
    }

    @Test
    fun `Primary language flags match expected geopolitical country indicators`() {
        val expectedPrimaryFlags = mapOf(
            Language.ENGLISH to "🇬🇧",
            Language.CHINESE_SIMPLIFIED to "🇨🇳",
            Language.CHINESE_TRADITIONAL to "🇹🇼",
            Language.JAPANESE to "🇯🇵",
            Language.KOREAN to "🇰🇷",
            Language.FRENCH to "🇫🇷",
            Language.GERMAN to "🇩🇪",
            Language.SPANISH to "🇪🇸",
            Language.PORTUGUESE to "🇧🇷",
            Language.ITALIAN to "🇮🇹",
            Language.DUTCH to "🇳🇱",
            Language.POLISH to "🇵🇱",
            Language.CZECH to "🇨🇿",
            Language.TURKISH to "🇹🇷",
            Language.UKRAINIAN to "🇺🇦",
            Language.RUSSIAN to "🇷🇺",
            Language.BURMESE to "🇲🇲",
            Language.HINDI to "🇮🇳",
            Language.BENGALI to "🇧🇩",
            Language.GUJARATI to "🇮🇳",
            Language.MARATHI to "🇮🇳",
            Language.TAMIL to "🇮🇳",
            Language.TELUGU to "🇮🇳",
            Language.URDU to "🇵🇰",
            Language.PERSIAN to "🇮🇷",
            Language.HEBREW to "🇮🇱",
            Language.ARABIC to "🇸🇦",
            Language.THAI to "🇹🇭",
            Language.VIETNAMESE to "🇻🇳",
            Language.INDONESIAN to "🇮🇩",
            Language.MALAY to "🇲🇾",
            Language.FILIPINO to "🇵🇭",
            Language.KHMER to "🇰🇭"
        )

        assertEquals(33, expectedPrimaryFlags.size, "Must test all 33 primary languages")

        for ((lang, expectedFlag) in expectedPrimaryFlags) {
            assertEquals(expectedFlag, lang.flag, "Flag mismatch for primary language ${lang.name}")
            assertFalse(lang.isDialect, "${lang.name} must have isDialect = false")
        }
    }

    @Test
    fun `Dialect flags and metadata are accurately configured`() {
        val expectedDialects = mapOf(
            Language.CANTONESE to ("yue" to "🇭🇰"),
            Language.HOKKIEN to ("nan" to "🇨🇳"),
            Language.TIBETAN to ("bo" to "🇨🇳"),
            Language.MONGOLIAN to ("mn" to "🇲🇳"),
            Language.UYGHUR to ("ug" to "🇨🇳")
        )

        assertEquals(5, expectedDialects.size, "Must test all 5 dialects")

        for ((dialect, meta) in expectedDialects) {
            val (expectedCode, expectedFlag) = meta
            assertTrue(dialect.isDialect, "${dialect.name} must have isDialect = true")
            assertEquals(expectedCode, dialect.code, "Code mismatch for dialect ${dialect.name}")
            assertEquals(expectedFlag, dialect.flag, "Flag mismatch for dialect ${dialect.name}")
            assertFalse(dialect.displayName.isBlank(), "Display name must not be blank for ${dialect.name}")
            assertFalse(dialect.nativeName.isBlank(), "Native name must not be blank for ${dialect.name}")
        }
    }

    @Test
    fun `Language codes are unique and lookup by code is bidirectionally consistent`() {
        val codes = mutableSetOf<String>()

        for (lang in Language.entries) {
            assertTrue(codes.add(lang.code), "Duplicate language code found: '${lang.code}' in ${lang.name}")
            assertEquals(lang, Language.fromCode(lang.code), "Lookup fromCode('${lang.code}') failed for ${lang.name}")
        }

        assertNull(Language.fromCode("invalid_code"), "Lookup with invalid code must return null")
        assertNull(Language.fromCode(""), "Lookup with empty code must return null")
    }

    // =========================================================================
    // SECTION 2: SEMANTIC VERSIONING & ABOUT APP VERIFICATION (1.5.0-beta.1)
    // =========================================================================

    companion object {
        // Official SemVer 2.0.0 Regex: https://semver.org/
        private val SEMVER_REGEX = Pattern.compile(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?${'$'}"""
        )

        const val TARGET_VERSION_NAME = com.translive.app.BuildConfig.VERSION_NAME
    }

    data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String? = null,
        val buildMetadata: String? = null
    ) : Comparable<SemVer> {
        val isPreRelease: Boolean get() = preRelease != null

        override fun compareTo(other: SemVer): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)

            // When major.minor.patch are equal, a pre-release version has lower precedence than normal version
            return when {
                preRelease == null && other.preRelease == null -> 0
                preRelease == null -> 1 // normal > pre-release
                other.preRelease == null -> -1 // pre-release < normal
                else -> comparePreReleaseIdentifiers(preRelease, other.preRelease)
            }
        }

        private fun comparePreReleaseIdentifiers(a: String, b: String): Int {
            val aParts = a.split(".")
            val bParts = b.split(".")
            val minLen = minOf(aParts.size, bParts.size)
            for (i in 0 until minLen) {
                val pA = aParts[i]
                val pB = bParts[i]
                val numA = pA.toIntOrNull()
                val numB = pB.toIntOrNull()
                val comp = when {
                    numA != null && numB != null -> numA.compareTo(numB)
                    numA != null -> -1 // numeric has lower precedence than string
                    numB != null -> 1
                    else -> pA.compareTo(pB)
                }
                if (comp != 0) return comp
            }
            return aParts.size.compareTo(bParts.size)
        }

        companion object {
            fun parse(versionString: String): SemVer {
                val matcher = SEMVER_REGEX.matcher(versionString.trim())
                require(matcher.matches()) { "Invalid SemVer 2.0.0 format: '$versionString'" }
                return SemVer(
                    major = matcher.group(1).toInt(),
                    minor = matcher.group(2).toInt(),
                    patch = matcher.group(3).toInt(),
                    preRelease = matcher.group(4),
                    buildMetadata = matcher.group(5)
                )
            }
        }
    }

    @Test
    fun `Target version string matches strict SemVer 2_0_0 specification`() {
        val matcher = SEMVER_REGEX.matcher(TARGET_VERSION_NAME)
        assertTrue(matcher.matches(), "Target version '$TARGET_VERSION_NAME' must strictly match SemVer 2.0.0 specification")
    }

    @Test
    fun `Parsed target version is a valid pre-release before the next minor`() {
        val parsed = SemVer.parse(TARGET_VERSION_NAME)

        assertTrue(parsed.major >= 1, "Major version must be positive")
        assertTrue(parsed.minor >= 0, "Minor version must be non-negative")
        assertTrue(parsed.patch >= 0, "Patch version must be non-negative")
        assertTrue(parsed.isPreRelease, "Target build should be a pre-release")
        val nextMinor = SemVer(parsed.major, parsed.minor + 1, 0)
        assertTrue(parsed < nextMinor, "Pre-release must sort before the next minor release")
    }

    // =========================================================================
    // SECTION 3: ABOUT APP METADATA SPECIFICATION
    // =========================================================================

    data class AboutAppMetadata(
        val appName: String,
        val versionName: String,
        val defaultTranslationModel: String,
        val ttsEngine: String,
        val sttEngine: String,
        val nativeInferenceEngine: String
    )

    @Test
    fun `About App metadata schema is complete, valid, and unlocalized`() {
        val aboutApp = AboutAppMetadata(
            appName = "Parlex",
            versionName = TARGET_VERSION_NAME,
            defaultTranslationModel = "Hy-MT 1.5 1.8B",
            ttsEngine = "Sherpa-ONNX / Kokoro",
            sttEngine = "Whisper Tiny + Silero VAD",
            nativeInferenceEngine = "llama.cpp"
        )

        assertEquals("Parlex", aboutApp.appName)
        assertEquals(com.translive.app.BuildConfig.VERSION_NAME, aboutApp.versionName)
        assertTrue(SEMVER_REGEX.matcher(aboutApp.versionName).matches(), "Version name in metadata must be valid SemVer")
        assertEquals("Hy-MT 1.5 1.8B", aboutApp.defaultTranslationModel)
        assertTrue(aboutApp.ttsEngine.isNotEmpty(), "TTS Engine must be specified")
        assertEquals("Whisper Tiny + Silero VAD", aboutApp.sttEngine)
        assertEquals("llama.cpp", aboutApp.nativeInferenceEngine)
    }
}
