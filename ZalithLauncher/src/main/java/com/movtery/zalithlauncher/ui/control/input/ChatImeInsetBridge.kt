package com.movtery.zalithlauncher.ui.control.input

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileChannel.MapMode

/**
 * Shares IME geometry with the embedded game JVM using a tiny memory-mapped file.
 * It changes no game viewport or Surface size; the runtime agent uses it only while
 * the vanilla chat screen is being rendered.
 */
object ChatImeInsetBridge {
    private const val MAGIC = 0x5A43494D // "ZCIM"
    private const val STATE_SIZE = 12L

    @Volatile
    private var mapped: MappedByteBuffer? = null
    private var file: RandomAccessFile? = null
    private var channel: FileChannel? = null

    @Synchronized
    fun initialize(context: Context) {
        if (mapped != null) return
        runCatching {
            val stateFile = File(context.filesDir, "chat-ime-state.bin")
            file = RandomAccessFile(stateFile, "rw").also {
                if (it.length() < STATE_SIZE) it.setLength(STATE_SIZE)
            }
            channel = file!!.channel
            mapped = channel!!.map(MapMode.READ_WRITE, 0L, STATE_SIZE).order(ByteOrder.BIG_ENDIAN) as MappedByteBuffer
            mapped!!.putInt(0, MAGIC)
            mapped!!.putInt(4, 0)
            mapped!!.putInt(8, 1)
        }
    }

    fun update(visible: Boolean, imeBottomPx: Int, physicalHeightPx: Int) {
        val data = mapped ?: return
        val inset = if (visible) imeBottomPx.coerceAtLeast(0) else 0
        val height = physicalHeightPx.coerceAtLeast(1)
        if (data.getInt(0) != MAGIC || data.getInt(4) != inset || data.getInt(8) != height) {
            data.putInt(0, MAGIC)
            data.putInt(4, inset)
            data.putInt(8, height)
        }
    }
}
