/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Line-oriented control channel from the Android side to podroid-vsock-agent.
 * Single connection — the guest serializes commands so we never need request
 * IDs or response correlation. All sends are best-effort: failure logs and
 * drops the bytes; the caller (engine) decides whether to surface that in UI.
 *
 * Wire format (LF-terminated ASCII):
 *   RESIZE <rows> <cols>
 *   ADD    <vport> tcp <host> <gport>
 *   REMOVE <vport>
 *   PING                      → agent replies "PONG\n" (we ignore the reply)
 */
package com.excp.podroid.engine.avf

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class VsockControlChannel(
    private val vm: Any,
    private val scope: CoroutineScope,
) {
    companion object {
        const val CTL_PORT: Long = 9100L
        private const val TAG = "VsockControlChannel"
        private const val MAX_ATTEMPTS = 30
        // Cap the pre-connect queue. Without a cap, every RESIZE (a keyboard
        // slide fires ~25) appended forever once the connect gave up — an
        // unbounded leak that also replayed stale resizes if a connect
        // eventually succeeded. RESIZE is coalesced (only the latest matters),
        // so the cap mainly bounds ADD/REMOVE churn before first connect.
        private const val MAX_PENDING = 64
    }

    private var pfd: ParcelFileDescriptor? = null
    private var writer: PrintWriter? = null
    private var connectJob: Job? = null
    @Volatile private var closed = false
    /** Set once the retry loop exhausts; further sends are dropped, not queued. */
    private var gaveUp = false
    private var warnedUnavailable = false

    /**
     * Commands written before the agent connection is established. Drained
     * in-order once open() succeeds. Without this, the first ADDs fired from
     * AvfEngine.detector.onReady silently drop because open()'s retry
     * coroutine hasn't run yet (same-tick scheduling race).
     */
    private val pending = mutableListOf<String>()

    /**
     * Open the connection. The guest agent may not be ready immediately after
     * VM Running fires (OpenRC sequencing), so we retry on a short backoff.
     * Commands enqueued via sendResize/addForward/removeForward before connect
     * succeeds are buffered and flushed in order on first success.
     */
    fun open() {
        connectJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (!closed && attempt < MAX_ATTEMPTS) {
                val ok = runCatching {
                    val p = AvfReflect.connectVsock(vm, CTL_PORT)
                    val w = PrintWriter(ParcelFileDescriptor.AutoCloseOutputStream(p), /* autoFlush */ true)
                    synchronized(this@VsockControlChannel) {
                        if (closed) { runCatching { p.close() }; return@launch }
                        pfd = p
                        writer = w
                        // Drain anything queued before we got connected.
                        for (line in pending) {
                            runCatching { w.println(line) }
                                .onFailure { Log.w(TAG, "drain send failed: $line", it) }
                        }
                        pending.clear()
                    }
                    Log.d(TAG, "control channel connected after ${attempt + 1} attempts")
                    true
                }.getOrElse { false }
                if (ok) return@launch
                attempt += 1
                kotlinx.coroutines.delay(500)
            }
            // Connect never succeeded: stop queueing so later sends don't grow
            // `pending` without bound. Drop the buffer; it can never be drained.
            synchronized(this@VsockControlChannel) {
                gaveUp = true
                pending.clear()
            }
            Log.w(TAG, "control channel: gave up after $attempt attempts")
        }
    }

    @Synchronized private fun sendOrQueue(line: String) {
        val w = writer
        when {
            w != null -> runCatching { w.println(line) }
                .onFailure { Log.w(TAG, "send failed: $line", it) }
            closed || gaveUp -> {
                // Channel will never connect (gave up) or is shutting down:
                // drop with a single warning instead of growing `pending`.
                if (gaveUp && !warnedUnavailable) {
                    warnedUnavailable = true
                    Log.w(TAG, "control channel unavailable; dropping commands (first: $line)")
                }
            }
            // Coalesce RESIZE: only the most recent geometry matters, so replace
            // any queued RESIZE rather than appending a new one per slide event.
            line.startsWith("RESIZE ") -> {
                pending.removeAll { it.startsWith("RESIZE ") }
                pending.add(line)
            }
            pending.size >= MAX_PENDING -> Log.w(TAG, "pending queue full ($MAX_PENDING); dropping: $line")
            else -> pending.add(line)
        }
    }

    fun sendResize(rows: Int, cols: Int)             = sendOrQueue("RESIZE $rows $cols")
    fun addForward(vport: Int, host: String, gport: Int) = sendOrQueue("ADD $vport tcp $host $gport")
    fun removeForward(vport: Int)                    = sendOrQueue("REMOVE $vport")

    @Synchronized fun close() {
        if (closed) return
        closed = true
        runCatching { connectJob?.cancel() }
        runCatching { writer?.close() }
        runCatching { pfd?.close() }
        writer = null
        pfd = null
    }
}
