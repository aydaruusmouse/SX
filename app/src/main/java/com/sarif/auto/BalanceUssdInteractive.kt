package com.sarif.auto

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal

/**
 * Runs balance USSD in rounds: after each reply, may send PIN again (e.g. "Invalid PIN format")
 * or a menu key until [BalanceParser] finds a positive amount or limits hit.
 */
object BalanceUssdInteractive {

    private const val TAG = "SarifAuto"
    private const val MAX_EXTRA_STEPS = 12
    /** ZAAD/modem often needs time after the first USSD text before accepting the PIN dial. */
    private const val MIN_DELAY_BEFORE_PIN_MS = 3_000L

    fun shouldSubmitPin(lastResponse: String): Boolean {
        val t = lastResponse.trim()
        if (t.isEmpty()) return false
        if (t.contains("invalid pin", ignoreCase = true)) return true
        if (!t.contains("pin", ignoreCase = true)) return false
        if (t.contains("zaad", ignoreCase = true)) return true
        return t.contains("enter", ignoreCase = true) ||
            t.contains("geli", ignoreCase = true) ||
            t.contains("fadlan", ignoreCase = true) ||
            t.contains("your pin", ignoreCase = true)
    }

    private fun logUssdOutcome(label: String, r: UssdResult) {
        when (r) {
            is UssdResult.Message ->
                Log.d(TAG, "$label: ok len=${r.text.length} preview=${r.text.take(120)}")
            is UssdResult.Failure ->
                Log.w(TAG, "$label: fail code=${r.code} msg=${r.message}")
        }
    }

    /**
     * Some modems never call back for a bare PIN; retry once with trailing `#` after timeout/failure.
     */
    private suspend fun sendPinDigitsWithRetry(
        executor: UssdExecutor,
        pinDigits: String
    ): List<UssdResult> {
        val out = mutableListOf<UssdResult>()
        var r = executor.sendOneStep(pinDigits)
        out.add(r)
        logUssdOutcome("PIN 1st (digits only)", r)
        val retry = r is UssdResult.Failure && (
            r.code == UssdResult.FAILURE_USSD_TIMEOUT ||
                r.code == TelephonyManager.USSD_RETURN_FAILURE
            )
        if (retry) {
            delay(2_500L)
            r = executor.sendOneStep(pinDigits + "#")
            out.add(r)
            logUssdOutcome("PIN 2nd (digits+#)", r)
        }
        return out
    }

    fun shouldTryMenuBalanceOption(lastResponse: String): Boolean {
        if (lastResponse.contains("invalid", ignoreCase = true)) return false
        if (lastResponse.contains("pin", ignoreCase = true) &&
            shouldSubmitPin(lastResponse)
        ) {
            return false
        }
        if (Regex("""(?m)^\s*1[\).\-\:]""").containsMatchIn(lastResponse)) return true
        if (Regex("""(?m)^\s*1\s+[-–—]""").containsMatchIn(lastResponse)) return true
        if (Regex("""(?i)\b(press|choose|select|dooro|riix)\s*1\b""").containsMatchIn(lastResponse)) {
            return true
        }
        if (Regex("""[\[\(]1[\]\)]""").containsMatchIn(lastResponse)) return true
        return false
    }

    /**
     * ZAAD *222#: after PIN, many handsets show a menu that does not match [shouldTryMenuBalanceOption]
     * (single line, OEM formatting). If we already submitted the PIN in this run and the opener was
     * *222#, send menu key **1** once.
     */
    /** Merge Telephony [UssdResult.Message] with accessibility snapshot of the same USSD session. */
    private fun mergeUssdApiAndSnapshot(apiText: String, snapshot: String?): String {
        val s = snapshot?.trim().orEmpty()
        val a = apiText.trim()
        if (s.isEmpty()) return a
        if (a.isEmpty()) return s
        if (s.contains(a, ignoreCase = true)) return s
        if (a.contains(s, ignoreCase = true)) return a
        return "$a\n$s"
    }

