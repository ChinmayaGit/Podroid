/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.ui.components.PodroidTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.excp.podroid.x11.TouchMode
import com.excp.podroid.x11.VncClient

// X11 keysyms used outside the label table.
private const val XK_BackSpace = 0xFF08
private const val XK_Tab       = 0xFF09
private const val XK_Return    = 0xFF0D
private const val XK_Escape    = 0xFF1B
private const val XK_Left      = 0xFF51
private const val XK_Up        = 0xFF52
private const val XK_Right     = 0xFF53
private const val XK_Down      = 0xFF54
private const val XK_Shift_L   = 0xFFE1
private const val XK_Control_L = 0xFFE3
private const val XK_Alt_L     = 0xFFE9

/**
 * Maps the human-readable label used by [X11ExtraKeysRow] (matching the
 * terminal's ExtraKeysRow vocabulary) to an X11 keysym. Returns null for
 * pure modifier labels (CTRL/ALT) — those are handled as toggles.
 */
private fun labelToKeysym(label: String): Int? = when (label) {
    "ESC"     -> XK_Escape
    "TAB"     -> XK_Tab
    "LEFT"    -> XK_Left
    "RIGHT"   -> XK_Right
    "UP"      -> XK_Up
    "DOWN"    -> XK_Down
    "HOME"    -> 0xFF50
    "END"     -> 0xFF57
    "PGUP"    -> 0xFF55
    "PGDN"    -> 0xFF56
    "F1"      -> 0xFFBE
    "F2"      -> 0xFFBF
    "F3"      -> 0xFFC0
    "F4"      -> 0xFFC1
    "F5"      -> 0xFFC2
    "F6"      -> 0xFFC3
    "F7"      -> 0xFFC4
    "F8"      -> 0xFFC5
    "F9"      -> 0xFFC6
    "F10"     -> 0xFFC7
    "F11"     -> 0xFFC8
    "F12"     -> 0xFFC9
    "-"       -> 0x2D
    "/"       -> 0x2F
    "|"       -> 0x7C
    else      -> null   // CTRL / ALT handled by toggles
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalComposeUiApi::class,
)
@Composable
fun X11Screen(
    onNavigateBack: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    viewModel: X11ViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val frameCount by viewModel.frameCounter.collectAsStateWithLifecycle()
    val fb by viewModel.fbSize.collectAsStateWithLifecycle()
    val bitmap = remember(fb) { Bitmap.createBitmap(fb.w, fb.h, Bitmap.Config.ARGB_8888) }
    val s by viewModel.x11Settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.connect() }

    val activity = LocalContext.current as? Activity
    LaunchedEffect(s.rotationLock) {
        activity?.requestedOrientation = when (s.rotationLock) {
            com.excp.podroid.x11.RotationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            com.excp.podroid.x11.RotationLock.PORTRAIT  -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            com.excp.podroid.x11.RotationLock.AUTO      -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val view = LocalView.current
    var fullscreen by remember { mutableStateOf(s.fullscreenDefault) }
    LaunchedEffect(fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val ctrl = WindowInsetsControllerCompat(window, view)
        if (fullscreen) {
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Keep the display awake while the X11 viewer is open, matching the
    // terminal (TerminalScreen adds the same flag). The VM-lifetime WakeLock in
    // PodroidService is partial/CPU-only; this is the screen-on counterpart.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Back exits fullscreen first (no on-screen exit button); a second Back
    // leaves the viewer through normal navigation.
    BackHandler(enabled = fullscreen) { fullscreen = false }

    var showSettings by remember { mutableStateOf(false) }

    var svWidth  by remember { mutableIntStateOf(1) }
    var svHeight by remember { mutableIntStateOf(1) }

    // Letterbox / pillarbox dst rect, pinned to top so the soft keyboard
    // (and the extra-keys row) live in the empty bottom strip.
    val (dstX, dstY, dstW, dstH) = remember(svWidth, svHeight, fb) {
        val fbW = fb.w.toFloat()
        val fbH = fb.h.toFloat()
        val viewW = svWidth.toFloat().coerceAtLeast(1f)
        val viewH = svHeight.toFloat().coerceAtLeast(1f)
        val scale = minOf(viewW / fbW, viewH / fbH)
        val dW = (fbW * scale).toInt().coerceAtLeast(1)
        val dH = (fbH * scale).toInt().coerceAtLeast(1)
        val dX = ((viewW - dW) / 2f).toInt()
        val dY = 0
        IntArray4(dX, dY, dW, dH)
    }

    val focusRequester = remember { FocusRequester() }
    val viewerFocus = remember { FocusRequester() }
    // Hold focus on the (non-editable) viewer while connected so a hardware
    // keyboard's keys reach onPreviewKeyEvent WITHOUT popping the soft keyboard
    // (a focused editable field would). The on-screen keyboard is summoned
    // explicitly via the keyboard button.
    LaunchedEffect(connection) {
        if (connection == X11ConnectionState.Connected) runCatching { viewerFocus.requestFocus() }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeBuf by remember { mutableStateOf(TextFieldValue("")) }

    // Sticky modifier state — tap CTRL once, the next key is sent with
    // Control_L held; the modifier auto-clears after that one keypress
    // (one-shot semantics, matches Termux convention).
    // Drag-lock: a long-press engages a held left button that persists across
    // gestures until the next tap drops it (move heavy GUI windows one-handed).
    var dragLocked by remember { mutableStateOf(false) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive  by remember { mutableStateOf(false) }

    fun sendWithModifiers(keysym: Int) {
        if (ctrlActive) viewModel.sendKey(XK_Control_L, down = true)
        if (altActive)  viewModel.sendKey(XK_Alt_L,     down = true)
        viewModel.sendKey(keysym, down = true)
        viewModel.sendKey(keysym, down = false)
        if (altActive)  viewModel.sendKey(XK_Alt_L,     down = false)
        if (ctrlActive) viewModel.sendKey(XK_Control_L, down = false)
        // One-shot: clear modifiers after one press.
        ctrlActive = false
        altActive  = false
    }

    fun onExtraKey(label: String) {
        when (label) {
            "CTRL" -> ctrlActive = !ctrlActive
            "ALT"  -> altActive  = !altActive
            else -> labelToKeysym(label)?.let(::sendWithModifiers)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(viewerFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                // Hardware/external keyboard. Lives on the (non-editable) Box so
                // it never pops the soft keyboard; the preview pass means it
                // fires for all key events while focus is anywhere in this
                // subtree (including when the on-screen keyboard field is up).
                val native = ev.nativeKeyEvent
                // Mouse right-click makes Android synthesize a BACK key. The
                // pointer handler already sent it to X as button 3, so swallow
                // the mouse-sourced Back (both down + up) to stop it exiting
                // fullscreen. A real Back (gesture/keyboard) passes through to
                // the BackHandler.
                if (ev.key == Key.Back) {
                    return@onPreviewKeyEvent (native.source and android.view.InputDevice.SOURCE_MOUSE) ==
                        android.view.InputDevice.SOURCE_MOUSE
                }
                if (android.view.KeyEvent.isModifierKey(native.keyCode)) {
                    return@onPreviewKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val special = when (ev.key) {
                    Key.Backspace      -> XK_BackSpace
                    Key.Enter, Key.NumPadEnter -> XK_Return
                    Key.Tab            -> XK_Tab
                    Key.Escape         -> XK_Escape
                    Key.DirectionLeft  -> XK_Left
                    Key.DirectionRight -> XK_Right
                    Key.DirectionUp    -> XK_Up
                    Key.DirectionDown  -> XK_Down
                    Key.MoveHome       -> 0xFF50
                    Key.MoveEnd        -> 0xFF57
                    Key.PageUp         -> 0xFF55
                    Key.PageDown       -> 0xFF56
                    Key.Delete         -> 0xFFFF
                    else               -> null
                }
                val ctrl = native.isCtrlPressed || ctrlActive
                val alt  = native.isAltPressed  || altActive
                val keysym: Int
                val shiftWrap: Boolean
                if (special != null) {
                    keysym = special
                    shiftWrap = native.isShiftPressed
                } else {
                    val cased = native.getUnicodeChar(
                        native.metaState and
                            (android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_CAPS_LOCK_ON)
                    )
                    if (cased == 0) return@onPreviewKeyEvent false
                    keysym = cased
                    shiftWrap = false
                }
                if (shiftWrap) viewModel.sendKey(XK_Shift_L, down = true)
                if (ctrl) viewModel.sendKey(XK_Control_L, down = true)
                if (alt)  viewModel.sendKey(XK_Alt_L, down = true)
                viewModel.sendKey(keysym, down = true)
                viewModel.sendKey(keysym, down = false)
                if (alt)  viewModel.sendKey(XK_Alt_L, down = false)
                if (ctrl) viewModel.sendKey(XK_Control_L, down = false)
                if (shiftWrap) viewModel.sendKey(XK_Shift_L, down = false)
                ctrlActive = false
                altActive  = false
                true
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Push the bottom of the layout up by the IME height when the
                // soft keyboard opens. Effect: extra-keys row rides above the
                // keyboard, AndroidView (weight=1) shrinks to fill the gap.
                .windowInsetsPadding(WindowInsets.ime),
        ) {
        if (!fullscreen) {
            PodroidTopBar(
                title = "X11",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { fullscreen = true }) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Settings")
                    }
                    IconButton(onClick = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Keyboard")
                    }
                    IconButton(onClick = onNavigateToTerminal) {
                        Icon(
                            Icons.Default.DesktopWindows,
                            contentDescription = "Terminal",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }

        if (showSettings) {
            X11SettingsSheet(viewModel = viewModel, onDismiss = { showSettings = false })
        }

        when (val state = connection) {
            X11ConnectionState.Connecting,
            X11ConnectionState.Disconnected -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Connecting to X11 server...",
                        modifier = Modifier.padding(top = 80.dp),
                        color = Color.White,
                    )
                }
            }
            is X11ConnectionState.Failed -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "X11 server not ready — VM still booting?\n${state.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            X11ConnectionState.Connected -> {
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(
                            s.touchMode, dstX, dstY, dstW, dstH, fb.w, fb.h,
                            s.trackpadSensitivity, s.trackpadAccel,
                        ) {
                            fun fbX(px: Float) = ((px - dstX) / dstW.coerceAtLeast(1) * fb.w).toInt().coerceIn(0, fb.w - 1)
                            fun fbY(py: Float) = ((py - dstY) / dstH.coerceAtLeast(1) * fb.h).toInt().coerceIn(0, fb.h - 1)
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue

                                    // Physical-mouse scroll wheel → X wheel (buttons 4/5).
                                    if (event.type == PointerEventType.Scroll) {
                                        val dy = change.scrollDelta.y
                                        if (dy != 0f) {
                                            viewModel.moveTo(fbX(change.position.x), fbY(change.position.y))
                                            viewModel.scroll(up = dy < 0f, ticks = abs(dy).toInt().coerceAtLeast(1))
                                        }
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }

                                    // Physical mouse → absolute move + native buttons.
                                    // Consuming keeps right-click from falling through to
                                    // Android Back (which exited fullscreen) and sends it
                                    // to X as button 3 instead.
                                    if (change.type == PointerType.Mouse) {
                                        var mask = 0
                                        if (event.buttons.isPrimaryPressed)   mask = mask or VncClient.BTN_LEFT
                                        if (event.buttons.isSecondaryPressed) mask = mask or VncClient.BTN_RIGHT
                                        if (event.buttons.isTertiaryPressed)  mask = mask or VncClient.BTN_MIDDLE
                                        viewModel.mouseUpdate(fbX(change.position.x), fbY(change.position.y), mask)
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }

                                    // Touch → finger-gesture state machine (one gesture).
                                    if (change.type != PointerType.Touch || !change.changedToDown()) continue
                                    viewerFocus.requestFocus()
                                    change.consume()
                                    val sx = change.position.x; val sy = change.position.y
                                    var lastX = sx; var lastY = sy
                                    var moved = 0f
                                    var maxPointers = 1
                                    var scrollAcc = 0f
                                    var leftHeld = false

                                    // A new touch while drag-locked drops the lock.
                                    if (dragLocked) {
                                        viewModel.release(VncClient.BTN_LEFT)
                                        dragLocked = false
                                        while (true) {
                                            val e = awaitPointerEvent(); e.changes.forEach { it.consume() }
                                            if (e.changes.none { it.pressed }) break
                                        }
                                        continue
                                    }

                                    if (s.touchMode == TouchMode.DIRECT) viewModel.moveTo(fbX(sx), fbY(sy))

                                    // Long-press (single finger, no move, ~500ms) => drag-lock.
                                    var outcome = "move"
                                    val completed = withTimeoutOrNull(500L) {
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val pressed = e.changes.filter { it.pressed }
                                            if (pressed.isEmpty()) { outcome = "tap"; return@withTimeoutOrNull Unit }
                                            if (pressed.size >= 2) { maxPointers = 2; outcome = "multi"; return@withTimeoutOrNull Unit }
                                            val p = pressed.first().position
                                            if (abs(p.x - sx) + abs(p.y - sy) > 16f) {
                                                lastX = p.x; lastY = p.y; outcome = "move"
                                                e.changes.forEach { it.consume() }
                                                return@withTimeoutOrNull Unit
                                            }
                                            e.changes.forEach { it.consume() }
                                        }
                                        @Suppress("UNREACHABLE_CODE") Unit
                                    }
                                    if (completed == null) {
                                        dragLocked = true; viewModel.press(VncClient.BTN_LEFT); leftHeld = true
                                    } else if (outcome == "tap") {
                                        viewModel.click(VncClient.BTN_LEFT)
                                        continue
                                    }

                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val pressed = e.changes.filter { it.pressed }
                                        maxPointers = maxOf(maxPointers, pressed.size)
                                        if (pressed.isEmpty()) break
                                        val p = pressed.first().position
                                        val dx = p.x - lastX; val dy = p.y - lastY
                                        moved += abs(dx) + abs(dy)
                                        if (pressed.size >= 2) {
                                            scrollAcc += dy
                                            while (abs(scrollAcc) >= 60f) {
                                                viewModel.scroll(scrollAcc < 0, 1)
                                                scrollAcc += if (scrollAcc < 0) 60f else -60f
                                            }
                                        } else when (s.touchMode) {
                                            TouchMode.DIRECT -> {
                                                viewModel.moveTo(fbX(p.x), fbY(p.y))
                                                if (!leftHeld) { viewModel.press(VncClient.BTN_LEFT); leftHeld = true }
                                            }
                                            TouchMode.TRACKPAD -> {
                                                val accel = if (s.trackpadAccel) (1f + (abs(dx) + abs(dy)) * 0.01f) else 1f
                                                val c = viewModel.cursor.value
                                                viewModel.moveTo(
                                                    (c.x + dx * s.trackpadSensitivity * accel).toInt(),
                                                    (c.y + dy * s.trackpadSensitivity * accel).toInt(),
                                                )
                                            }
                                        }
                                        lastX = p.x; lastY = p.y
                                        e.changes.forEach { it.consume() }
                                    }

                                    if (maxPointers >= 2) {
                                        if (moved < 28f) viewModel.click(VncClient.BTN_RIGHT)
                                    } else if (s.touchMode == TouchMode.TRACKPAD && !dragLocked && moved < 16f) {
                                        viewModel.click(VncClient.BTN_LEFT)
                                    }
                                    if (!dragLocked && leftHeld) { viewModel.release(VncClient.BTN_LEFT); leftHeld = false }
                                }
                            }
                        },
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {}
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                    svWidth = w
                                    svHeight = hh
                                    viewModel.requestResolution(w, hh)
                                }
                                override fun surfaceDestroyed(h: SurfaceHolder) {}
                            })
                        }
                    },
                    update = { sv ->
                        @Suppress("UNUSED_EXPRESSION")
                        frameCount
                        // Lock the IntArray for the copy into Bitmap pixels so
                        // we never observe a half-written frame from the RFB
                        // decoder thread (paired with synchronized(framebuffer)
                        // in X11ViewModel.connect).
                        synchronized(viewModel.framebuffer) {
                            val src = viewModel.framebuffer
                            val bw = bitmap.width
                            val bh = bitmap.height
                            // During a resolution change the framebuffer array is
                            // reallocated on the RFB thread while the Bitmap is
                            // recreated on a (slightly later) recomposition. Blit
                            // only when array and Bitmap agree in size, and clamp
                            // against the Bitmap's OWN dimensions — otherwise skip
                            // this frame (the next is consistent). Guards the
                            // "y + height must be <= bitmap.height()" crash on open.
                            if (src.size == bw * bh) {
                                val damage = viewModel.lastDamage
                                if (damage.isEmpty()) {
                                    bitmap.setPixels(src, 0, bw, 0, 0, bw, bh)
                                } else {
                                    for (r in damage) {
                                        val rx = r.x.coerceIn(0, bw)
                                        val ry = r.y.coerceIn(0, bh)
                                        val rw = (r.x + r.w).coerceAtMost(bw) - rx
                                        val rh = (r.y + r.h).coerceAtMost(bh) - ry
                                        if (rw <= 0 || rh <= 0) continue
                                        bitmap.setPixels(src, ry * bw + rx, bw, rx, ry, rw, rh)
                                    }
                                }
                            }
                        }
                        val holder = sv.holder
                        val canvas = holder.lockCanvas() ?: return@AndroidView
                        try {
                            canvas.drawColor(android.graphics.Color.BLACK)
                            val dst = Rect(dstX, dstY, dstX + dstW, dstY + dstH)
                            canvas.drawBitmap(bitmap, null, dst, null)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    },
                )

                if (s.showExtraKeys && !fullscreen) {
                    X11ExtraKeysRow(
                        onKey = ::onExtraKey,
                        ctrlActive = ctrlActive,
                        altActive  = altActive,
                    )
                }

                // Hidden IME hook (must stay in the layout while connected so
                // the requestFocus/show sequence has a target).
                BasicTextField(
                    value = imeBuf,
                    onValueChange = { new ->
                        val added = if (new.text.length > imeBuf.text.length)
                            new.text.substring(imeBuf.text.length) else ""
                        if ((ctrlActive || altActive) && added.length == 1) {
                            // Combine the sticky CTRL/ALT with the typed character
                            // (e.g. tap CTRL then type L → Ctrl+L to clear the
                            // terminal). sendWithModifiers clears the one-shot after.
                            sendWithModifiers(added[0].code)
                        } else {
                            forwardImeDiff(imeBuf.text, new.text, viewModel)
                        }
                        imeBuf = TextFieldValue("")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            viewModel.sendKey(XK_Return, down = true)
                            viewModel.sendKey(XK_Return, down = false)
                        },
                    ),
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester),
                )
            }
        } // end when
        } // end Column

    } // end Box
}

