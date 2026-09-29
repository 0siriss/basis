package app.basis.ml.llm

/** JNI bindings, see src/main/cpp/llm_jni.cpp. */
internal object LlamaNative {
    fun interface ByteListener {
        fun onBytes(bytes: ByteArray): Boolean
    }

    @Volatile private var initialized: String? = null

    /** Loads the native library and the CPU backend variants from [libDir] (nativeLibraryDir). */
    @Synchronized
    fun init(libDir: String): String {
        initialized?.let { return it }
        System.loadLibrary("basis_llm")
        return nativeInit(libDir).also { initialized = it }
    }

    external fun nativeInit(libDir: String): String
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, repack: Boolean): Long
    external fun nativeSetThreads(handle: Long, n: Int)
    external fun nativeDescribe(handle: Long): String
    external fun nativeContextSize(handle: Long): Int
    external fun nativeCountTokens(handle: Long, text: ByteArray): Int
    external fun nativeGenerate(
        handle: Long, prompt: ByteArray, grammar: String?, maxTokens: Int, temperature: Float, seed: Int, listener: ByteListener,
    ): ByteArray?
    external fun nativeLastStats(handle: Long): LongArray
    external fun nativeFree(handle: Long)
}
