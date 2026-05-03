package com.sarif.auto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed class UssdResult {
    data class Message(val text: String) : UssdResult()
    data class Failure(val code: Int, val message: String?) : UssdResult()

    companion object {
        /** No callback from modem/RIL within [UssdExecutor.USSD_CALLBACK_TIMEOUT_MS]. */
        const val FAILURE_USSD_TIMEOUT = -3
        /** Could not launch system phone USSD UI. */
        const val FAILURE_USSD_LAUNCH = -4
    }
}

/**
 * Sends USSD strings one after another on the same subscription.
 * Note: Many networks do not treat follow-up API calls as the same interactive session;
 * if this fails, you may need a single stacked USSD string or an Accessibility-based flow.
 */
class UssdExecutor(
    context: Context,
    private val subscriptionId: Int
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private fun logStepMeta(index: Int, total: Int, ussd: String) {
        val kind = when {
            ussd.startsWith("*") || ussd.startsWith("#") -> "code"
            ussd.all { it.isDigit() } -> "digits"
            else -> "other"
        }
        Log.d(TAG, "USSD step ${index + 1}/$total kind=$kind len=${ussd.length} subId=$subscriptionId")
    }

    private fun telephonyForSub(): TelephonyManager {
        val tm = appContext.getSystemService(TelephonyManager::class.java)
        return if (subscriptionId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tm.createForSubscriptionId(subscriptionId)
        } else {
            tm
        }
    }

    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    suspend fun sendChain(
        steps: List<String>,
        betweenStepsMs: Long = 0L
    ): List<UssdResult> = withContext(Dispatchers.Main) {
        val out = mutableListOf<UssdResult>()
        val nonEmpty = steps.map { it.trim() }.filter { it.isNotEmpty() }
        Log.d(TAG, "sendChain: ${nonEmpty.size} step(s), subId=$subscriptionId")
        nonEmpty.forEachIndexed { index, trimmed ->
            logStepMeta(index, nonEmpty.size, trimmed)
            out.add(sendOne(trimmed))
            val last = out.last()
            if (last is UssdResult.Failure) return@withContext out
            if (betweenStepsMs > 0 && index < nonEmpty.lastIndex) {
                delay(betweenStepsMs)
            }
        }
        out
    }

    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    suspend fun sendOneStep(ussd: String): UssdResult = withContext(Dispatchers.Main) {
        val t = ussd.trim()
        logStepMeta(0, 1, t)
        sendOne(t)
    }

    /**
     * Opens the phone stack USSD UI via ACTION_CALL so the USSD popup is visible.
     * Returns false when launch is blocked/fails.
     */
    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    fun launchSystemUssdPopup(ussd: String): Boolean {
        val t = ussd.trim()
        if (t.isEmpty()) return false
        return try {
            val uri = Uri.parse("tel:${Uri.encode(t)}")
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Log.d(TAG, "USSD popup launch requested len=${t.length} subId=$subscriptionId")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "USSD popup launch denied", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "USSD popup launch error", e)
            false
        }
    }

    @RequiresPermission(android.Manifest.permission.CALL_PHONE)
    private suspend fun sendOne(ussd: String): UssdResult =
        withTimeoutOrNull(USSD_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val tm = telephonyForSub()
                try {
                    tm.sendUssdRequest(
                        ussd,
                        object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(
                                telephonyManager: TelephonyManager,
                                request: String,
                                response: CharSequence?
                            ) {
                                if (cont.isActive) {
                                    val text = response?.toString().orEmpty()
                                    Log.d(TAG, "USSD response ok len=${text.length} preview=${text.take(160)}")
                                    cont.resume(UssdResult.Message(text))
                                }
                            }

                            override fun onReceiveUssdResponseFailed(
                                telephonyManager: TelephonyManager,
                                request: String?,
                                failureCode: Int
                            ) {
                                if (cont.isActive) {
                                    Log.w(
                                        TAG,
                                        "USSD failed failureCode=$failureCode " +
                                            "requestLen=${request?.length ?: 0} (${ussdFailureHint(failureCode)})"
                                    )
                                    cont.resume(UssdResult.Failure(failureCode, null))
                                }
                            }
                        },
                        handler
                    )
                } catch (e: SecurityException) {
                    Log.e(TAG, "USSD SecurityException", e)
                    if (cont.isActive) cont.resume(UssdResult.Failure(-1, e.message))
                } catch (e: Exception) {
                    Log.e(TAG, "USSD error", e)
                    if (cont.isActive) cont.resume(UssdResult.Failure(-2, e.message))
                }
            }
        } ?: run {
            Log.e(TAG, "USSD no response after ${USSD_CALLBACK_TIMEOUT_MS}ms (len=${ussd.length})")
            UssdResult.Failure(UssdResult.FAILURE_USSD_TIMEOUT, "timeout")
        }

    companion object {
        private const val TAG = "SarifAuto"
        private const val USSD_CALLBACK_TIMEOUT_MS = 90_000L

        /**
         * [failureCode] is from [TelephonyManager.UssdResponseCallback.onReceiveUssdResponseFailed].
         * -1 is commonly USSD_RETURN_FAILURE: network/modem rejected the string (wrong format,
         * no coverage, USSD busy, or carrier blocks API-initiated USSD on this code).
         */
        private fun ussdFailureHint(failureCode: Int): String = when (failureCode) {
            TelephonyManager.USSD_RETURN_FAILURE ->
                "USSD_RETURN_FAILURE: wrong format, no signal, session busy, or carrier blocked API USSD"
            UssdResult.FAILURE_USSD_TIMEOUT -> "no modem callback within timeout"
            1 -> "USSD_ERROR_SERVICE_UNAVAILABLE (if supported on this API)"
            2 -> "USSD_ERROR_CONNECTION_LOST (if supported on this API)"
            else -> "see TelephonyManager / modem docs for code=$failureCode"
        }
    }
}
