/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.x11.AudioStreamer
import com.excp.podroid.x11.ResolutionMode
import com.excp.podroid.x11.ResolutionPolicy
import com.excp.podroid.x11.ResolutionPreset
import com.excp.podroid.x11.RotationLock
import com.excp.podroid.x11.TouchMode
import com.excp.podroid.x11.VncClient
import com.excp.podroid.x11.VncRect
import com.excp.podroid.x11.VncSize
import com.excp.podroid.x11.X11Constants
import com.excp.podroid.x11.X11Settings
import com.excp.podroid.x11.ZrleDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

sealed interface X11ConnectionState {
    object Disconnected : X11ConnectionState
    object Connecting : X11ConnectionState
    object Connected : X11ConnectionState
    data class Failed(val message: String) : X11ConnectionState
}

@HiltViewModel
class X11ViewModel @Inject constructor(
    val engine: VmEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state

    private val _connection = MutableStateFlow<X11ConnectionState>(X11ConnectionState.Disconnected)
    val connection: StateFlow<X11ConnectionState> = _connection.asStateFlow()

    val x11Settings = settings.x11Settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), X11Settings())

    private val _fbSize = MutableStateFlow(VncSize(X11Constants.FB_WIDTH, X11Constants.FB_HEIGHT))
    val fbSize: StateFlow<VncSize> = _fbSize.asStateFlow()

    val cursor = MutableStateFlow(android.graphics.Point(X11Constants.FB_WIDTH / 2, X11Constants.FB_HEIGHT / 2))

    @Volatile private var fbW = X11Constants.FB_WIDTH
    @Volatile private var fbH = X11Constants.FB_HEIGHT
    @Volatile var framebuffer: IntArray = IntArray(fbW * fbH); private set
    @Volatile private var scratch: IntArray = IntArray(fbW * fbH)
    private val zrle = ZrleDecoder()
    @Volatile private var screenId = 0
    @Volatile private var desiredW = 0; @Volatile private var desiredH = 0
    @Volatile var lastDamage: List<VncRect> = emptyList(); private set

    private val _frameCounter = MutableStateFlow(0)
    val frameCounter: StateFlow<Int> = _frameCounter.asStateFlow()

    private val audio = AudioStreamer()
    private var sessionJob: Job? = null
    private var rfbOut: OutputStream? = null

    fun connect() {
        if (sessionJob?.isActive == true) return
        _connection.value = X11ConnectionState.Connecting
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", X11Constants.VNC_PORT), 2000)
                    val inp = sock.getInputStream()
                    val out = sock.getOutputStream()
                    rfbOut = out

                    VncClient.handshake(inp, out)
                    VncClient.negotiatePixelFormat(out)
                    if (desiredW > 0) VncClient.requestDesktopSize(out, screenId, desiredW, desiredH)
                    VncClient.requestFramebufferUpdate(out, w = fbW, h = fbH, incremental = false)
                    _connection.value = X11ConnectionState.Connected
                    audio.start(viewModelScope)
                    while (isActive) {
                        val upd = VncClient.readFramebufferUpdate(inp, scratch, fbW, zrle)
                        upd.newSize?.let { ns ->
                            if (ns.w != fbW || ns.h != fbH) {
                                fbW = ns.w; fbH = ns.h
                                val fresh = IntArray(fbW * fbH)
                                synchronized(framebuffer) { framebuffer = fresh }
                                scratch = IntArray(fbW * fbH)
                                _fbSize.value = ns
                                cursor.value = android.graphics.Point(fbW / 2, fbH / 2)
                                VncClient.requestFramebufferUpdate(out, w = fbW, h = fbH, incremental = false)
                                return@let
                            }
                        }
                        synchronized(framebuffer) {
                            System.arraycopy(scratch, 0, framebuffer, 0, framebuffer.size)
                            lastDamage = upd.damage
                        }
                        _frameCounter.value = _frameCounter.value + 1
                        VncClient.requestFramebufferUpdate(out, w = fbW, h = fbH, incremental = true)
                    }
                }
            } catch (e: Exception) {
                _connection.value = X11ConnectionState.Failed(e.message ?: "unknown")
            } finally {
                rfbOut = null
                audio.stop()
                if (_connection.value !is X11ConnectionState.Failed) {
                    _connection.value = X11ConnectionState.Disconnected
                }
            }
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
    }

    @Volatile private var lastViewportW = 0
    @Volatile private var lastViewportH = 0

    fun requestResolution(viewportW: Int, viewportH: Int) {
        lastViewportW = viewportW
        lastViewportH = viewportH
        val s = x11Settings.value
        val t = ResolutionPolicy.target(s, viewportW, viewportH)
        desiredW = t.w; desiredH = t.h
        val out = rfbOut ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { VncClient.requestDesktopSize(out, screenId, t.w, t.h) } }
    }

    fun setResolutionMode(m: ResolutionMode) {
        viewModelScope.launch { settings.setX11ResolutionMode(m.name) }
        val explicit = x11Settings.value.copy(resolutionMode = m)
        reapplyResolution(explicit)
    }

    fun setPreset(p: ResolutionPreset) {
        viewModelScope.launch { settings.setX11Preset(p.name) }
        val explicit = x11Settings.value.copy(resolutionMode = ResolutionMode.PRESET, preset = p)
        reapplyResolution(explicit)
    }

    fun setCustom(w: Int, h: Int) {
        viewModelScope.launch { settings.setX11Custom(w, h) }
        val explicit = x11Settings.value.copy(resolutionMode = ResolutionMode.CUSTOM, customW = w, customH = h)
        reapplyResolution(explicit)
    }

    fun setRotation(r: RotationLock) {
        viewModelScope.launch { settings.setX11Rotation(r.name) }
    }

    fun setTouchMode(m: TouchMode) {
        viewModelScope.launch { settings.setX11TouchMode(m.name) }
    }

    fun setTrackpadSensitivity(v: Float) {
        viewModelScope.launch { settings.setX11TrackpadSensitivity(v) }
    }

    fun setTrackpadAccel(v: Boolean) {
        viewModelScope.launch { settings.setX11TrackpadAccel(v) }
    }

    fun setShowExtraKeys(v: Boolean) {
        viewModelScope.launch { settings.setX11ShowExtraKeys(v) }
    }

    fun setFullscreenDefault(v: Boolean) {
        viewModelScope.launch { settings.setX11Fullscreen(v) }
    }

    fun setDpi(v: Int) {
        viewModelScope.launch { settings.setX11Dpi(v) }
    }

    private fun reapplyResolution(explicit: X11Settings) {
        if (lastViewportW <= 0) return
        val t = ResolutionPolicy.target(explicit, lastViewportW, lastViewportH)
        desiredW = t.w; desiredH = t.h
        val out = rfbOut ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { VncClient.requestDesktopSize(out, screenId, t.w, t.h) } }
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val out = rfbOut ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { VncClient.sendPointer(out, x, y, buttonMask) }
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        val out = rfbOut ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { VncClient.sendKey(out, keysym, down) }
        }
    }

    fun moveTo(x: Int, y: Int) { cursor.value = android.graphics.Point(x.coerceIn(0, fbW - 1), y.coerceIn(0, fbH - 1)); sendPointer(cursor.value.x, cursor.value.y, heldButtons) }
    @Volatile private var heldButtons = 0
    fun press(button: Int) { heldButtons = heldButtons or button; sendPointer(cursor.value.x, cursor.value.y, heldButtons) }
    fun release(button: Int) { heldButtons = heldButtons and button.inv(); sendPointer(cursor.value.x, cursor.value.y, heldButtons) }
    fun click(button: Int) { press(button); release(button) }
    /** Physical-mouse update: absolute position + the full button mask in one event. */
    fun mouseUpdate(x: Int, y: Int, mask: Int) {
        val nx = x.coerceIn(0, fbW - 1); val ny = y.coerceIn(0, fbH - 1)
        cursor.value = android.graphics.Point(nx, ny)
        heldButtons = mask
        sendPointer(nx, ny, mask)
    }
    fun scroll(up: Boolean, ticks: Int = 1) { val b = if (up) VncClient.BTN_WHEEL_UP else VncClient.BTN_WHEEL_DOWN; repeat(ticks) { sendPointer(cursor.value.x, cursor.value.y, heldButtons or b); sendPointer(cursor.value.x, cursor.value.y, heldButtons) } }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
