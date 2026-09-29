package app.basis.pipeline

import android.content.Context
import java.io.File

/** Looks for anything audio-like in the app's storage — to prove no audio outlives recognition. */
object AudioAudit {
    private val AUDIO_EXT = setOf("bseg", "pcm", "wav", "raw", "m4a", "ogg", "opus", "mp3", "aac", "3gp", "amr", "flac")

    data class Result(val pendingSegments: Int, val otherAudio: List<File>)

    fun scan(context: Context): Result {
        val roots = listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir, context.externalCacheDir, context.getExternalFilesDir(null))
        val models = File(context.filesDir, "models")
        var pending = 0
        val other = mutableListOf<File>()
        roots.distinct().forEach { root ->
            root.walkTopDown().onEnter { it != models }.filter { it.isFile }.forEach { f ->
                val ext = f.extension.lowercase()
                val tmpSegment = f.name.endsWith(".bseg.tmp")
                when {
                    ext == "bseg" && f.parentFile?.name == "segments" -> pending++
                    ext in AUDIO_EXT || tmpSegment -> other += f
                }
            }
        }
        return Result(pending, other)
    }
}
