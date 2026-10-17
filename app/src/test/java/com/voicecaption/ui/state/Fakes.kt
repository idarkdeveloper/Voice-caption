package com.voicecaption.ui.state

import com.voicecaption.core.ModelSize
import com.voicecaption.translate.Translator
import java.io.File

/** Fake installer: records calls, installs into an in-memory set, behavior is replaceable. */
class FakeInstaller(val installed: MutableSet<ModelSize> = mutableSetOf()) : ModelInstaller {
    var installCalls = 0
    var behavior: suspend ((Float) -> Unit) -> Result<File> = { progress ->
        progress(0.25f)
        progress(0.75f)
        progress(1f)
        Result.success(File("model"))
    }

    override fun isInstalled(size: ModelSize) = size in installed

    override suspend fun install(size: ModelSize, onProgress: (Float) -> Unit): Result<File> {
        installCalls++
        val result = behavior(onProgress)
        if (result.isSuccess) installed += size
        return result
    }
}

/** Fake translator: a pair is ready when same-language or listed in [ready]. */
class FakeTranslator : Translator {
    val ready = mutableSetOf<Pair<String, String>>()
    var prepareCalls = 0
    var behavior: suspend ((Boolean) -> Unit) -> Result<Unit> = { progress ->
        progress(true)
        progress(false)
        Result.success(Unit)
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String) = text

    override suspend fun isReady(sourceLang: String, targetLang: String) =
        sourceLang == targetLang || (sourceLang to targetLang) in ready

    override suspend fun prepare(sourceLang: String, targetLang: String, onProgress: (Boolean) -> Unit): Result<Unit> {
        prepareCalls++
        val result = behavior(onProgress)
        if (result.isSuccess) ready += sourceLang to targetLang
        return result
    }
}
