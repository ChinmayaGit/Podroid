/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Background CPU load for load-simulator demos. Android writes the desired
 * worker count to Downloads/Podroid/load-sim; this script syncs workers.
 */
package com.excp.podroid.util

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

@Singleton
class LoadSimulator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PODROID_DIR = "Podroid"
    }
    private val running = AtomicBoolean(false)
    private val cpuThreads = mutableListOf<Thread>()
    private var memoryChunks = mutableListOf<ByteArray>()
    private var diskJob: Job? = null
    private var diskScope: CoroutineScope? = null

    private var phoneEnabled = false
    private var vmEnabled = false
    private var intensity = LoadSimulatorIntensity.MEDIUM

    fun configure(phone: Boolean, vm: Boolean, level: LoadSimulatorIntensity) {
        phoneEnabled = phone
        vmEnabled = vm
        intensity = level
        apply()
    }

    fun stopAll() {
        phoneEnabled = false
        vmEnabled = false
        apply()
    }

    private fun apply() {
        stopPhoneLoad()
        writeGuestLevel(if (vmEnabled) intensity.workers else 0)
        if (phoneEnabled) startPhoneLoad(intensity)
    }

    private fun startPhoneLoad(level: LoadSimulatorIntensity) {
        running.set(true)
        repeat(level.workers.coerceAtMost(DeviceResourcePolicy.deviceCpuCount())) {
            val t = thread(name = "podroid-load-sim-$it", isDaemon = true) {
                var x = 0L
                while (running.get()) {
                    for (i in 0 until 500_000) x += (i * 31L) xor x
                }
            }
            cpuThreads += t
        }
        val mb = level.memoryMb
        if (mb > 0) {
            try {
                memoryChunks.add(ByteArray(mb * 1024 * 1024))
            } catch (_: OutOfMemoryError) {
                // Best-effort; low-memory devices may skip RAM churn.
            }
        }
        val scratch = File(context.filesDir, "load_sim_scratch.dat")
        diskScope = CoroutineScope(Dispatchers.IO)
        diskJob = diskScope?.launch {
            val buf = ByteArray(256 * 1024)
            while (isActive && running.get()) {
                try {
                    scratch.appendBytes(buf)
                    if (scratch.length() > 32L * 1024 * 1024) scratch.delete()
                } catch (_: Exception) {
                    break
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun stopPhoneLoad() {
        running.set(false)
        cpuThreads.forEach { it.interrupt() }
        cpuThreads.clear()
        memoryChunks.clear()
        diskJob?.cancel()
        diskJob = null
        diskScope = null
        File(context.filesDir, "load_sim_scratch.dat").delete()
    }

    fun guestSignalFile(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, PODROID_DIR), "load-sim")
    }

    fun isGuestSignalAvailable(): Boolean {
        val f = guestSignalFile()
        return f.parentFile?.exists() == true
    }

    fun guestTerminalCommand(level: LoadSimulatorIntensity): String =
        "podroid-stress start ${level.workers}"

    fun guestStopCommand(): String = "podroid-stress stop"

    private fun writeGuestLevel(workers: Int) {
        if (!isGuestSignalAvailable()) return
        try {
            val dir = guestSignalFile().parentFile ?: return
            dir.mkdirs()
            guestSignalFile().writeText(workers.coerceIn(0, 8).toString())
        } catch (_: Exception) {
            // Missing storage permission or AVF backend.
        }
    }
}

enum class LoadSimulatorIntensity(val workers: Int, val memoryMb: Int) {
    LOW(1, 32),
    MEDIUM(2, 64),
    HIGH(4, 128),
}
