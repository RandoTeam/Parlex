package com.translive.app.engine.hardware

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Registry and hardware detection subsystem for Snapdragon Adreno GPUs.
 */
object AdrenoProfileRegistry {

    private const val KGSL_GPU_MODEL_PATH = "/sys/class/kgsl/kgsl-3d0/gpu_model"

    // ─── Reference Profile (Snapdragon 8 Elite / OnePlus 13 — FROZEN REFERENCE) ───
    val PROFILE_SNAPDRAGON_8_ELITE = AdrenoDeviceProfile(
        socName = "Snapdragon 8 Elite",
        gpuIdentifier = "Adreno (TM) 830",
        gpuModelPattern = Regex("Adreno.*?830", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_8XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 512,
        nUbatch = 128,
        maxSingleAllocMb = 4096L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    // ─── Snapdragon 8-Series (Adreno 8xx / 7xx / 6xx) ──────────────────────────
    val PROFILE_SNAPDRAGON_8S_GEN_4 = AdrenoDeviceProfile(
        socName = "Snapdragon 8s Gen 4",
        gpuIdentifier = "Adreno (TM) 825",
        gpuModelPattern = Regex("Adreno.*?825", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_8XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 512,
        nUbatch = 128,
        maxSingleAllocMb = 4096L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val PROFILE_SNAPDRAGON_8_GEN_3 = AdrenoDeviceProfile(
        socName = "Snapdragon 8 Gen 3",
        gpuIdentifier = "Adreno (TM) 750",
        gpuModelPattern = Regex("Adreno.*?750", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_7XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 256,
        nUbatch = 64,
        maxSingleAllocMb = 2048L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val PROFILE_SNAPDRAGON_8_GEN_2 = AdrenoDeviceProfile(
        socName = "Snapdragon 8 Gen 2",
        gpuIdentifier = "Adreno (TM) 740",
        gpuModelPattern = Regex("Adreno.*?740", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_7XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 256,
        nUbatch = 64,
        maxSingleAllocMb = 2048L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val PROFILE_SNAPDRAGON_8_GEN_1 = AdrenoDeviceProfile(
        socName = "Snapdragon 8 Gen 1 / 8+ Gen 1",
        gpuIdentifier = "Adreno (TM) 730",
        gpuModelPattern = Regex("Adreno.*?730", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_7XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 256,
        nUbatch = 64,
        maxSingleAllocMb = 2048L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val PROFILE_SNAPDRAGON_8S_GEN_3 = AdrenoDeviceProfile(
        socName = "Snapdragon 8s Gen 3",
        gpuIdentifier = "Adreno (TM) 735",
        gpuModelPattern = Regex("Adreno.*?735", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_7XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 256,
        nUbatch = 64,
        maxSingleAllocMb = 2048L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val PROFILE_SNAPDRAGON_7_PLUS_GEN_3 = AdrenoDeviceProfile(
        socName = "Snapdragon 7+ Gen 3",
        gpuIdentifier = "Adreno (TM) 732",
        gpuModelPattern = Regex("Adreno.*?732", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_7XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 256,
        nUbatch = 64,
        maxSingleAllocMb = 2048L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val PROFILE_SNAPDRAGON_7_PLUS_GEN_2 = AdrenoDeviceProfile(
        socName = "Snapdragon 7+ Gen 2",
        gpuIdentifier = "Adreno (TM) 725",
        gpuModelPattern = Regex("Adreno.*?725", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_7XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 256,
        nUbatch = 64,
        maxSingleAllocMb = 2048L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val PROFILE_SNAPDRAGON_888 = AdrenoDeviceProfile(
        socName = "Snapdragon 888 / 888+",
        gpuIdentifier = "Adreno (TM) 660",
        gpuModelPattern = Regex("Adreno.*?660", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_6XX,
        openClTarget = "OpenCL 2.0",
        nBatch = 128,
        nUbatch = 32,
        maxSingleAllocMb = 1024L,
        hostThreads = 2,
        supportsFullOffloadDefault = false
    )

    val PROFILE_SNAPDRAGON_865 = AdrenoDeviceProfile(
        socName = "Snapdragon 865 / 865+ / 870",
        gpuIdentifier = "Adreno (TM) 650",
        gpuModelPattern = Regex("Adreno.*?650", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_6XX,
        openClTarget = "OpenCL 2.0",
        nBatch = 128,
        nUbatch = 32,
        maxSingleAllocMb = 512L,
        hostThreads = 2,
        supportsFullOffloadDefault = false
    )

    val PROFILE_SNAPDRAGON_855 = AdrenoDeviceProfile(
        socName = "Snapdragon 855 / 855+ / 860",
        gpuIdentifier = "Adreno (TM) 640",
        gpuModelPattern = Regex("Adreno.*?640", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_6XX,
        openClTarget = "OpenCL 2.0",
        nBatch = 128,
        nUbatch = 32,
        maxSingleAllocMb = 512L,
        hostThreads = 2,
        supportsFullOffloadDefault = false
    )

    val PROFILE_SNAPDRAGON_845 = AdrenoDeviceProfile(
        socName = "Snapdragon 845",
        gpuIdentifier = "Adreno (TM) 630",
        gpuModelPattern = Regex("Adreno.*?630", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_6XX,
        openClTarget = "OpenCL 2.0",
        nBatch = 128,
        nUbatch = 32,
        maxSingleAllocMb = 512L,
        hostThreads = 2,
        supportsFullOffloadDefault = false
    )

    // ─── Generic Fallback Profiles ─────────────────────────────────────────────
    val GENERIC_ADRENO_8XX = AdrenoDeviceProfile(
        socName = "Generic Snapdragon 8-Series (Adreno 8xx)",
        gpuIdentifier = "Adreno (TM) 8xx",
        gpuModelPattern = Regex("Adreno.*?8\\d{2}", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_8XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 512,
        nUbatch = 128,
        maxSingleAllocMb = 4096L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val GENERIC_ADRENO_7XX = AdrenoDeviceProfile(
        socName = "Generic Snapdragon (Adreno 7xx)",
        gpuIdentifier = "Adreno (TM) 7xx",
        gpuModelPattern = Regex("Adreno.*?7\\d{2}", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_7XX,
        openClTarget = "OpenCL 3.0",
        nBatch = 256,
        nUbatch = 64,
        maxSingleAllocMb = 2048L,
        hostThreads = 4,
        supportsFullOffloadDefault = true
    )

    val GENERIC_ADRENO_6XX = AdrenoDeviceProfile(
        socName = "Generic Snapdragon (Adreno 6xx)",
        gpuIdentifier = "Adreno (TM) 6xx",
        gpuModelPattern = Regex("Adreno.*?6\\d{2}", RegexOption.IGNORE_CASE),
        generation = AdrenoGeneration.ADRENO_6XX,
        openClTarget = "OpenCL 2.0",
        nBatch = 128,
        nUbatch = 32,
        maxSingleAllocMb = 512L,
        hostThreads = 2,
        supportsFullOffloadDefault = false
    )

    val FALLBACK_UNKNOWN_GPU = AdrenoDeviceProfile(
        socName = "Generic Host / Unknown GPU",
        gpuIdentifier = "Unknown GPU",
        gpuModelPattern = Regex(".*"),
        generation = AdrenoGeneration.UNKNOWN_GPU,
        openClTarget = "OpenCL 2.0",
        nBatch = 128,
        nUbatch = 32,
        maxSingleAllocMb = 512L,
        hostThreads = 2,
        supportsFullOffloadDefault = false
    )

    /** Ordered table of all specific profiles before generic fallbacks. */
    val ALL_KNOWN_PROFILES = listOf(
        PROFILE_SNAPDRAGON_8_ELITE,
        PROFILE_SNAPDRAGON_8S_GEN_4,
        PROFILE_SNAPDRAGON_8_GEN_3,
        PROFILE_SNAPDRAGON_8_GEN_2,
        PROFILE_SNAPDRAGON_8_GEN_1,
        PROFILE_SNAPDRAGON_8S_GEN_3,
        PROFILE_SNAPDRAGON_7_PLUS_GEN_3,
        PROFILE_SNAPDRAGON_7_PLUS_GEN_2,
        PROFILE_SNAPDRAGON_888,
        PROFILE_SNAPDRAGON_865,
        PROFILE_SNAPDRAGON_855,
        PROFILE_SNAPDRAGON_845
    )

    /**
     * Reads the GPU model identifier from the Qualcomm KGSL kernel sysfs node.
     */
    fun readKgslGpuModel(): String? {
        return try {
            val file = File(KGSL_GPU_MODEL_PATH)
            if (file.exists() && file.canRead()) {
                file.readText().lines().firstOrNull()?.trim()?.ifEmpty { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the optimal [AdrenoDeviceProfile] for the device using available identifiers.
     */
    fun resolveProfile(
        gpuModel: String?,
        socName: String? = null
    ): AdrenoDeviceProfile {
        val query = gpuModel?.trim() ?: readKgslGpuModel() ?: ""

        // 1. Direct regex match against registered specific profiles
        if (query.isNotBlank()) {
            val matched = ALL_KNOWN_PROFILES.firstOrNull { it.gpuModelPattern.containsMatchIn(query) }
            if (matched != null) return matched
        }

        // 2. Match by SoC name if provided or detectable via Build props
        if (!socName.isNullOrBlank()) {
            val matchedSoc = ALL_KNOWN_PROFILES.firstOrNull {
                it.socName.contains(socName, ignoreCase = true) ||
                socName.contains(it.socName, ignoreCase = true)
            }
            if (matchedSoc != null) return matchedSoc
        }

        // 3. Fallback to generation-level classification
        return when (AdrenoGeneration.fromGpuModel(query)) {
            AdrenoGeneration.ADRENO_8XX -> GENERIC_ADRENO_8XX
            AdrenoGeneration.ADRENO_7XX -> GENERIC_ADRENO_7XX
            AdrenoGeneration.ADRENO_6XX -> GENERIC_ADRENO_6XX
            AdrenoGeneration.UNKNOWN_GPU -> FALLBACK_UNKNOWN_GPU
        }
    }

    /**
     * Detects and resolves the active device profile for the current running Android environment.
     */
    fun detectCurrentDeviceProfile(context: Context? = null): AdrenoDeviceProfile {
        val kgslModel = readKgslGpuModel()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
        return resolveProfile(gpuModel = kgslModel, socName = soc)
    }
}
