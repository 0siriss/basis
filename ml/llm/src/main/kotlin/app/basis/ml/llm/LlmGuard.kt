package app.basis.ml.llm

import android.content.Context
import android.os.Process
import app.basis.core.common.AppLog

/**
 * Crash-loop protection. Marks "model X is in use by pid P" while an engine is open. If a new process
 * finds the mark of a dead process, that model killed (or got killed with) the previous process — usually
 * out of memory. After [MAX_FAILURES] such deaths automatic runs with that model stop until a manual run
 * succeeds or the settings change.
 */
object LlmGuard {
    private const val PREFS = "llm_guard"
    const val MAX_FAILURES = 2

    private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call once per process start: turns a leftover mark into a counted failure. */
    fun checkPreviousProcess(context: Context) {
        val sp = sp(context)
        val model = sp.getString("inflight_model", null) ?: return
        val pid = sp.getInt("inflight_pid", -1)
        if (pid == Process.myPid()) return
        val failures = sp.getInt("fail_$model", 0) + 1
        sp.edit().remove("inflight_model").remove("inflight_pid").putInt("fail_$model", failures).apply()
        AppLog.e("LLM", "процесс погиб во время работы модели $model (раз подряд: $failures) — вероятно, не хватило памяти")
    }

    fun failures(context: Context, modelId: String): Int = sp(context).getInt("fail_$modelId", 0)

    fun blocked(context: Context, modelId: String): Boolean = failures(context, modelId) >= MAX_FAILURES

    fun begin(context: Context, modelId: String) {
        sp(context).edit().putString("inflight_model", modelId).putInt("inflight_pid", Process.myPid()).commit()
    }

    fun end(context: Context) {
        sp(context).edit().remove("inflight_model").remove("inflight_pid").commit()
    }

    fun success(context: Context, modelId: String) {
        sp(context).edit().remove("fail_$modelId").apply()
    }

    /** The user pressed a button: allow one run even if blocked (WorkManager re-runs after a crash don't get this). */
    fun grantManual(context: Context) {
        sp(context).edit().putLong("manual_grant", System.currentTimeMillis()).apply()
    }

    fun consumeManualGrant(context: Context): Boolean {
        val sp = sp(context)
        val t = sp.getLong("manual_grant", 0)
        sp.edit().remove("manual_grant").apply()
        return System.currentTimeMillis() - t < 10 * 60_000
    }

    fun reset(context: Context) {
        sp(context).edit().clear().apply()
    }
}