/**
 * Same vocabulary as the terminal's ExtraKeysRow so muscle memory carries
 * over: ESC, TAB, CTRL, arrows, ALT, punctuation, HOME/END, PGUP/PGDN, F1–F12.
 * CTRL and ALT are sticky one-shot modifiers (highlighted while active).
 */
@Composable
private fun X11ExtraKeysRow(
    onKey: (String) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        X11KeyButton("ESC", onKey)
        X11KeyButton("TAB", onKey)
        X11KeyButton("CTRL", onKey, isActive = ctrlActive)
        X11KeyButton("←", onKey, sendKey = "LEFT",  repeatable = true)
        X11KeyButton("↑", onKey, sendKey = "UP",    repeatable = true)
        X11KeyButton("↓", onKey, sendKey = "DOWN",  repeatable = true)
        X11KeyButton("→", onKey, sendKey = "RIGHT", repeatable = true)
        X11KeyButton("ALT", onKey, isActive = altActive)
        X11KeyButton("-", onKey)
        X11KeyButton("/", onKey)
        X11KeyButton("|", onKey)
        X11KeyButton("HOME", onKey)
        X11KeyButton("END", onKey)
        X11KeyButton("PGUP", onKey)
        X11KeyButton("PGDN", onKey)
        X11KeyButton("F1", onKey)
        X11KeyButton("F2", onKey)
        X11KeyButton("F3", onKey)
        X11KeyButton("F4", onKey)
        X11KeyButton("F5", onKey)
        X11KeyButton("F6", onKey)
        X11KeyButton("F7", onKey)
        X11KeyButton("F8", onKey)
        X11KeyButton("F9", onKey)
        X11KeyButton("F10", onKey)
        X11KeyButton("F11", onKey)
        X11KeyButton("F12", onKey)
    }
}

