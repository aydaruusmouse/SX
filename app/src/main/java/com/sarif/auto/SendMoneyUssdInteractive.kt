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

    /** `*220*…#` stacked transfer or `*800#` menu transfer — popup opener + AX service PIN. */
    private fun isAxPopupFirstSendStep(s: String): Boolean {
        return PlaceholderUssd.isAxPopupPinSendOpener(normalizeUssd(s))
    }

    private fun isPinDigits(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && t.all { it.isDigit() } && t.length in 4..12
    }

    /**
     * Steps Telephony cannot send mid-session (menu keys, amounts) — must use Accessibility
     * when continuing a *800# / *220*…# session.
     */
    fun isBareInSessionSendStep(step: String): Boolean = shouldInjectSendFollowUpViaAccessibility(step)

    /**
     * After *800# / *220*…# + AX PIN, Telephony [TelephonyManager.sendUssdRequest] cannot send
     * in-session menu keys ("5", "2") on many devices — use Accessibility instead.
     */
    /**
     * Same balance summary + "OK" after a menu digit means we never left the old USSD session
     * (common when the balance overlay was still open).
     */
    private fun looksLikeStaleZaadBalanceOkScreen(text: String): Boolean {
        val t = text.lowercase()
        if (t.length > 900) return false
        if (!t.contains("ok")) return false
        if (!t.contains("hadhaag") && !t.contains("xisaab")) return false
        if (t.contains("dooro") && t.contains("adeega")) return false
        if (t.contains("fadlan") && t.contains("lacag")) return false
        if (t.contains("geli") || t.contains("gali")) return false
        if (t.contains("maclum") || t.contains("macluum")) return false
        return true
    }

    private fun shouldInjectSendFollowUpViaAccessibility(step: String): Boolean {
        val t = step.trim()
        if (t.isEmpty()) return false
        val c0 = t[0]
        if (c0 == '*' || c0 == '#') return false
        if (t.all { it.isDigit() }) return true
        return t.matches(Regex("""^\d+\.\d{1,6}$"""))
    }

    private suspend fun sendStepThroughAccessibility(
        appCtx: Context,
        step: String,
        staleMenuRetry: Int = 0
    ): UssdResult {
        val t = step.trim()
        UssdPinBridge.abortSession()
        UssdAccessibilityService.prepareArmedInjectSession()
        // 3+ digits: service/bank secrets; 4+ is typical — 3-digit bank BIN must use PIN path, not menu.
        val isPinStep = t.length in 3..12 && t.all { it.isDigit() }
        val deferred = if (isPinStep) {
            UssdPinBridge.beginPinSession(t)
        } else {
            UssdPinBridge.beginMenuSession(t)
        }
        UssdAccessibilityService.scheduleArmedInjectKicks()
        val pinDelayMs = when {
            !isPinStep -> 95L
            UssdPinBridge.isFastTransferCapture() && t.length >= 6 -> 240L
            UssdPinBridge.isFastTransferCapture() -> 180L
            else -> 140L
        }
        delay(pinDelayMs)
        val captured = withTimeoutOrNull(32_000L) { deferred.await() }
        if (captured.isNullOrBlank()) {
            UssdPinBridge.abortSession()
            Log.w(TAG, "sendMoney: AX follow-up timeout stepLen=${t.length}")
            return UssdResult.Failure(-6, "accessibility follow-up timeout")
        }
        val singleMenuDigit = t.length == 1 && t[0].isDigit()
        if (singleMenuDigit && staleMenuRetry < 1 && looksLikeStaleZaadBalanceOkScreen(captured)) {
            Log.w(TAG, "sendMoney: menu digit produced stale balance screen — BACK + retry once")
            UssdPinBridge.abortSession()
            UssdAccessibilityService.forceDismissDialog()
            delay(750L)
            return sendStepThroughAccessibility(appCtx, step, staleMenuRetry + 1)
        }
        if (BalanceParser.isHardFailure(captured)) {
            UssdPinBridge.abortSession()
            UssdAccessibilityService.forceDismissDialog()
        }
        return UssdResult.Message(captured)
    }

    suspend fun runSendChain(
        context: Context,
        executor: UssdExecutor,
        steps: List<String>,
        stepDelayMs: Long,
        prefs: SecurePrefs,
        initialStepIndex: Int = 0,
        onEachStep: ((stepIndex: Int, result: UssdResult) -> Unit)? = null,
        /**
         * When balance was already *800# / *888# and send template starts with menu keys (no new
         * `*800#` line), Telephony must not be used for those keys — same USSD session continues.
         */
        assumeAccessibilityMenuFollowUps: Boolean = false
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

        val fastTransfer = preferAx
        if (fastTransfer) UssdPinBridge.setFastTransferCapture(true)
        try {
            var i = 0
            /** True after *800# / *220*…# popup + successful AX PIN — remaining steps use AX, not Telephony. */
            var useAccessibilityFollowUps = assumeAccessibilityMenuFollowUps
            fun betweenStepPause(): Long = if (useAccessibilityFollowUps) {
                kotlin.math.max(260L, kotlin.math.min(delayMs, 380L))
            } else {
                delayMs
            }
            while (i < trimmed.size) {
                val openerRaw = trimmed[i]
                val opener = normalizeUssd(openerRaw)
                val nextPinDigits = if (i + 1 < trimmed.size && isPinDigits(trimmed[i + 1])) {
                    trimmed[i + 1]
                } else {
                    null
                }
                val axPopupOpener = isAxPopupFirstSendStep(opener)
                Log.d(
                    TAG,
                    "sendMoney: axEnabled=$axEnabled preferAx=$preferAx axPopupOpener=$axPopupOpener " +
                        "openerLen=${opener.length} openerPreview=${opener.take(28)}"
                )
                if (axPopupOpener) {
                    val pinDigits = nextPinDigits ?: PlaceholderUssd.resolvePinDigitsForUssd(prefs.pin)
                    Log.i(TAG, "sendMoney: popup opener → AX service PIN, then menu/stacked follow-up")
                    UssdPinBridge.abortSession()
                    val pinDeferred = UssdPinBridge.beginPinSession(pinDigits)
                    delay(if (fastTransfer) 220L else 300L)
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
                        appendAndNotify(
                            UssdResult.Failure(-7, "PIN timeout after transfer USSD opener")
                        )
                        return@withContext results
                    } else if (BalanceParser.isHardFailure(captured)) {
                        Log.w(TAG, "sendMoney: hard failure detected on opener, aborting transfer")
                        UssdAccessibilityService.forceDismissDialog()
                        appendAndNotify(UssdResult.Message(captured))
                        return@withContext results
                    } else {
                        useAccessibilityFollowUps = preferAx
                    }
                    appendAndNotify(UssdResult.Message(captured.orEmpty()))
                    i += if (nextPinDigits != null) 2 else 1
                    if (i < trimmed.size) delay(betweenStepPause())
                    continue
                }

                val followUp = useAccessibilityFollowUps && preferAx &&
                    shouldInjectSendFollowUpViaAccessibility(opener)
                Log.d(TAG, "sendMoney: step followUpAx=$followUp preview=${opener.take(24)}")
                val stepResult = if (followUp) {
                    sendStepThroughAccessibility(appCtx, opener)
                } else {
                    executor.sendOneStep(opener)
                }
                appendAndNotify(stepResult)
                val res = results.last()
                if (res is UssdResult.Failure) return@withContext results
                if (res is UssdResult.Message && BalanceParser.isHardFailure(res.text)) {
                    Log.w(TAG, "sendMoney: hard failure detected in step, aborting transfer")
                    UssdAccessibilityService.forceDismissDialog()
                    return@withContext results
                }
                i += 1
                if (i < trimmed.size) delay(betweenStepPause())
            }
        } finally {
            if (fastTransfer) UssdPinBridge.setFastTransferCapture(false)
        }
        results
    }
}
