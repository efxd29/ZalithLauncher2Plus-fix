/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.control.input

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HidableInputLayout(
    onSend: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onClose: () -> Unit,
    keyboardController: SoftwareKeyboardController? = LocalSoftwareKeyboardController.current,
) {
    var view by remember { mutableStateOf<TouchCharInput?>(null) }
    var previewText by remember { mutableStateOf("") }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val previewScrollState = rememberScrollState()

    LaunchedEffect(previewText) {
        withFrameNanos { }
        previewScrollState.scrollTo(previewScrollState.maxValue)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier
            .alpha(0f)
            .size(1.dp),
        factory = { context ->
            TouchCharInput(context).apply {
                id = View.generateViewId()

                imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                        EditorInfo.IME_ACTION_DONE
                inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        InputType.TYPE_TEXT_VARIATION_FILTER or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE

                setEms(10)

                isFocusableInTouchMode = true
                nextFocusDownId = id
                nextFocusUpId = id
                nextFocusLeftId = id
                nextFocusRightId = id
            }.also { view0 ->
                view = view0.also {
                    it.setListener(
                        object : InputListener {
                            override fun onSend(char: Char) {
                                previewText += char
                                onSend(char.toString())
                            }
                            override fun onBackspace() {
                                if (previewText.isNotEmpty()) previewText = previewText.dropLast(1)
                                onBackspace()
                            }
                            override fun onEnter() {
                                onEnter()
                                previewText = ""
                                onClose()
                            }
                        }
                    )
                }
            }
        }
    )

    // Keep the Minecraft framebuffer in place. Show a lightweight, read-only mirror of the
    // chat/command text above the Android IME instead of moving the whole game surface.
    if (imeBottomPx > 0) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, -imeBottomPx) }
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(Color(0xD91A1A1A), RoundedCornerShape(7.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text(
                text = if (previewText.isEmpty()) "Chat / command" else previewText + "▏",
                color = if (previewText.isEmpty()) Color(0xFFB8B8B8) else Color.White,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.horizontalScroll(previewScrollState),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
    }

    LaunchedEffect(view) {
        val input = view ?: return@LaunchedEffect
        input.enableKeyboard()
    }

    DisposableEffect(Unit) {
        onDispose {
            view?.disableKeyboard()
            keyboardController?.hide()
            previewText = ""
            view = null
        }
    }

    OnKeyboardClosed {
        keyboardController?.hide()
        previewText = ""
        onClose()
    }
}