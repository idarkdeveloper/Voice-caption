package com.voicecaption.vad

/** Classifies one 512-sample (32 ms at 16 kHz) frame of audio in [-1, 1] as speech or not. */
fun interface VoiceActivityDetector {
    fun isSpeech(frame: FloatArray): Boolean
}
