package com.translive.app.engine

import java.io.File
import java.security.MessageDigest

/** Versioned PP-OCR character dictionary contract. */
object PpOcrDictionary {
    const val CHARACTER_COUNT = 6904
    const val UTF8_BYTES = 27156
    const val SHA256 = "c5cbe34ef40c29c4df07ed012bf96569cb69a2d2a01a07027e9f13cb832bd9cd"

    /**
     * Reads one character per line and verifies the exact artifact before it
     * reaches CTC decoding. The trailing newline is part of the checksum.
     */
    fun readValidated(file: File): List<String>? {
        if (!file.isFile || file.length() != UTF8_BYTES.toLong()) return null
        val bytes = file.readBytes()
        if (sha256(bytes) != SHA256) return null
        val text = bytes.toString(Charsets.UTF_8)
        val characters = text.split('\n').dropLastWhile { it.isEmpty() }
        return characters.takeIf { it.size == CHARACTER_COUNT && it.all(String::isNotEmpty) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