    private suspend fun mergeMenuStepWithAxReadIfNeeded(
        appCtx: Context,
        prefs: SecurePrefs,
        apiResult: UssdResult
    ): UssdResult {
        if (apiResult !is UssdResult.Message) return apiResult
        if (!prefs.useAccessibilityUssdPin || !UssdAccessibilityService.isEnabled(appCtx)) {
            return apiResult
        }
        UssdPinBridge.abortReadUssdTextCapture()
        val deferred = UssdPinBridge.beginReadUssdTextCapture()
        UssdAccessibilityService.scheduleReadCaptureAssisted()
        val snapshot = try {
            withTimeoutOrNull(11_000L) { deferred.await() }
        } finally {
            UssdPinBridge.abortReadUssdTextCapture()
        }
        val merged = mergeUssdApiAndSnapshot(apiResult.text, snapshot)
        Log.d(TAG, "interactive: menu key merged apiLen=${apiResult.text.length} snapLen=${snapshot?.length ?: 0} outLen=${merged.length}")
        return UssdResult.Message(merged)
    }

    private suspend fun sendMenuOne(
        appCtx: Context,
        prefs: SecurePrefs,
        executor: UssdExecutor
    ): UssdResult {
        val useAx = prefs.useAccessibilityUssdPin && UssdAccessibilityService.isEnabled(appCtx)
        if (useAx) {
            UssdPinBridge.abortSession()
            val deferred = UssdPinBridge.beginMenuSession("1")
            val captured = withTimeoutOrNull(25_000L) { deferred.await() }
            if (!captured.isNullOrBlank()) {
                Log.d(TAG, "interactive: menu key 1 sent via Accessibility len=${captured.length}")
                return UssdResult.Message(captured)
            }
            UssdPinBridge.abortSession()
            Log.w(TAG, "interactive: menu key AX timeout, falling back to TelephonyManager")
        }
        val apiMenu = executor.sendOneStep("1")
        return mergeMenuStepWithAxReadIfNeeded(appCtx, prefs, apiMenu)
    }

    private fun shouldAutoSendMenuOneAfterPin(
        lastResponse: String,
        combinedSoFar: String,
        openerSteps: List<String>
    ): Boolean {
        if (lastResponse.isBlank()) return false
        if (lastResponse.contains("invalid", ignoreCase = true)) return false
        if (shouldSubmitPin(lastResponse)) return false
        val zaadSession = combinedSoFar.contains("zaad", ignoreCase = true) ||
            openerSteps.any { it.contains("*222") }
        if (!zaadSession) return false
        return true
    }

