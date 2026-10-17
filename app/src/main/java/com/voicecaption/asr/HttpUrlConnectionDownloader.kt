package com.voicecaption.asr

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Minimal [Downloader] over [HttpURLConnection] (follows redirects, no resume). Not unit-tested:
 * it needs a real network; the verification logic lives in [ModelManager].
 */
class HttpUrlConnectionDownloader : Downloader {
    override suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${conn.responseCode} for $url")
                }
                val length = conn.contentLengthLong
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (length > 0) onProgress(read.toFloat() / length)
                        }
                    }
                }
                onProgress(1f)
            } finally {
                conn.disconnect()
            }
        }
    }
}
