package com.translive.app.engine.tqi

data class DimensionScore(
    val name: String,
    val score: Float,
    val maxScore: Float = 25.0f,
    val details: List<String> = emptyList()
)

data class TqiEvaluationResult(
    val fixtureId: String,
    val fixtureName: String,
    val totalScore: Float,
    val geometryScore: DimensionScore,
    val inpaintingScore: DimensionScore,
    val typographyScore: DimensionScore,
    val semanticScore: DimensionScore,
    val isPassed: Boolean,
    val criticalIssues: List<String> = emptyList()
) {
    fun summaryString(): String = buildString {
        appendLine("================================================================================")
        appendLine(" TQI EVALUATION REPORT: $fixtureName ($fixtureId)")
        appendLine(" TOTAL SCORE: %.1f / 100.0 | STATUS: %s".format(totalScore, if (isPassed) "PASSED (>=85)" else "FAILED"))
        appendLine("--------------------------------------------------------------------------------")
        appendLine(" 1. Geometry & Anchoring:     %4.1f / 25.0 pts".format(geometryScore.score))
        geometryScore.details.forEach { appendLine("    - $it") }
        appendLine(" 2. Inpainting & Erase:       %4.1f / 25.0 pts".format(inpaintingScore.score))
        inpaintingScore.details.forEach { appendLine("    - $it") }
        appendLine(" 3. Typography & WCAG:        %4.1f / 25.0 pts".format(typographyScore.score))
        typographyScore.details.forEach { appendLine("    - $it") }
        appendLine(" 4. Semantics & Noise Guard:  %4.1f / 25.0 pts".format(semanticScore.score))
        semanticScore.details.forEach { appendLine("    - $it") }
        if (criticalIssues.isNotEmpty()) {
            appendLine(" CRITICAL ISSUES:")
            criticalIssues.forEach { appendLine("    [!] $it") }
        }
        appendLine("================================================================================")
    }
}
