package com.translive.app.engine

import java.io.File
import java.security.MessageDigest

/**
 * Immutable contract for the bundled/imported PP-OCRv6 tiny MNN package.
 *
 * The package is deliberately not advertised as downloadable until a stable
 * signed host for the converted MNN artifacts exists. ONNX and MNN are not
 * interchangeable at runtime.
 */
object PpOcrPackage {
    const val ID = "pp-ocrv6-tiny-mnn"
    const val VERSION = "PP-OCRv6 tiny"

    data class Artifact(
        val id: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String
    )

    val detector = Artifact(
        id = "detector",
        fileName = "ppocrv6_tiny_det.mnn",
        sizeBytes = 1_747_696L,
        sha256 = "a137cff7134239eced46e056e9dab89bf82b758ddb6946056718e93cbae ca366".replace(" ", "")
    )
    val recognizer = Artifact(
        id = "recognizer",
        fileName = "ppocrv6_tiny_rec.mnn",
        sizeBytes = 4_434_100L,
        sha256 = "fe7123e0b01a8f6a218765827e4bcc31306cd78d763d17eda7b529583188490c"
    )
    val dictionary = Artifact(
        id = "dictionary",
        fileName = "ppocrv6_dict.txt",
        sizeBytes = PpOcrDictionary.UTF8_BYTES.toLong(),
        sha256 = PpOcrDictionary.SHA256
    )

    val artifacts = listOf(detector, recognizer, dictionary)

    data class Validation(
        val valid: Boolean,
        val message: String
    )

    fun validate(root: File): Validation {
        val failures = artifacts.mapNotNull { artifact ->
            val file = File(root, artifact.fileName)
            when {
                !file.isFile -> "${artifact.id}: file is missing"
                file.length() != artifact.sizeBytes ->
                    "${artifact.id}: size ${file.length()} != ${artifact.sizeBytes}"
                sha256(file) != artifact.sha256 -> "${artifact.id}: SHA-256 mismatch"
                artifact == dictionary && PpOcrDictionary.readValidated(file) == null ->
                    "dictionary: character contract mismatch"
                else -> null
            }
        }
        return if (failures.isEmpty()) Validation(true, "PP-OCRv6 tiny MNN package is valid")
        else Validation(false, failures.joinToString("; "))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
