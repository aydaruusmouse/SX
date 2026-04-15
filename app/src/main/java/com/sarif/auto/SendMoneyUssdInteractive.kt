package com.sarif.auto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Send-money USSD after balance check. When Accessibility PIN mode is on, runs opener via
 * [UssdExecutor.launchSystemUssdPopup] and injects PIN like [BalanceUssdInteractive] — Telephony-only
 * PIN often returns "invalid PIN format" on ZAAD.
 */
object SendMoneyUssdInteractive {

    private const val TAG = "SarifAuto"

    /** USSD dialers sometimes receive fullwidth * or bidi marks — breaks startsWith("*") checks. */
    private fun normalizeUssd(s: String): String {
        return s.trim()
            .trimStart {
                it == '\u200E' || it == '\u200F' || it == '\u202A' || it == '\u202B' ||
                    it == '\u2066' || it == '\u2067' || it == '\u2068' || it == '\u2069'
            }
            .replace('\uFF0A', '*')
            .replace('\u2217', '*')
    }

    /** `*220*MSISDN*AMOUNT#` transfer (not `*222#`, which is balance). */
    private fun isStackedMoneySendOpener(s: String): Boolean {
        return PlaceholderUssd.isStackedSendMoneyOpener(normalizeUssd(s))
    }

    private fun isPinDigits(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && t.all { it.isDigit() } && t.length in 4..12
    }

    suspend fun runSendChain(
        context: Context,
        executor: UssdExecutor,
        steps: List<String>,
        stepDelayMs: Long,
        prefs: SecurePrefs,
        initialStepIndex: Int = 0,
        onEachStep: ((stepIndex: Int, result: UssdResult) -> Unit)? = null
    ): List<UssdResult> = withContext(Dispatchers.Main) {
        val appCtx = context.applicationContext
        val trimmed = steps.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return@withContext emptyList()

        val axEnabled = UssdAccessibilityService.isEnabled(appCtx)
        val preferAx = prefs.useAccessibilityUssdPin && axEnabled
        val delayMs = stepDelayMs.coerceAtLeast(200L)

        val results = mutableListOf<UssdResult>()
        fun appendAndNotify(r: UssdResult) {
            results.add(r)
            val n = initialStepIndex + results.size
            Log.d(TAG, "sendMoney: step $n -> ${r::class.simpleName}")
            onEachStep?.invoke(n, r)
        }

        // No accessibility service: only Telephony API (PIN-in-dialog won't work reliably).
        if (!axEnabled) {
            val chain = executor.sendChain(trimmed, betweenStepsMs = delayMs)
            chain.forEach { appendAndNotify(it) }
            return@withContext results
        }

        var i = 0
        while (i < trimmed.size) {
            val openerRaw = trimmed[i]
            val opener = normalizeUssd(openerRaw)
            val nextPinDigits = if (i + 1 < trimmed.size && isPinDigits(trimmed[i + 1])) {
                trimmed[i + 1]
            } else {
                null
            }
            // *220*MSISDN*AMOUNT# transfer: system popup + AX PIN when service is on.
            val isStackedSend = isStackedMoneySendOpener(opener)
            Log.d(
                TAG,
                "sendMoney: axEnabled=$axEnabled preferAx=$preferAx stackedSend=$isStackedSend " +
                    "openerLen=${opener.length} openerPreview=${opener.take(28)}"
            )
            if (isStackedSend) {
                val pinDigits = nextPinDigits ?: PlaceholderUssd.resolvePinDigitsForUssd(prefs.pin)
                Log.i(TAG, "sendMoney: stacked send → opener popup + AX PIN")
                UssdPinBridge.abortSession()
                val pinDeferred = UssdPinBridge.beginPinSession(pinDigits)
                delay(300L)
                val launched = executor.launchSystemUssdPopup(opener)
                if (!launched) {
                    UssdPinBridge.abortSession()
                    appendAndNotify(
                        UssdResult.Failure(
                            UssdResult.FAILURE_USSD_LAUNCH,
                            "failed to launch transfer USSD popup"
                        )
                    )
                    return@withContext results
                }
                val captured = withTimeoutOrNull(40_000L) { pinDeferred.await() }
                if (captured.isNullOrBlank()) {
                    UssdPinBridge.abortSession()
                    Log.w(TAG, "sendMoney: AX PIN timeout after transfer opener")
                }
                appendAndNotify(UssdResult.Message(captured.orEmpty()))
                i += if (nextPinDigits != null) 2 else 1
                if (i < trimmed.size) delay(delayMs)
                continue
            }

            appendAndNotify(executor.sendOneStep(opener))
            if (results.last() is UssdResult.Failure) return@withContext results
            i += 1
            if (i < trimmed.size) delay(delayMs)
        }
        results
    }
}
