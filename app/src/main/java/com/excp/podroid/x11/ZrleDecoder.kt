/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * ZRLE (RFB encoding 16) decoder.
 *
 * Wire format per rect:
 *   4 bytes big-endian compressed-data length
 *   N bytes zlib-compressed tile stream (standard zlib header, nowrap=false)
 *
 * The zlib stream is continuous across rects for the lifetime of the RFB session,
 * so a single Inflater is held as instance state and must NOT be re-created per call.
 *
 * Tiles are 64x64 max, row-major (left to right, top to bottom) covering the w*h rect.
 * Each tile begins with 1 subencoding byte:
 *   0        raw:          tw*th CPIXELs in raster order
 *   1        solid:        1 CPIXEL fills the whole tile
 *   2..16    packed-palette: N CPIXELs palette, then packed indices (1/2/4 bpp), row-aligned
 *   128      plain RLE:    sequence of (CPIXEL, run-length); run-length = sum-of-bytes+1
 *                          (each 0xFF means +255 and continue; final <0xFF byte ends the run)
 *   130..255 palette RLE:  palette of (subenc-128) CPIXELs; then index bytes:
 *                          bit7=0 -> single pixel palette[index]; bit7=1 -> palette[index&0x7F]
 *                          repeated run-length times (same sum-of-bytes+1 encoding)
 *
 * CPIXEL = 3 bytes in wire order B, G, R (matching the negotiated 32bpp/depth-24/LE/R16-G8-B0
 * pixel format). Decoded to ARGB: 0xFF_000000 | R<<16 | G<<8 | B.
 */
package com.excp.podroid.x11

import java.io.DataInputStream
import java.io.IOException
import java.util.zip.Inflater

class ZrleDecoder {

    private val inflater = Inflater(/*nowrap=*/false)

    // Scratch buffer for compressed input read from the socket.
    private var inputScratch = ByteArray(4096)
    // Decompressed output buffer; re-used across inflate calls within one decode() call.
    private var outputBuf = ByteArray(4096)

    // Remaining compressed bytes in the current rect that have not yet been fed to the inflater.
    private var remaining = 0
    private var inputStream: DataInputStream? = null

