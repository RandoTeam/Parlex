package com.translive.app.engine.tqi

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1 Host Benchmark Suite executing the 100-Point AR Translation Quality Index (TQI)
 * across 4 real-world screen archetypes.
 */
class TqiHostBenchmarkTest {

    @Test
    fun benchmark_archetypeWebSearch_achievesPassingTqi() {
        val fixture = ScreenFixtures.ARCHETYPE_WEB_SEARCH
        val result = TqiEvaluator.evaluate(fixture)

        println(result.summaryString())

        assertTrue(
            "Expected Web Search archetype to pass with TQI >= 85, got ${result.totalScore}: ${result.criticalIssues}",
            result.isPassed
        )
        assertTrue("Total TQI score must be >= 85", result.totalScore >= 85.0f)
    }

    @Test
    fun benchmark_archetypeMessengerChat_achievesPassingTqi() {
        val fixture = ScreenFixtures.ARCHETYPE_MESSENGER_CHAT
        val result = TqiEvaluator.evaluate(fixture)

        println(result.summaryString())

        assertTrue(
            "Expected Messenger Chat archetype to pass with TQI >= 85, got ${result.totalScore}: ${result.criticalIssues}",
            result.isPassed
        )
        assertTrue("Total TQI score must be >= 85", result.totalScore >= 85.0f)
    }

    @Test
    fun benchmark_archetypeDocumentation_achievesPassingTqi() {
        val fixture = ScreenFixtures.ARCHETYPE_DOCUMENTATION
        val result = TqiEvaluator.evaluate(fixture)

        println(result.summaryString())

        assertTrue(
            "Expected Documentation archetype to pass with TQI >= 85, got ${result.totalScore}: ${result.criticalIssues}",
            result.isPassed
        )
        assertTrue("Total TQI score must be >= 85", result.totalScore >= 85.0f)
    }

    @Test
    fun benchmark_archetypeAppStoreGrid_achievesPassingTqi() {
        val fixture = ScreenFixtures.ARCHETYPE_APP_STORE_GRID
        val result = TqiEvaluator.evaluate(fixture)

        println(result.summaryString())

        assertTrue(
            "Expected App Store Grid archetype to pass with TQI >= 85, got ${result.totalScore}: ${result.criticalIssues}",
            result.isPassed
        )
        assertTrue("Total TQI score must be >= 85", result.totalScore >= 85.0f)
    }

    @Test
    fun benchmark_allArchetypes_averageTqiExceedsNinety() {
        val results = ScreenFixtures.ALL_ARCHETYPES.map { TqiEvaluator.evaluate(it) }
        val avgScore = results.map { it.totalScore }.average()

        println("================================================================================")
        println(" OVERALL AR QUALITY SCORE ACROSS ALL 4 ARCHETYPES: %.1f / 100.0".format(avgScore))
        println("================================================================================")

        for (res in results) {
            assertTrue("Archetype '${res.fixtureId}' failed: ${res.criticalIssues}", res.isPassed)
        }
        assertTrue("Average TQI score across all real-world archetypes must exceed 90.0, got $avgScore", avgScore >= 90.0)
    }
}
