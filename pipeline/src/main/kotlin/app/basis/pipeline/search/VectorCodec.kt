package app.basis.pipeline.search

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stores an L2-normalized embedding as int8 with one float scale: 4 + dim bytes (1028 for BGE-M3)
 * instead of 4·dim. Ranking by cosine is practically unchanged (quantization error ≈ 0.4% of max).
 */
object VectorCodec {
    fun encode(v: FloatArray): ByteArray {
        val max = v.maxOfOrNull { abs(it) }?.takeIf { it > 0f } ?: 1f
        val scale = max / 127f
        val out = ByteArray(4 + v.size)
        val bits = java.lang.Float.floatToRawIntBits(scale)
        out[0] = (bits ushr 24).toByte(); out[1] = (bits ushr 16).toByte(); out[2] = (bits ushr 8).toByte(); out[3] = bits.toByte()
        for (i in v.indices) out[4 + i] = (v[i] / scale).roundToInt().coerceIn(-127, 127).toByte()
        return out
    }

    fun dim(blob: ByteArray): Int = blob.size - 4

    fun decode(blob: ByteArray): FloatArray {
        val scale = scale(blob)
        return FloatArray(dim(blob)) { blob[4 + it] * scale }
    }

    /** Dot product of a float query with a stored vector (= cosine, both normalized). */
    fun dot(query: FloatArray, blob: ByteArray): Float {
        require(dim(blob) == query.size) { "dimension mismatch: ${dim(blob)} vs ${query.size}" }
        var acc = 0f
        for (i in query.indices) acc += query[i] * blob[4 + i]
        return acc * scale(blob)
    }

    private fun scale(b: ByteArray): Float = java.lang.Float.intBitsToFloat(
        ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff),
    )
}

/** Keeps the [k] best-scoring ids seen so far. */
class TopK(private val k: Int) {
    private val heap = java.util.PriorityQueue<Pair<Long, Float>>(compareBy { it.second })

    fun offer(id: Long, score: Float) {
        if (heap.size < k) heap.add(id to score)
        else if (score > heap.peek().second) { heap.poll(); heap.add(id to score) }
    }

    /** Best first. */
    fun result(): List<Pair<Long, Float>> = heap.sortedByDescending { it.second }
}

/**
 * Reciprocal rank fusion: score(d) = Σ 1 / (k + rank_i(d)), rank from 1. Robust way to merge a
 * keyword ranking with a vector ranking whose scores are not comparable.
 */
object Rrf {
    fun fuse(rankings: List<List<Long>>, k: Int = 60): List<Pair<Long, Double>> {
        val scores = LinkedHashMap<Long, Double>()
        for (ranking in rankings) {
            ranking.distinct().forEachIndexed { i, id -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + i + 1) }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
