package com.voicecaption.vad

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards against accidental replacement of the bundled Silero VAD model (see assets/README.md). */
class SileroAssetTest {
    private val asset = File("src/main/assets/silero_vad.onnx")

    @Test
    fun modelMatchesPinnedSha256() {
        assertTrue("missing ${asset.absolutePath}", asset.isFile)
        val digest = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
        assertEquals(PINNED_SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun modelIsSmall() {
        assertTrue(asset.length() in 1 until 3_000_000)
    }

    private companion object {
        const val PINNED_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
    }
}
