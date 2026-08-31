package com.translive.app.data.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    @Test
    fun `ModelCatalog contains all required model families`() {
        val families = ModelCatalog.ALL_FAMILIES
        assertTrue(families.isNotEmpty(), "Catalog must not be empty")
        assertEquals(5, families.size, "Catalog must contain exactly 5 families")

        val familyIds = families.map { it.id }.toSet()
        assertTrue(familyIds.contains("hy_mt"), "HY-MT 1.5 family missing")
        assertTrue(familyIds.contains("hy_mt2_1_8b"), "Hy-MT2 1.8B family missing")
        assertTrue(familyIds.contains("hy_mt2_7b"), "Hy-MT2 7B family missing")
        assertTrue(familyIds.contains("translate_gemma"), "TranslateGemma family missing")
        assertTrue(familyIds.contains("gemma_4_litert"), "Gemma 4 LiteRT family missing")
    }

    @Test
    fun `All model variants have valid namespaced IDs matching family ID`() {
        for (family in ModelCatalog.ALL_FAMILIES) {
            assertTrue(family.variants.isNotEmpty(), "Family ${family.id} has no variants")
            for (variant in family.variants) {
                assertTrue(variant.id.startsWith("${family.id}:"), "Variant ID ${variant.id} must start with ${family.id}:")
                assertEquals(family.id, variant.familyId)
                assertTrue(variant.quantId.isNotEmpty(), "Quant ID in ${variant.id} is empty")
            }
        }
    }

    @Test
    fun `All variant IDs and filenames across entire catalog are globally unique`() {
        val allVariants = ModelCatalog.ALL_FAMILIES.flatMap { it.variants }
        val idSet = mutableSetOf<String>()
        val filenameSet = mutableSetOf<String>()

        for (v in allVariants) {
            assertTrue(idSet.add(v.id), "Duplicate variant ID: ${v.id}")
            assertTrue(filenameSet.add(v.filename.lowercase()), "Duplicate filename: ${v.filename}")
        }
    }

    @Test
    fun `All download URLs are HTTPS and resolve correctly`() {
        for (family in ModelCatalog.ALL_FAMILIES) {
            for (variant in family.variants) {
                assertTrue(variant.downloadUrl.startsWith("https://"), "URL for ${variant.id} must be HTTPS: ${variant.downloadUrl}")
                assertTrue(variant.downloadUrl.contains("huggingface.co/"), "URL for ${variant.id} must point to HuggingFace")
                assertTrue(variant.downloadUrl.contains(variant.filename), "URL for ${variant.id} must include filename ${variant.filename}")
            }
        }
    }

    @Test
    fun `All variants have realistic file sizes and positive RAM estimates`() {
        for (variant in ModelCatalog.ALL_FAMILIES.flatMap { it.variants }) {
            assertTrue(variant.sizeBytes > 100_000_000L, "Variant ${variant.id} size too small: ${variant.sizeBytes}")
            assertTrue(variant.ramEstimateMb >= 500, "Variant ${variant.id} RAM estimate too small: ${variant.ramEstimateMb}")
            val sizeMb = variant.sizeBytes / (1024 * 1024)
            assertTrue(variant.ramEstimateMb > sizeMb * 0.8, "Variant ${variant.id} RAM estimate should exceed file size footprint")
        }
    }

    @Test
    fun `LiteRT-LM models have valid SHA-256 and hardware runtime flags`() {
        val litertVariants = ModelCatalog.ALL_FAMILIES
            .flatMap { it.variants }
            .filter { it.runtime == ModelRuntime.LITERT_LM }

        assertTrue(litertVariants.isNotEmpty(), "LiteRT variants must exist")
        for (v in litertVariants) {
            val sha = v.sha256
            assertNotNull(sha, "LiteRT variant ${v.id} must have pinned SHA-256")
            assertEquals(64, sha.length, "SHA-256 for ${v.id} must be 64 hex characters")
            assertTrue(v.supportsGpu || v.supportsCpu, "LiteRT variant ${v.id} must support at least CPU or GPU")
            assertEquals(ModelPerformanceTier.GPU_ACCELERATED, v.performanceTier)
        }
    }

    @Test
    fun `Every family has exactly one recommended variant`() {
        for (family in ModelCatalog.ALL_FAMILIES) {
            val recCount = family.variants.count { it.isRecommended }
            assertEquals(1, recCount, "Family ${family.id} must have exactly one recommended variant (found $recCount)")
        }
    }

    @Test
    fun `Performance tiers classify variants consistently`() {
        val allVariants = ModelCatalog.ALL_FAMILIES.flatMap { it.variants }
        for (v in allVariants) {
            assertNotNull(v.performanceTier, "Variant ${v.id} must have a performance tier")
            when (v.performanceTier) {
                ModelPerformanceTier.FAST_BUDGET -> {
                    assertTrue(v.sizeBytes < 2_500_000_000L, "Fast/Budget variant ${v.id} should be compact")
                }
                ModelPerformanceTier.MAX_QUALITY -> {
                    assertTrue(v.ramEstimateMb >= 2_000, "Max quality variant ${v.id} should have adequate RAM allocation")
                }
                ModelPerformanceTier.GPU_ACCELERATED -> {
                    assertTrue(v.supportsGpu, "GPU accelerated variant ${v.id} must have supportsGpu = true")
                }
                ModelPerformanceTier.BALANCED -> {}
            }
        }
    }

    @Test
    fun `ModelFamily findById and familyOf resolve bidirectional relationships accurately`() {
        for (family in ModelCatalog.ALL_FAMILIES) {
            assertEquals(family, ModelFamily.findById(family.id))
            for (variant in family.variants) {
                assertEquals(variant, ModelFamily.findVariantById(variant.id))
                assertEquals(family, ModelFamily.familyOf(variant))
            }
        }
    }
}
