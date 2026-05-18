/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Reads raw signed-16-bit-LE stereo PCM from PulseAudio's
 * module-simple-protocol-tcp source and pumps it into AudioTrack.
 * Auto-reconnects on socket drop with 1 s backoff.
 */
package com.excp.podroid.x11

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

class AudioStreamer(private val host: String = "127.0.0.1") {

    companion object {
        private const val TAG = "AudioStreamer"
        private const val BUF_BYTES = 4096
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        val track = buildTrack()
        track.play()
        try {
            while (coroutineContext.isActive) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, X11Constants.AUDIO_PORT), 2000)
                        val inp = s.getInputStream()
                        val buf = ByteArray(BUF_BYTES)
                        while (coroutineContext.isActive) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            track.write(buf, 0, n)
                        }
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "audio reconnect: ${e.message}")
                    delay(1000)
                }
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            X11Constants.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Target ~100 ms — 4410 frames @ 44.1 kHz * 2 channels * 2 bytes/sample
        // ≈ 17.6 KB. Floor at minBuf so AudioTrack never refuses to build.
        // Previously we used minBuf * 4 which gave ~170 ms one-way latency —
        // audible lag on click feedback in X11.
        val targetBuf = (X11Constants.AUDIO_SAMPLE_RATE / 10 *
            X11Constants.AUDIO_CHANNELS * 2).coerceAtLeast(minBuf)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(X11Constants.AUDIO_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(targetBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(1.0f) }
    }
}
