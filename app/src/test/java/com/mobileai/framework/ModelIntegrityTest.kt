package com.mobileai.framework

import com.phoneai.localmodel.ModelFileManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit test that scans the workspace for downloaded model files (.gguf and .bin)
 * and verifies their binary header magic, GGUF versioning, and framework classification.
 */
class ModelIntegrityTest {

    @Test
    fun testAllGgufModelsInWorkspace() {
        val rootDir = File(".").canonicalFile
        val ggufFiles = rootDir.listFiles { file -> file.extension.equals("gguf", ignoreCase = true) }
            ?: emptyArray()

        if (ggufFiles.isEmpty()) {
            println("No GGUF models located in workspace root.")
            return
        }

        println("Found ${ggufFiles.size} GGUF models to verify:")

        for (file in ggufFiles) {
            println("  -> Verifying binary integrity: ${file.name} (${file.length() / (1024 * 1024)} MB)")
            assertTrue("File ${file.name} should exist and not be empty", file.exists() && file.length() > 1024)

            RandomAccessFile(file, "r").use { raf ->
                val headerBytes = ByteArray(16)
                raf.readFully(headerBytes)
                val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

                // 1. Check GGUF magic bytes (0x46554747 -> "GGUF")
                val magic = ByteArray(4)
                buffer.get(magic)
                val magicStr = String(magic, Charsets.US_ASCII)
                assertEquals("File ${file.name} must have GGUF magic header", "GGUF", magicStr)

                // 2. Check GGUF version (typically 2 or 3)
                val version = buffer.int
                assertTrue("GGUF version must be 2 or 3 (got $version in ${file.name})", version in 2..3)

                // 3. Check tensor and metadata counts
                val tensorCount = buffer.long
                assertTrue("Tensor count must be positive (got $tensorCount in ${file.name})", tensorCount > 0)
            }

            // 4. Test ModelFileManager classification
            val isProjector = ModelFileManager.isVisionProjector(file.name)
            if (file.name.startsWith("mmproj-", ignoreCase = true)) {
                assertTrue("File ${file.name} should be classified as Vision Projector", isProjector)
            } else {
                assertFalse("Base model ${file.name} should NOT be classified as Vision Projector", isProjector)
            }

            // 5. Verify functional role mapping to tabs
            val role = ModelFileManager.getModelRole(file.name)
            assertNotNull("Model ${file.name} must have a valid functional role", role)
            when {
                file.name.contains("whisper", ignoreCase = true) ->
                    assertEquals("Whisper model must be assigned to AUDIO", com.phoneai.localmodel.ModelRole.AUDIO, role)
                file.name.contains("minilm", ignoreCase = true) || file.name.contains("bge", ignoreCase = true) ->
                    assertEquals("Embedding model must be assigned to EMBEDDING", com.phoneai.localmodel.ModelRole.EMBEDDING, role)
                file.name.contains("vlm", ignoreCase = true) || file.name.contains("ocr", ignoreCase = true) || isProjector ->
                    assertEquals("Vision/OCR model must be assigned to VISUAL", com.phoneai.localmodel.ModelRole.VISUAL, role)
                else ->
                    assertEquals("LLM must be assigned to CHAT", com.phoneai.localmodel.ModelRole.CHAT, role)
            }
        }
    }

    @Test
    fun testTabRoleMappings() {
        assertEquals(com.phoneai.localmodel.ModelRole.CHAT, ModelFileManager.getModelRole("smollm2-360m-instruct-q4_k_m.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.CHAT, ModelFileManager.getModelRole("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.CHAT, ModelFileManager.getModelRole("gemma-2-2b-it-q4_k_m.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.VISUAL, ModelFileManager.getModelRole("smolvlm-500m-instruct-q4_k_m.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.VISUAL, ModelFileManager.getModelRole("mmproj-SmolVLM-500M-Instruct-f16.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.VISUAL, ModelFileManager.getModelRole("GLM-OCR-Q8_0.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.VISUAL, ModelFileManager.getModelRole("mmproj-GLM-OCR-Q8_0.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.AUDIO, ModelFileManager.getModelRole("whisper-base.en-Q8_0.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.EMBEDDING, ModelFileManager.getModelRole("all-minilm-l6-v2-q4_k_m.gguf"))
        assertEquals(com.phoneai.localmodel.ModelRole.EMBEDDING, ModelFileManager.getModelRole("bge-small-en-v1.5-q4_k_m.gguf"))
    }

    @Test
    fun testMediaPipeBinFiles() {
        val rootDir = File(".").canonicalFile
        val binFiles = rootDir.listFiles { file -> file.extension.equals("bin", ignoreCase = true) && file.name.contains("gemma", ignoreCase = true) }
            ?: emptyArray()

        for (file in binFiles) {
            println("  -> Verifying MediaPipe bundle: ${file.name} (${file.length() / (1024 * 1024)} MB)")
            assertTrue("MediaPipe bundle ${file.name} must exist and be > 100MB", file.exists() && file.length() > 100 * 1024 * 1024)
        }
    }
}