@Composable
private fun X11KeyButton(
    label: String,
    onKey: (String) -> Unit,
    sendKey: String = label,
    isActive: Boolean = false,
    repeatable: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, sendKey, repeatable) {
        if (!repeatable || !pressed) return@LaunchedEffect
        delay(400L)
        var interval = 70L
        while (pressed) {
            onKey(sendKey)
            delay(interval)
            if (interval > 30L) interval -= 3L
        }
    }
    val tapModifier = if (repeatable) {
        Modifier.pointerInput(sendKey) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onKey(sendKey)
                pressed = true
                try {
                    waitForUpOrCancellation()
                } finally {
                    pressed = false
                }
            }
        }
    } else {
        Modifier.clickable { onKey(sendKey) }
    }
    Text(
        text = label,
        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .then(tapModifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * Compares old vs new IME buffer content, fires synthetic X11 key events
 * for the diff. Printable characters use their ASCII code as the keysym
 * (X11 keysyms 0x20–0x7E match ASCII verbatim).
 */
private fun forwardImeDiff(old: String, new: String, vm: X11ViewModel) {
    if (new.length > old.length) {
        new.substring(old.length).forEach { ch ->
            val keysym = ch.code
            vm.sendKey(keysym, down = true)
            vm.sendKey(keysym, down = false)
        }
    } else if (new.length < old.length) {
        repeat(old.length - new.length) {
            vm.sendKey(XK_BackSpace, down = true)
            vm.sendKey(XK_BackSpace, down = false)
        }
    }
}

private data class IntArray4(val a: Int, val b: Int, val c: Int, val d: Int)
