package com.example.tiktoktowhatsappstickers.core.engine

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin (no Android dependencies) reader/writer for the WebP RIFF container.
 *
 * Android can decode animated WebP but cannot encode it, so the engine decodes every frame,
 * re-renders it on a fixed 512x512 canvas and re-assembles the animation with this builder.
 * Keeping it free of Android classes lets it be unit-tested on the JVM.
 */
object WebpContainer {

    class ChunkData(val fourCc: String, val bytes: ByteArray)

    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        /** true = alpha-blend over the previous canvas, false = overwrite the rectangle. */
        val blend: Boolean,
        /** true = clear this frame's rectangle to transparent before the next frame. */
        val disposeToBackground: Boolean,
        val payload: List<ChunkData>
    )

    class Info(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val hasAnimationFlag: Boolean,
        val hasAlpha: Boolean,
        val loopCount: Int,
        val frames: List<Frame>
    ) {
        /** Animated for WhatsApp's purposes: flagged AND more than one real frame. */
        val isAnimated: Boolean get() = hasAnimationFlag && frames.size >= 2
    }

    class EncodedFrame(val chunks: List<ChunkData>, val durationMs: Int)

    private const val ANIMATION_FLAG = 0x02
    private const val ALPHA_FLAG = 0x10

    fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 && fourCc(b, 0) == "RIFF" && fourCc(b, 8) == "WEBP"

    /** Parses a WebP file. Returns null if the bytes are not a well-formed WebP. */
    fun parse(bytes: ByteArray): Info? {
        if (!isWebp(bytes)) return null
        val riffSize = le32(bytes, 4)
        val end = minOf(bytes.size.toLong(), riffSize + 8L).toInt()

        var canvasW = 0
        var canvasH = 0
        var animFlag = false
        var alpha = false
        var loop = 0
        val frames = ArrayList<Frame>()
        val stillChunks = ArrayList<ChunkData>()

        var pos = 12
        while (pos + 8 <= end) {
            val id = fourCc(bytes, pos)
            val size = le32(bytes, pos + 4)
            if (size < 0 || pos + 8L + size > end) break
            val data = pos + 8
            when (id) {
                "VP8X" -> if (size >= 10) {
                    val flags = bytes[data].toInt()
                    animFlag = (flags and ANIMATION_FLAG) != 0
                    alpha = (flags and ALPHA_FLAG) != 0
                    canvasW = le24(bytes, data + 4) + 1
                    canvasH = le24(bytes, data + 7) + 1
                }
                "ANIM" -> if (size >= 6) loop = le16(bytes, data + 4)
                "ANMF" -> if (size >= 16) {
                    val flags = bytes[data + 15].toInt()
                    frames.add(
                        Frame(
                            x = le24(bytes, data) * 2,
                            y = le24(bytes, data + 3) * 2,
                            width = le24(bytes, data + 6) + 1,
                            height = le24(bytes, data + 9) + 1,
                            durationMs = le24(bytes, data + 12),
                            blend = (flags and 0x02) == 0,
                            disposeToBackground = (flags and 0x01) != 0,
                            payload = subChunks(bytes, data + 16, data + size)
                        )
                    )
                }
                "ALPH", "VP8 ", "VP8L" -> stillChunks.add(ChunkData(id, bytes.copyOfRange(data, data + size)))
            }
            pos = data + size + (size and 1)
        }

        if (canvasW == 0 && stillChunks.isNotEmpty()) {
            stillSize(stillChunks)?.let { (w, h) -> canvasW = w; canvasH = h }
        }
        if (canvasW <= 0 || canvasH <= 0) return null
        return Info(canvasW, canvasH, animFlag, alpha, loop, frames)
    }

    /** Wraps one animation frame's bitstream into a standalone WebP so Android can decode it. */
    fun wrapFrameAsStill(frame: Frame): ByteArray {
        val hasAlph = frame.payload.any { it.fourCc == "ALPH" }
        val chunks = ByteArrayOutputStream()
        if (hasAlph) {
            val vp8x = ByteArray(10)
            vp8x[0] = ALPHA_FLAG.toByte()
            put24(vp8x, 4, frame.width - 1)
            put24(vp8x, 7, frame.height - 1)
            writeChunk(chunks, "VP8X", vp8x)
        }
        frame.payload.forEach { writeChunk(chunks, it.fourCc, it.bytes) }
        return riff(chunks.toByteArray())
    }

    /** Extracts the image bitstream chunks (ALPH / VP8 / VP8L) from a still WebP. */
    fun extractImageChunks(still: ByteArray): List<ChunkData> {
        if (!isWebp(still)) return emptyList()
        val end = minOf(still.size.toLong(), le32(still, 4) + 8L).toInt()
        return subChunks(still, 12, end).filter { it.fourCc == "ALPH" || it.fourCc == "VP8 " || it.fourCc == "VP8L" }
    }

    /** Builds an animated WebP whose frames all cover the full canvas at (0,0). */
    fun buildAnimated(width: Int, height: Int, frames: List<EncodedFrame>, loopCount: Int = 0): ByteArray {
        require(width in 1..16384 && height in 1..16384) { "bad canvas $width x $height" }
        require(frames.isNotEmpty()) { "no frames" }
        val body = ByteArrayOutputStream()

        val vp8x = ByteArray(10)
        vp8x[0] = (ANIMATION_FLAG or ALPHA_FLAG).toByte()
        put24(vp8x, 4, width - 1)
        put24(vp8x, 7, height - 1)
        writeChunk(body, "VP8X", vp8x)

        val anim = ByteArray(6) // background colour 0x00000000 (transparent), then loop count
        put16(anim, 4, loopCount)
        writeChunk(body, "ANIM", anim)

        for (f in frames) {
            val inner = ByteArrayOutputStream()
            val header = ByteArray(16)
            put24(header, 0, 0) // x / 2
            put24(header, 3, 0) // y / 2
            put24(header, 6, width - 1)
            put24(header, 9, height - 1)
            put24(header, 12, f.durationMs.coerceIn(1, 0xFFFFFF))
            header[15] = 0x02.toByte() // do not blend: each frame fully replaces the canvas; dispose none
            inner.write(header)
            f.chunks.forEach { writeChunk(inner, it.fourCc, it.bytes) }
            writeChunk(body, "ANMF", inner.toByteArray())
        }
        return riff(body.toByteArray())
    }

    /** Width/height of a still bitstream (VP8 lossy or VP8L lossless). */
    fun stillSize(chunks: List<ChunkData>): Pair<Int, Int>? {
        chunks.firstOrNull { it.fourCc == "VP8L" }?.let { c ->
            val b = c.bytes
            if (b.size < 5 || (b[0].toInt() and 0xFF) != 0x2F) return null
            val bits = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8) or
                ((b[3].toInt() and 0xFF) shl 16) or ((b[4].toInt() and 0xFF) shl 24)
            return Pair((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
        }
        chunks.firstOrNull { it.fourCc == "VP8 " }?.let { c ->
            val b = c.bytes
            if (b.size < 10 || (b[3].toInt() and 0xFF) != 0x9D || (b[4].toInt() and 0xFF) != 0x01 ||
                (b[5].toInt() and 0xFF) != 0x2A
            ) return null
            val w = ((b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)) and 0x3FFF
            val h = ((b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)) and 0x3FFF
            return Pair(w, h)
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun subChunks(b: ByteArray, from: Int, to: Int): List<ChunkData> {
        val out = ArrayList<ChunkData>()
        var pos = from
        while (pos + 8 <= to) {
            val id = fourCc(b, pos)
            val size = le32(b, pos + 4)
            if (size < 0 || pos + 8L + size > to) break
            out.add(ChunkData(id, b.copyOfRange(pos + 8, pos + 8 + size)))
            pos += 8 + size + (size and 1)
        }
        return out
    }

    private fun riff(chunks: ByteArray): ByteArray {
        val out = ByteArray(12 + chunks.size)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        put32(out, 4, 4 + chunks.size)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        chunks.copyInto(out, 12)
        return out
    }

    private fun writeChunk(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        val size = ByteArray(4)
        put32(size, 0, data.size)
        out.write(size)
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun fourCc(b: ByteArray, o: Int) = String(b, o, 4, Charsets.US_ASCII)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le24(b: ByteArray, o: Int) = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)
    private fun le32(b: ByteArray, o: Int): Int = le24(b, o) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun put24(b: ByteArray, o: Int, v: Int) { put16(b, o, v); b[o + 2] = (v shr 16).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Int) { put24(b, o, v); b[o + 3] = (v shr 24).toByte() }
}