    /**
     * Decodes one ZRLE-encoded rectangle into [target].
     *
     * @param din    source stream positioned at the 4-byte length prefix of the ZRLE data
     * @param x      left edge of the rectangle in the framebuffer
     * @param y      top edge of the rectangle in the framebuffer
     * @param w      rectangle width
     * @param h      rectangle height
     * @param target ARGB framebuffer array
     * @param stride row stride of [target] (pixels per row)
     */
    fun decode(din: DataInputStream, x: Int, y: Int, w: Int, h: Int, target: IntArray, stride: Int) {
        // Read and decompress the rect's zlib block.
        val compLen = din.readInt()
        if (compLen < 0 || compLen > 64 * 1024 * 1024) throw IOException("ZRLE: absurd compressed length $compLen")

        // Feed the entire compressed block to the inflater.
        var bytesLeft = compLen
        while (bytesLeft > 0) {
            val chunk = minOf(bytesLeft, inputScratch.size)
            din.readFully(inputScratch, 0, chunk)
            inflater.setInput(inputScratch, 0, chunk)
            bytesLeft -= chunk
            // Drain any output the inflater can produce now so its internal buffer stays free.
            // We don't store it here; ZInput pulls on demand.
        }

        // Wrap the inflater so tile-level code just calls readByte()/readBytes().
        val zi = ZInput(inflater)

        // Tile loop: 64x64 tiles in row-major order.
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                decodeTile(zi, x + tx, y + ty, tw, th, target, stride)
                tx += 64
            }
            ty += 64
        }
    }

    private fun decodeTile(zi: ZInput, tx: Int, ty: Int, tw: Int, th: Int, target: IntArray, stride: Int) {
        val subenc = zi.readByte()
        when {
            subenc == 0 -> {
                // Raw: tw*th CPIXELs.
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    for (col in 0 until tw) {
                        target[base + col] = zi.readCpixel()
                    }
                }
            }
            subenc == 1 -> {
                // Solid: 1 CPIXEL, fill the whole tile.
                val color = zi.readCpixel()
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    for (col in 0 until tw) target[base + col] = color
                }
            }
            subenc in 2..16 -> {
                // Packed palette.
                val n = subenc
                val palette = IntArray(n) { zi.readCpixel() }
                val bitsPerIndex = when {
                    n == 2 -> 1
                    n <= 4 -> 2
                    else -> 4
                }
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    // Each row is bit-packed, byte-aligned.
                    var col = 0
                    var accumByte = 0
                    var bitsInAccum = 0
                    while (col < tw) {
                        if (bitsInAccum == 0) {
                            accumByte = zi.readByte()
                            bitsInAccum = 8
                        }
                        val idx = (accumByte ushr (8 - bitsPerIndex)) and ((1 shl bitsPerIndex) - 1)
                        accumByte = (accumByte shl bitsPerIndex) and 0xFF
                        bitsInAccum -= bitsPerIndex
                        target[base + col] = palette[idx]
                        col++
                    }
                    // Discard any padding bits at end of row (bitsInAccum may be > 0 but
                    // we already read the full byte; nothing extra to consume).
                }
            }
            subenc == 128 -> {
                // Plain RLE: sequence of runs until tile is full.
                val total = tw * th
                var filled = 0
                while (filled < total) {
                    val color = zi.readCpixel()
                    val runLen = zi.readRunLength()
                    repeat(runLen) {
                        val pos = filled + it
                        val row = pos / tw; val col = pos % tw
                        target[(ty + row) * stride + (tx + col)] = color
                    }
                    filled += runLen
                }
            }
            subenc in 130..255 -> {
                // Palette RLE.
                val n = subenc - 128
                val palette = IntArray(n) { zi.readCpixel() }
                val total = tw * th
                var filled = 0
                while (filled < total) {
                    val indexByte = zi.readByte()
                    if (indexByte and 0x80 == 0) {
                        // Single pixel.
                        val pos = filled
                        val row = pos / tw; val col = pos % tw
                        target[(ty + row) * stride + (tx + col)] = palette[indexByte]
                        filled++
                    } else {
                        // Run of palette[index & 0x7F].
                        val color = palette[indexByte and 0x7F]
                        val runLen = zi.readRunLength()
                        repeat(runLen) {
                            val pos = filled + it
                            val row = pos / tw; val col = pos % tw
                            target[(ty + row) * stride + (tx + col)] = color
                        }
                        filled += runLen
                    }
                }
            }
            else -> throw IOException("ZRLE: unsupported subencoding $subenc")
        }
    }

    /**
     * Thin wrapper around [Inflater] that provides byte-level and CPIXEL reads.
     * The inflater's input was already loaded by [decode]; this just drains output.
     */
    private inner class ZInput(private val inf: Inflater) {
        private val buf = ByteArray(256)
        private var pos = 0
        private var avail = 0

        private fun fill() {
            while (avail == 0) {
                if (inf.finished()) throw IOException("ZRLE: inflater finished early")
                val n = inf.inflate(buf)
                if (n > 0) { pos = 0; avail = n }
                // n == 0 with needsInput means all input was consumed; if finished() is false
                // but no output and needsInput, the caller overfed or the stream is malformed.
                else if (inf.needsInput()) throw IOException("ZRLE: inflater needs more input but none queued")
            }
        }

        fun readByte(): Int {
            fill()
            return buf[pos++].toInt().also { avail-- } and 0xFF
        }

        /** Read 3-byte CPIXEL (B, G, R) and return as ARGB int. */
        fun readCpixel(): Int {
            val b = readByte()
            val g = readByte()
            val r = readByte()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /**
         * Read a ZRLE run-length (sum-of-bytes + 1).
         * Each byte 0xFF contributes 255 and reading continues;
         * the first byte < 0xFF ends the sequence, contributing its value.
         * The actual run count = sum + 1.
         */
        fun readRunLength(): Int {
            var total = 0
            while (true) {
                val b = readByte()
                total += b
                if (b != 0xFF) break
            }
            return total + 1
        }
    }
}
