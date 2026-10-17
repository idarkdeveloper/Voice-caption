package com.voicecaption.asr

import com.voicecaption.core.ModelSize
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val good = "good-bytes".toByteArray()
    private val tokens = "tokens".toByteArray()

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val catalog = ModelCatalog(
        mapOf(
            ModelSize.BASE to listOf(
                ModelFile("enc.onnx", "http://x/enc", sha(good), good.size.toLong()),
                ModelFile("tokens.txt", "http://x/tok", sha(tokens), tokens.size.toLong()),
            ),
            ModelSize.SMALL to emptyList(),
        ),
    )

    private fun manager(downloader: Downloader) = ModelManager(tmp.root, downloader, catalog)

    private fun bytesFor(url: String) = if (url.endsWith("tok")) tokens else good

    private fun leftovers() = tmp.root.listFiles().orEmpty().map { it.name }

    @Test
    fun ioFailureReturnsFailureAndLeavesNoFiles() = runTest {
        val m = manager { _, dest, _ ->
            dest.writeBytes(byteArrayOf(1, 2, 3)) // partial data written before the failure
            throw IOException("network down")
        }
        val r = m.install(ModelSize.BASE) {}
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), leftovers())
        assertFalse(m.isInstalled(ModelSize.BASE))
    }

    @Test
    fun checksumMismatchFailsAndDeletesFile() = runTest {
        val m = manager { _, dest, _ -> dest.writeBytes("wrong".toByteArray()) }
        val r = m.install(ModelSize.BASE) {}
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("checksum", ignoreCase = true))
        assertEquals(emptyList<String>(), leftovers())
        assertFalse(m.isInstalled(ModelSize.BASE))
    }

    @Test
    fun laterFileFailureRemovesEarlierInstalledFiles() = runTest {
        val m = manager { url, dest, _ ->
            if (url.endsWith("tok")) throw IOException("boom") else dest.writeBytes(good)
        }
        assertTrue(m.install(ModelSize.BASE) {}.isFailure)
        assertEquals(emptyList<String>(), leftovers())
        assertFalse(m.isInstalled(ModelSize.BASE))
    }

    @Test
    fun correctBytesInstallAndReportFullProgress() = runTest {
        val progress = mutableListOf<Float>()
        val m = manager { url, dest, onProgress ->
            onProgress(0.5f)
            dest.writeBytes(bytesFor(url))
            onProgress(1f)
        }
        val r = m.install(ModelSize.BASE) { progress += it }
        assertTrue(r.isSuccess)
        assertTrue(r.getOrThrow().isDirectory)
        assertTrue(m.isInstalled(ModelSize.BASE))
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress) // monotonic
        assertEquals(listOf("base"), leftovers())
        assertEquals(setOf("enc.onnx", "tokens.txt"), File(tmp.root, "base").list()!!.toSet())
    }

    @Test
    fun cancellationIsRethrownAndCleansUp() = runTest {
        val m = manager { _, dest, _ ->
            dest.writeBytes(byteArrayOf(1))
            throw CancellationException("cancelled")
        }
        var cancelled = false
        try {
            m.install(ModelSize.BASE) {}
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun isInstalledFalseWhenFileTruncated() = runTest {
        val m = manager { url, dest, _ -> dest.writeBytes(bytesFor(url)) }
        m.install(ModelSize.BASE) {}
        File(tmp.root, "base/enc.onnx").writeBytes(byteArrayOf(0))
        assertFalse(m.isInstalled(ModelSize.BASE))
    }

    @Test
    fun deleteRemovesInstalledModelOnly() = runTest {
        val m = manager { url, dest, _ -> dest.writeBytes(bytesFor(url)) }
        m.install(ModelSize.BASE) {}
        assertTrue(m.isInstalled(ModelSize.BASE))
        assertTrue(m.delete(ModelSize.BASE))
        assertFalse(m.isInstalled(ModelSize.BASE))
        assertFalse(File(tmp.root, "base").exists())
        assertFalse(m.delete(ModelSize.BASE))
    }
}

class ModelCatalogTest {
    @Test
    fun realCatalogHasThreeFilesPerSizeWithPinnedUrlsAndHashes() {
        for (size in ModelSize.values()) {
            val files = ModelCatalog.DEFAULT.files(size)
            assertEquals(3, files.size)
            for (f in files) {
                assertTrue(f.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(f.sizeBytes > 0)
                assertTrue(f.url.startsWith("https://huggingface.co/csukuangfj/"))
                assertFalse(f.url.contains("/main/"))
            }
        }
        assertTrue(ModelCatalog.DEFAULT.totalBytes(ModelSize.SMALL) > ModelCatalog.DEFAULT.totalBytes(ModelSize.BASE))
    }
}
