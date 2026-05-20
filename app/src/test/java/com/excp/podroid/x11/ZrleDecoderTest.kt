/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.Deflater

class ZrleDecoderTest {
    /** Encode 3-byte CPIXEL from an ARGB int: order is B, G, R (little-endian channels). */
    private fun cpixel(argb: Int) = byteArrayOf(
        (argb and 0xFF).toByte(),           // B
        ((argb shr 8) and 0xFF).toByte(),   // G
        ((argb shr 16) and 0xFF).toByte()   // R
    )

    /** Compress plain bytes with zlib (nowrap=false = standard zlib header) and prepend 4-byte big-endian length. */
    private fun zrleRect(plain: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, /*nowrap=*/false)
        def.setInput(plain); def.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!def.finished()) { val n = def.deflate(buf); out.write(buf, 0, n) }
        val z = out.toByteArray()
        val hdr = java.nio.ByteBuffer.allocate(4).putInt(z.size).array()
        return hdr + z
    }

    @Test fun `solid tile fills with its color`() {
        val red = 0xFFFF0000.toInt()
        val plain = byteArrayOf(1) + cpixel(red)                 // subencoding 1 = solid
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain)))
        val target = IntArray(4 * 4)
        ZrleDecoder().decode(din, 0, 0, 4, 4, target, 4)
        assertEquals(red, target[0]); assertEquals(red, target[15])
    }

    @Test fun `raw tile copies pixels`() {
        val a = 0xFF010203.toInt(); val b = 0xFF040506.toInt()
        val plain = byteArrayOf(0) + cpixel(a) + cpixel(b) + cpixel(a) + cpixel(b) // 2x2 raw
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain)))
        val target = IntArray(2 * 2)
        ZrleDecoder().decode(din, 0, 0, 2, 2, target, 2)
        assertEquals(a, target[0]); assertEquals(b, target[1]); assertEquals(a, target[2]); assertEquals(b, target[3])
    }
}
