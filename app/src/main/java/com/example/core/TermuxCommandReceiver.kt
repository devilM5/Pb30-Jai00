package com.example.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

data class TermuxIntentResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val internalErrCode: Int,
    val internalErrMsg: String
)

/**
 * Receives real stdout/stderr/exitCode callbacks from Termux's RunCommandService via PendingIntent.
 */
class TermuxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val result = parseResultIntent(intent, context.classLoader)
        TermuxExecutionCallbacks.complete(executionId, result)
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "friday_termux_exec_id"
        const val TERMUX_RESULT_BUNDLE_KEY = "result"
        private const val LEGACY_RESULT_BUNDLE_KEY = "com.termux.execute.plugin.result.bundle"

        internal fun parseResultIntent(
            intent: Intent,
            classLoader: ClassLoader? = null
        ): TermuxIntentResult {
            classLoader?.let { intent.setExtrasClassLoader(it) }
            val resultBundle = intent.getBundleExtra(TERMUX_RESULT_BUNDLE_KEY)
                ?: intent.getBundleExtra(LEGACY_RESULT_BUNDLE_KEY)

            if (resultBundle != null) {
                classLoader?.let { resultBundle.classLoader = it }
                val exitCode = when {
                    resultBundle.containsKey("exitCode") -> resultBundle.getInt("exitCode", -1)
                    resultBundle.containsKey("exit_code") -> resultBundle.getInt("exit_code", -1)
                    else -> -1
                }
                return TermuxIntentResult(
                    stdout = resultBundle.getString("stdout", "").orEmpty(),
                    stderr = resultBundle.getString("stderr", "").orEmpty(),
                    exitCode = exitCode,
                    internalErrCode = resultBundle.getInt("err", 0),
                    internalErrMsg = resultBundle.getString("errmsg", "").orEmpty()
                )
            }

            // Some Termux forks/plugins may place keys directly on Intent extras as a fallback
            val directHasFields = intent.hasExtra("stdout") ||
                intent.hasExtra("stderr") ||
                intent.hasExtra("exitCode") ||
                intent.hasExtra("exit_code") ||
                intent.hasExtra("err")
            if (directHasFields) {
                val exitCode = when {
                    intent.hasExtra("exitCode") -> intent.getIntExtra("exitCode", -1)
                    intent.hasExtra("exit_code") -> intent.getIntExtra("exit_code", -1)
                    else -> -1
                }
                return TermuxIntentResult(
                    stdout = intent.getStringExtra("stdout").orEmpty(),
                    stderr = intent.getStringExtra("stderr").orEmpty(),
                    exitCode = exitCode,
                    internalErrCode = intent.getIntExtra("err", 0),
                    internalErrMsg = intent.getStringExtra("errmsg").orEmpty()
                )
            }

            return TermuxIntentResult(
                stdout = "",
                stderr = "Termux returned an empty result bundle. Ensure allow-external-apps=true in ~/.termux/termux.properties.",
                exitCode = -1,
                internalErrCode = -1,
                internalErrMsg = "Missing result bundle"
            )
        }
    }
}

object TermuxExecutionCallbacks {
    private val pendingMap = ConcurrentHashMap<Int, CompletableDeferred<TermuxIntentResult>>()

    fun register(executionId: Int): CompletableDeferred<TermuxIntentResult> {
        val deferred = CompletableDeferred<TermuxIntentResult>()
        pendingMap[executionId] = deferred
        return deferred
    }

    fun complete(executionId: Int, result: TermuxIntentResult) {
        pendingMap.remove(executionId)?.complete(result)
    }

    fun cancel(executionId: Int) {
        pendingMap.remove(executionId)?.cancel()
    }
}
