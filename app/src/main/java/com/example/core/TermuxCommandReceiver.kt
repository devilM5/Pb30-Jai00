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
        val resultBundle = intent.getBundleExtra(RESULT_BUNDLE_KEY)

        val result = if (resultBundle != null) {
            TermuxIntentResult(
                stdout = resultBundle.getString("stdout", ""),
                stderr = resultBundle.getString("stderr", ""),
                exitCode = resultBundle.getInt("exitCode", -1),
                internalErrCode = resultBundle.getInt("err", 0),
                internalErrMsg = resultBundle.getString("errmsg", "")
            )
        } else {
            TermuxIntentResult(
                stdout = "",
                stderr = "Termux returned an empty result bundle. Ensure allow-external-apps=true in ~/.termux/termux.properties.",
                exitCode = -1,
                internalErrCode = -1,
                internalErrMsg = "Missing result bundle"
            )
        }
        TermuxExecutionCallbacks.complete(executionId, result)
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "friday_termux_exec_id"
        private const val RESULT_BUNDLE_KEY = "com.termux.execute.plugin.result.bundle"
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
