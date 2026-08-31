package com.translive.app.engine.hardware

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdrenoHardwareProfileTest {

    @Test
    fun `Snapdragon 8 Elite Adreno 830 matches OnePlus 13 reference behavior exactly`() {
        val profile = AdrenoProfileRegistry.resolveProfile("Adreno (TM) 830")
        assertEquals(AdrenoGeneration.ADRENO_8XX, profile.generation)
        assertEquals("Snapdragon 8 Elite", profile.socName)
        assertEquals(512, profile.nBatch)
        assertEquals(128, profile.nUbatch)
        assertEquals(4096L, profile.maxSingleAllocMb)
        assertEquals(4, profile.hostThreads)
        assertEquals("OpenCL 3.0", profile.openClTarget)
        assertTrue(profile.supportsFullOffloadDefault)

        // 1.8B HY-MT model (1.2 GB, 28 layers) on OnePlus 13 (8 GB available RAM)
        val layers = profile.calculateGpuLayers(
            modelTotalBytes = 1_200_000_000L,
            modelLayerCount = 28,
            availableRamBytes = 8_000_000_000L
        )
        assertEquals(-1, layers, "Adreno 830 must default to 100% full offload (-1)")
    }

    @Test
    fun `Snapdragon 8 Gen 3 Adreno 750 defaults to full offload with OpenCL 3_0`() {
        val profile = AdrenoProfileRegistry.resolveProfile("Adreno (TM) 750")
        assertEquals(AdrenoGeneration.ADRENO_7XX, profile.generation)
        assertEquals(256, profile.nBatch)
        assertEquals(64, profile.nUbatch)
        assertEquals(2048L, profile.maxSingleAllocMb)
        assertEquals(4, profile.hostThreads)
        assertTrue(profile.supportsFullOffloadDefault)

        val layers = profile.calculateGpuLayers(
            modelTotalBytes = 1_200_000_000L,
            modelLayerCount = 28,
            availableRamBytes = 6_000_000_000L
        )
        assertEquals(-1, layers)
    }

    @Test
    fun `Snapdragon 845 Adreno 630 on 4GB RAM dynamically limits layer offload to prevent OOM`() {
        val profile = AdrenoProfileRegistry.resolveProfile("Adreno (TM) 630")
        assertEquals(AdrenoGeneration.ADRENO_6XX, profile.generation)
        assertEquals(128, profile.nBatch)
        assertEquals(32, profile.nUbatch)
        assertEquals(512L, profile.maxSingleAllocMb)
        assertEquals(2, profile.hostThreads)
        assertEquals("OpenCL 2.0", profile.openClTarget)
        assertFalse(profile.supportsFullOffloadDefault)

        // 4GB RAM device with 1.5 GB available RAM
        // model = 1.2 GB (28 layers) -> ~42.85 MB/layer
        // usable = 1500 MB - 600 MB reserve = 900 MB; capped by maxGpuMemoryCap (1024 MB) -> 805 MB budget -> ~18 layers
        val layers = profile.calculateGpuLayers(
            modelTotalBytes = 1_200_000_000L,
            modelLayerCount = 28,
            availableRamBytes = 1_500_000_000L
        )
        assertTrue(layers in 1..27, "Adreno 630 on 4GB RAM must calculate partial offload, got $layers")
        assertEquals(20, layers)
    }

    @Test
    fun `Adreno 630 falls back to CPU when available RAM is severely exhausted`() {
        val profile = AdrenoProfileRegistry.resolveProfile("Adreno (TM) 630")
        // Available RAM is 400 MB, which is less than 600 MB safety reserve
        val layers = profile.calculateGpuLayers(
            modelTotalBytes = 1_200_000_000L,
            modelLayerCount = 28,
            availableRamBytes = 400_000_000L
        )
        assertEquals(0, layers, "Must fallback to CPU (0 layers) when available RAM is below safety reserve")
    }

    @Test
    fun `Resolution accurately maps all registered Snapdragon platforms`() {
        assertEquals("Snapdragon 8 Elite", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 830").socName)
        assertEquals("Snapdragon 8s Gen 4", AdrenoProfileRegistry.resolveProfile("Adreno 825").socName)
        assertEquals("Snapdragon 8 Gen 3", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 750").socName)
        assertEquals("Snapdragon 8 Gen 2", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 740").socName)
        assertEquals("Snapdragon 8 Gen 1 / 8+ Gen 1", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 730").socName)
        assertEquals("Snapdragon 7+ Gen 3", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 732").socName)
        assertEquals("Snapdragon 8s Gen 3", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 735").socName)
        assertEquals("Snapdragon 7+ Gen 2", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 725").socName)
        assertEquals("Snapdragon 888 / 888+", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 660").socName)
        assertEquals("Snapdragon 865 / 865+ / 870", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 650").socName)
        assertEquals("Snapdragon 855 / 855+ / 860", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 640").socName)
        assertEquals("Snapdragon 845", AdrenoProfileRegistry.resolveProfile("Adreno (TM) 630").socName)
    }

    @Test
    fun `Unknown and non-Adreno GPUs fallback gracefully`() {
        val nonAdrenoProfile = AdrenoProfileRegistry.resolveProfile("Mali-G715 Immortalis")
        assertEquals(AdrenoGeneration.UNKNOWN_GPU, nonAdrenoProfile.generation)
        assertEquals(128, nonAdrenoProfile.nBatch)
        assertEquals(32, nonAdrenoProfile.nUbatch)
        assertEquals(2, nonAdrenoProfile.hostThreads)
        assertFalse(nonAdrenoProfile.supportsFullOffloadDefault)

        val nullProfile = AdrenoProfileRegistry.resolveProfile(null)
        assertEquals(AdrenoGeneration.UNKNOWN_GPU, nullProfile.generation)
    }

    @Test
    fun `calculateGpuLayers handles edge case zero or negative parameters safely`() {
        val profile = AdrenoProfileRegistry.PROFILE_SNAPDRAGON_8_ELITE
        assertEquals(0, profile.calculateGpuLayers(0L, 28, 8_000_000_000L))
        assertEquals(0, profile.calculateGpuLayers(1_200_000_000L, 0, 8_000_000_000L))
        assertEquals(0, profile.calculateGpuLayers(1_200_000_000L, 28, 0L))
        assertEquals(0, profile.calculateGpuLayers(-100L, 28, 8_000_000_000L))
    }
}
