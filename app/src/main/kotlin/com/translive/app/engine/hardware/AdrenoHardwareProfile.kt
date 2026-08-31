package com.translive.app.engine.hardware

import kotlin.math.max
import kotlin.math.min

/**
 * Snapdragon Adreno GPU microarchitecture generations.
 */
enum class AdrenoGeneration(
    val generationName: String,
    val openClTarget: String,
    val defaultBatch: Int,
    val defaultUbatch: Int,
    val defaultMaxSingleAllocMb: Long,
    val defaultHostThreads: Int
) {
    /** Adreno 600-series (Adreno 630–660, Snapdragon 845–888). OpenCL 2.0 baseline. */
    ADRENO_6XX(
        generationName = "Adreno 6xx",
        openClTarget = "OpenCL 2.0",
        defaultBatch = 128,
        defaultUbatch = 32,
        defaultMaxSingleAllocMb = 512L,
        defaultHostThreads = 2
    ),

    /** Adreno 700-series (Adreno 725–750, Snapdragon 8 Gen 1–8 Gen 3, 7+ Gen 2/3). OpenCL 3.0 baseline. */
    ADRENO_7XX(
        generationName = "Adreno 7xx",
        openClTarget = "OpenCL 3.0",
        defaultBatch = 256,
        defaultUbatch = 64,
        defaultMaxSingleAllocMb = 2048L,
        defaultHostThreads = 4
    ),

    /** Adreno 800-series (Adreno 825–830, Snapdragon 8 Elite / SM8750+). Sliced architecture baseline. */
    ADRENO_8XX(
        generationName = "Adreno 8xx",
        openClTarget = "OpenCL 3.0",
        defaultBatch = 512,
        defaultUbatch = 128,
        defaultMaxSingleAllocMb = 4096L,
        defaultHostThreads = 4
    ),

    /** Fallback profile for non-Adreno or unrecognized GPU hardware. */
    UNKNOWN_GPU(
        generationName = "Generic / Unknown GPU",
        openClTarget = "OpenCL 2.0",
        defaultBatch = 128,
        defaultUbatch = 32,
        defaultMaxSingleAllocMb = 512L,
        defaultHostThreads = 2
    );

    val isAdreno: Boolean get() = this != UNKNOWN_GPU

    companion object {
        private val ADRENO_GEN_REGEX = Regex("Adreno.*?(\\d{3})", RegexOption.IGNORE_CASE)

        /**
         * Classifies an Adreno generation from a raw GPU identifier or model string.
         */
        fun fromGpuModel(gpuModel: String?): AdrenoGeneration {
            if (gpuModel.isNullOrBlank()) return UNKNOWN_GPU
            val match = ADRENO_GEN_REGEX.find(gpuModel) ?: return UNKNOWN_GPU
            val modelNumber = match.groupValues[1].toIntOrNull() ?: return UNKNOWN_GPU
            return when (modelNumber) {
                in 600..699 -> ADRENO_6XX
                in 700..799 -> ADRENO_7XX
                in 800..899 -> ADRENO_8XX
                else -> UNKNOWN_GPU
            }
        }
    }
}

/**
 * Execution profile tailored to a specific Snapdragon SoC / Adreno GPU model.
 */
data class AdrenoDeviceProfile(
    val socName: String,
    val gpuIdentifier: String,
    val gpuModelPattern: Regex,
    val generation: AdrenoGeneration,
    val openClTarget: String = generation.openClTarget,
    val nBatch: Int = generation.defaultBatch,
    val nUbatch: Int = generation.defaultUbatch,
    val maxSingleAllocMb: Long = generation.defaultMaxSingleAllocMb,
    val hostThreads: Int = generation.defaultHostThreads,
    val supportsFullOffloadDefault: Boolean = generation != AdrenoGeneration.ADRENO_6XX && generation != AdrenoGeneration.UNKNOWN_GPU,
    val maxGpuMemoryCapBytes: Long = when (generation) {
        AdrenoGeneration.ADRENO_6XX -> 1024L * 1024L * 1024L // 1.0 GiB max GPU pool on A6x
        AdrenoGeneration.ADRENO_7XX -> 4096L * 1024L * 1024L // 4.0 GiB max GPU pool on A7x
        AdrenoGeneration.ADRENO_8XX -> Long.MAX_VALUE         // Uncapped unified pool on A8x
        AdrenoGeneration.UNKNOWN_GPU -> 512L * 1024L * 1024L
    },
    val systemRamSafetyReserveBytes: Long = 600L * 1024L * 1024L // 600 MiB reserve for OS/UI/TTS/OCR
) {

    /**
     * Calculates the safe number of transformer layers to offload to the OpenCL GPU.
     *
     * In llama.cpp:
     * - `-1`: Full offload (all layers on GPU).
     * - `0`: CPU only (0 layers on GPU).
     * - `k > 0`: Partial offload (k layers on GPU, remainder on CPU).
     *
     * Rules:
     * 1. Adreno 830 (Snapdragon 8 Elite / OnePlus 13 reference) & Adreno 750 (8 Gen 3) default to 100% full offload (`-1`)
     *    as long as available system RAM covers the model + minimum safety reserve.
     * 2. Adreno 630 on 4GB / 6GB RAM calculates a proportional partial offload to prevent OOM
     *    and driver allocation failures (`maxSingleAllocMb = 512MB`).
     * 3. When available RAM is severely exhausted (< safety reserve), returns `0` (CPU fallback).
     */
    fun calculateGpuLayers(
        modelTotalBytes: Long,
        modelLayerCount: Int,
        availableRamBytes: Long
    ): Int {
        if (modelTotalBytes <= 0L || modelLayerCount <= 0 || availableRamBytes <= 0L) {
            return 0
        }

        // Available memory after keeping OS & app UI safety headroom
        val usableSystemMemory = availableRamBytes - systemRamSafetyReserveBytes
        if (usableSystemMemory <= 0L) {
            return 0
        }

        val bytesPerLayer = modelTotalBytes.toDouble() / modelLayerCount

        // 1. Flagship default path (Adreno 830 / 750 / 8xx / 7xx with sufficient headroom)
        if (supportsFullOffloadDefault) {
            val requiredMemoryForFullOffload = modelTotalBytes + systemRamSafetyReserveBytes
            if (availableRamBytes >= requiredMemoryForFullOffload) {
                return -1 // 100% Full Offload
            }
        }

        // 2. Budget / Memory-constrained calculation (Adreno 630 on 4GB/6GB RAM or low-memory A7x)
        val safeGpuBudget = min(usableSystemMemory, maxGpuMemoryCapBytes)
        val maxCalculatedLayers = (safeGpuBudget / bytesPerLayer).toInt()
        val safeLayers = maxCalculatedLayers.coerceIn(0, modelLayerCount)

        return when {
            safeLayers >= modelLayerCount -> {
                if (generation == AdrenoGeneration.ADRENO_6XX && (modelTotalBytes / modelLayerCount) > (maxSingleAllocMb * 1024 * 1024)) {
                    0
                } else {
                    -1 // Full offload if all layers safely fit
                }
            }
            safeLayers > 0 -> safeLayers
            else -> 0 // CPU fallback
        }
    }
}
