/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.movtery.zalithlauncher.game.sdl

import android.view.KeyEvent
import com.movtery.zalithlauncher.game.input.EfficientAndroidLWJGLKeycode
import org.libsdl.app.SDLActivity

/**
 * Sends a short, synthetic key press through SDL's input queue.
 *
 * Use this only when Minecraft owns the SDL render/input path. Sending the same key
 * through both GLFW and SDL can enqueue duplicate state transitions in SDL-era clients.
 */
object SdlTextSender {
    @JvmStatic
    fun sendKey(lwjglGlfwKeycode: Int) {
        if (!SdlBridge.sdlEnabled || !SdlBridge.isSdlRenderActive()) return

        val androidKeycode = EfficientAndroidLWJGLKeycode.getSdlAndroidKeycode(lwjglGlfwKeycode)
        if (androidKeycode == KeyEvent.KEYCODE_UNKNOWN) return

        SDLActivity.onNativeKeyDown(androidKeycode)
        SDLActivity.onNativeKeyUp(androidKeycode)
    }
}