    suspend fun runUntilBalanceParsed(
        context: Context,
        executor: UssdExecutor,
        rawTemplate: String,
        pin: String,
        recipient: String,
        stepDelayMs: Long,
        onEachStep: ((stepIndex: Int, result: UssdResult) -> Unit)? = null
    ): List<UssdResult> {
        val appCtx = context.applicationContext
        val prefs = SecurePrefs(appCtx)
        val pinDigits = PlaceholderUssd.resolvePinDigitsForUssd(pin)
        val expanded = PlaceholderUssd.expandBalanceSteps(rawTemplate, pin, recipient)
        if (expanded.isEmpty()) return emptyList()

        val results = mutableListOf<UssdResult>()

        fun combinedText(): String = results.joinToString("\n") { r ->
            when (r) {
                is UssdResult.Message -> r.text
                is UssdResult.Failure -> "FAIL:${r.code}:${r.message}"
            }
        }

        fun appendAndNotify(r: UssdResult) {
            results.add(r)
            val n = results.size
            Log.d(TAG, "interactive: step $n -> ${r::class.simpleName}")
            onEachStep?.invoke(n, r)
        }

        val delayMs = stepDelayMs.coerceAtLeast(200L)
        suspend fun delayStep() = delay(delayMs)
        var submittedPinThisRun = false
        var sentMenuOneAfterPin = false

        if (expanded.size >= 2) {
            val chain = executor.sendChain(expanded, delayMs)
            chain.forEach { appendAndNotify(it) }
            if (chain.any { it is UssdResult.Failure }) return results
        } else {
            val singleOpener = expanded[0]
            val useAxForOpen = prefs.useAccessibilityUssdPin &&
                UssdAccessibilityService.isEnabled(appCtx)
            if (useAxForOpen) {
                Log.i(TAG, "interactive: opener via system USSD popup + immediate AX PIN")
                UssdPinBridge.abortSession()
                val pinDeferred = UssdPinBridge.beginPinSession(pinDigits)
                val launched = executor.launchSystemUssdPopup(singleOpener)
                if (!launched) {
                    UssdPinBridge.abortSession()
                    appendAndNotify(
                        UssdResult.Failure(
                            UssdResult.FAILURE_USSD_LAUNCH,
                            "failed to launch system USSD popup"
                        )
                    )
                    return results
                }

                val afterPinText = withTimeoutOrNull(36_000L) { pinDeferred.await() }
                if (afterPinText.isNullOrBlank()) {
                    Log.w(TAG, "interactive: AX PIN session timed out, falling back to read capture")
                    UssdPinBridge.abortSession()
                    UssdPinBridge.abortReadUssdTextCapture()
                    val deferred = UssdPinBridge.beginReadUssdTextCapture()
                    UssdAccessibilityService.scheduleReadCaptureAssisted()
                    val openerText = try {
                        withTimeoutOrNull(14_000L) { deferred.await() }
                    } finally {
                        UssdPinBridge.abortReadUssdTextCapture()
                    }
                    appendAndNotify(UssdResult.Message(openerText.orEmpty()))
                } else {
                    appendAndNotify(UssdResult.Message(afterPinText))
                    submittedPinThisRun = true
                }

                // Some devices/networks accept the PIN but do not provide immediate capture text.
                // For *222 sessions, force-send menu key 1 right after the PIN attempt.
                if (!sentMenuOneAfterPin && singleOpener.contains("*222")) {
                    Log.d(TAG, "interactive: forced menu key 1 after opener PIN stage")
                    appendAndNotify(sendMenuOne(appCtx, prefs, executor))
                    sentMenuOneAfterPin = true
                }
                delay(maxOf(delayMs, MIN_DELAY_BEFORE_PIN_MS))
            } else {
                appendAndNotify(executor.sendOneStep(singleOpener))
                delay(maxOf(delayMs, MIN_DELAY_BEFORE_PIN_MS))
            }
        }

        var extra = 0
        while (extra < MAX_EXTRA_STEPS) {
            val parsed = BalanceParser.parseLargestPositiveAmount(combinedText())
            if (parsed != null && parsed > BigDecimal.ZERO) {
                Log.d(TAG, "interactive: balance parsed=$parsed")
                return results
            }

            val last = results.lastOrNull() ?: break
            if (last is UssdResult.Failure) break

            val lastText = (last as UssdResult.Message).text
            when {
                shouldSubmitPin(lastText) -> {
                    val useAx = prefs.useAccessibilityUssdPin &&
                        UssdAccessibilityService.isEnabled(appCtx)
                    if (useAx) {
                        Log.i(TAG, "interactive: PIN via Accessibility (system USSD dialog)")
                        UssdPinBridge.abortSession()
                        val deferred = UssdPinBridge.beginPinSession(pinDigits)
                        delay(500L)
                        val captured = withTimeoutOrNull(28_000L) { deferred.await() }
                        if (captured == null) {
                            UssdPinBridge.abortSession()
                            Log.w(TAG, "interactive: Accessibility PIN timeout")
                        }
                        appendAndNotify(UssdResult.Message(captured.orEmpty()))
                        submittedPinThisRun = true
                        extra++
                    } else {
                        if (prefs.useAccessibilityUssdPin) {
                            Log.w(
                                TAG,
                                "interactive: Accessibility enabled in app but service off — " +
                                    "using TelephonyManager (often no PIN callback)"
                            )
                        }
                        Log.d(TAG, "interactive: PIN via TelephonyManager len=${pinDigits.length}")
                        val pinRound = sendPinDigitsWithRetry(executor, pinDigits)
                        pinRound.forEach { appendAndNotify(it) }
                        extra += pinRound.size
                        submittedPinThisRun = true
                    }
                    delayStep()
                }
                shouldTryMenuBalanceOption(lastText) ||
                    (
                        !sentMenuOneAfterPin && submittedPinThisRun &&
                            shouldAutoSendMenuOneAfterPin(lastText, combinedText(), expanded)
                        ) -> {
                    val explicitMenu = shouldTryMenuBalanceOption(lastText)
                    Log.d(
                        TAG,
                        "interactive: sending menu key 1 " +
                            "(numberedMenu=$explicitMenu, postPinFallback=${!explicitMenu && submittedPinThisRun})"
                    )
                    appendAndNotify(sendMenuOne(appCtx, prefs, executor))
                    sentMenuOneAfterPin = true
                    extra++
                    delayStep()
                }
                else -> {
                    Log.d(TAG, "interactive: stopping (no follow-up rule)")
                    break
                }
            }
        }

        return results
    }
}
