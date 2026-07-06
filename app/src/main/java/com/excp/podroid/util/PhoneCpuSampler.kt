/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Samples overall phone CPU use from /proc/stat for the Status screen.
 */
package com.excp.podroid.util

import android.os.SystemClock
import java.io.File

class PhoneCpuSampler {
    private var lastTotal: Long? = null
    private var lastIdle: Long? = null
    private var lastTimeMs: Long? = null

    fun reset() {
        lastTotal = null
        lastIdle = null
        lastTimeMs = null
    }

    /** Overall CPU busy percent 0–100 across all cores. */
    fun samplePercent(): Float? {
        val (total, idle) = readCpuTotals() ?: return null
        val now = SystemClock.elapsedRealtime()
        val prevTotal = lastTotal
        val prevIdle = lastIdle
        val prevTime = lastTimeMs
        lastTotal = total
        lastIdle = idle
        lastTimeMs = now
        if (prevTotal == null || prevIdle == null || prevTime == null) return null

        val deltaTotal = (total - prevTotal).coerceAtLeast(1)
        val deltaIdle = (idle - prevIdle).coerceAtLeast(0)
        val pct = (1.0 - deltaIdle.toDouble() / deltaTotal.toDouble()) * 100.0
        return pct.coerceIn(0.0, 100.0).toFloat()
    }

    companion object {
        const val MAX_SAMPLES = 120

        fun readCpuTotals(): Pair<Long, Long>? {
            return try {
                val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return null
                val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (parts.size < 4) return null
                val idle = parts[3] + parts.getOrElse(4) { 0L }
                val total = parts.sum()
                total to idle
            } catch (_: Exception) {
                null
            }
        }
    }
}
