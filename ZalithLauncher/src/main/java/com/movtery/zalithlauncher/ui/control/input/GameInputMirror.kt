/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.movtery.zalithlauncher.ui.control.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors text being entered by the Android IME so a chat/command input line can remain
 * visible above the keyboard without translating or moving the Minecraft framebuffer.
 */
object GameInputMirror {
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    private val _text = MutableStateFlow("")
    val text = _text.asStateFlow()

    @JvmStatic
    fun begin() {
        if (!_active.value) {
            _text.value = ""
            _active.value = true
        }
    }

    @JvmStatic
    fun updateText(value: String) {
        _text.value = value
    }

    @JvmStatic
    fun end() {
        _active.value = false
        _text.value = ""
    }
}
