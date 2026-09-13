package com.translive.app.engine.hardware

import android.content.Context
import android.os.Build
import android.util.Log
import com.translive.app.data.SettingsRepository
import java.io.File

/**
 * Qualcomm Hexagon Tensor Processor (HTP) microarchitecture generation.
 */
enum class HexagonArchitecture(
    val generationName: String,
    val htpVersionString: String,
    val peakTops: Int,
    val supportedQuantizations: List<String>
) {
    /** Snapdragon 8 Elite (SM8750 / "Sun"). 2nd-gen micro-tile HTP with 45 TOPS. */
    HTP_V79(
        generationName = "Hexagon HTP v79",
        htpVersionString = "v79",
        peakTops = 45,
        supportedQuantizations = listOf("INT4", "INT8", "FP16", "W4A16", "QAT")
    ),

    /** Snapdragon 8 Gen 3 (SM8650 / "Lanai"). 1st-gen micro-tile HTP with 45 TOPS. */
    HTP_V75(
        generationName = "Hexagon HTP v75",
        htpVersionString = "v75",
        peakTops = 45,
        supportedQuantizations = listOf("INT4", "INT8", "FP16", "W4A16")
    ),

    /** Snapdragon 8 Gen 2 (SM8550 / "Kalama"). HTP baseline with 33 TOPS. */
    HTP_V73(
        generationName = "Hexagon HTP v73",
        htpVersionString = "v73",
        peakTops = 33,
        supportedQuantizations = listOf("INT8", "FP16", "W8A16")
    ),

    /** Non-Qualcomm or pre-HTP legacy hardware. */
    NONE(
        generationName = "None / Unsupported NPU",
        htpVersionString = "none",
        peakTops = 0,
        supportedQuantizations = emptyList()
    );

    val isNpuCapable: Boolean get() = this != NONE
}

/**
 * Detailed specification of the Qualcomm NPU available on the host device.
 */
data class QualcommNpuProfile(
    val socName: String,
    val socModel: String,
    val architecture: HexagonArchitecture,
    val peakTops: Int = architecture.peakTops,
    val isNpuSupported: Boolean = architecture.isNpuCapable,
    val hasCdspDeviceNode: Boolean = false,
    val supportedComputeBackends: List<String> = buildList {
        add(SettingsRepository.BACKEND_CPU)
        add(SettingsRepository.BACKEND_GPU)
        if (architecture.isNpuCapable) {
            add(SettingsRepository.BACKEND_NPU)
        }
    }
)

/**
 * Dynamic hardware probe for Qualcomm Snapdragon NPU subsystem.
 */
object QualcommNpuRegistry {

    private const val TAG = "QualcommNpuRegistry"
    private const val CDSP_DEVICE_PATH = "/dev/fastrpc-cdsp"
    private const val ADSP_DEVICE_PATH = "/dev/adsprpc-smd"

    @Volatile
    private var cachedProfile: QualcommNpuProfile? = null

    fun getProfile(context: Context? = null): QualcommNpuProfile {
        cachedProfile?.let { return it }
        val profile = detectProfile()
        cachedProfile = profile
        return profile
    }

    private fun detectProfile(): QualcommNpuProfile {
        val socModel = getSocModel().uppercase().trim()
        val cdspExists = File(CDSP_DEVICE_PATH).exists() || File(ADSP_DEVICE_PATH).exists()

        val architecture = when {
            // Snapdragon 8 Elite (SM8750 / SM8750-AB / Sun)
            socModel.contains("SM8750") || socModel.contains("SUN") -> HexagonArchitecture.HTP_V79
            // Snapdragon 8 Gen 3 (SM8650 / SM8650-AB / Lanai)
            socModel.contains("SM8650") || socModel.contains("LANAI") -> HexagonArchitecture.HTP_V75
            // Snapdragon 8 Gen 2 (SM8550 / Kalama)
            socModel.contains("SM8550") || socModel.contains("KALAMA") -> HexagonArchitecture.HTP_V73
            else -> {
                // Secondary check via Adreno GPU profile
                val adrenoGen = AdrenoProfileRegistry.detectCurrentDeviceProfile().generation
                when (adrenoGen) {
                    AdrenoGeneration.ADRENO_8XX -> HexagonArchitecture.HTP_V79
                    AdrenoGeneration.ADRENO_7XX -> HexagonArchitecture.HTP_V75
                    else -> HexagonArchitecture.NONE
                }
            }
        }

        val socDisplayName = when (architecture) {
            HexagonArchitecture.HTP_V79 -> "Snapdragon 8 Elite"
            HexagonArchitecture.HTP_V75 -> "Snapdragon 8 Gen 3"
            HexagonArchitecture.HTP_V73 -> "Snapdragon 8 Gen 2"
            HexagonArchitecture.NONE -> if (socModel.isNotBlank()) "Qualcomm $socModel" else "Generic SoC"
        }

        val profile = QualcommNpuProfile(
            socName = socDisplayName,
            socModel = if (socModel.isNotBlank()) socModel else "UNKNOWN",
            architecture = architecture,
            hasCdspDeviceNode = cdspExists
        )

        Log.i(TAG, "Detected NPU: ${profile.socName} (${profile.architecture.generationName}, ${profile.peakTops} TOPS, CDSP=$cdspExists)")
        return profile
    }

    private fun getSocModel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) {
                return soc
            }
        }
        val hardware = Build.HARDWARE
        if (!hardware.isNullOrBlank() && hardware != Build.UNKNOWN) {
            return hardware
        }
        val board = Build.BOARD
        if (!board.isNullOrBlank() && board != Build.UNKNOWN) {
            return board
        }
        return ""
    }
}
