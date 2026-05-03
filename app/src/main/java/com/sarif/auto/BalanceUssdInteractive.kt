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

    /**
     * Balance openers that share the *222#-style flow: after AX PIN, may need forced menu **1**,
     * and a shorter post-open pause when the opener runs via Accessibility.
     */
    private fun isZaadStyleBalanceOpener(line: String): Boolean =
        line.contains("*222") || line.contains("*880") || line.contains("*800") || line.contains("*888")

    /** *800# / *888#: send menu **1** immediately after PIN (no extra second-scale waits). */
    private fun isQuickBalanceMenuOneOpener(line: String): Boolean =
        line.contains("*800") || line.contains("*888")

    /** Carrier said the PIN attempt was wrong or malformed — do not auto-send menu "1" on top of it. */
    private fun carrierRejectedPinMessage(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("invalid pin") ||
            t.contains("pin format") ||
            t.contains("wrong pin") ||
            t.contains("incorrect pin")
    }

    /** Accessibility dumped MainActivity (disclaimer) instead of the system USSD dialog. */
    private fun looksLikeCapturedAppDisclaimer(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("ussd behavior depends") && t.contains("stacked code per step")
    }

    /**
     * Opener already failed at modem/carrier — follow-up menu keys usually make things worse
     * (extra USSD while session is bad → AURTimeout / loading).
     */


    fun shouldSubmitPin(lastResponse: String): Boolean {
        val t = lastResponse.trim()
        if (t.isEmpty()) return false
        if (t.contains("invalid pin", ignoreCase = true)) return true
        if (!t.contains("pin", ignoreCase = true)) return false
        if (t.contains("zaad", ignoreCase = true)) return true
        return t.contains("enter", ignoreCase = true) ||
            t.contains("geli", ignoreCase = true) ||
            t.contains("gali", ignoreCase = true) ||
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
        if (lastResponse.contains("select valid", ignoreCase = true)) return false
        if (lastResponse.contains("valid option", ignoreCase = true)) return false
        if (BalanceParser.isHardFailure(lastResponse)) return false
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
     * ZAAD *222# / *880# / *800#: after PIN, many handsets show a menu that does not match
     * [shouldTryMenuBalanceOption] (single line, OEM formatting). If we already submitted the PIN in
     * this run and the opener was one of those codes, send menu key **1** once.
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
        executor: UssdExecutor,
        quickSend: Boolean = false
    ): UssdResult {
        // *800# / *888#: still wait briefly so the USSD layer shows the menu before injecting "1"
        // (immediate inject often hits "invalid menu / select valid option").
        val preAwait = if (quickSend) 240L else 450L
        val retryPauseLong = if (quickSend) 380L else 1_400L
        val retryPauseShort = if (quickSend) 160L else 350L
        val useAx = prefs.useAccessibilityUssdPin && UssdAccessibilityService.isEnabled(appCtx)
        if (useAx) {
            UssdAccessibilityService.prepareArmedInjectSession()
            UssdPinBridge.abortSession()
            val deferred = UssdPinBridge.beginMenuSession("1")
            UssdAccessibilityService.scheduleArmedInjectKicks()
            // *222# / *880#: brief pause so IME does not steal USSD; *800# / *888#: shorter settle (see preAwait).
            if (preAwait > 0L) delay(preAwait)
            var captured = withTimeoutOrNull(22_000L) { deferred.await() }
            if (captured.isNullOrBlank()) {
                Log.w(TAG, "interactive: menu key AX timeout, retry after pause (focus may have left USSD)")
                UssdPinBridge.abortSession()
                delay(retryPauseLong)
                UssdAccessibilityService.prepareArmedInjectSession()
                val deferred2 = UssdPinBridge.beginMenuSession("1")
                UssdAccessibilityService.scheduleArmedInjectKicks()
                delay(retryPauseShort)
                captured = withTimeoutOrNull(18_000L) { deferred2.await() }
            }
            if (!captured.isNullOrBlank()) {
                Log.d(TAG, "interactive: menu key 1 sent via Accessibility len=${captured.length}")
                return UssdResult.Message(captured)
            }
            UssdPinBridge.abortSession()
            Log.w(TAG, "interactive: menu key AX failed after retry, falling back to TelephonyManager")
        }
        val apiMenu = executor.sendOneStep("1")
        return mergeMenuStepWithAxReadIfNeeded(appCtx, prefs, apiMenu)
    }

    /**
     * On *800# / *888#, do not fire menu "1" until the dialog looks like the ZAAD service picker;
     * otherwise the carrier often returns "invalid menu / select valid option".
     */
    private fun shouldOfferBalanceMenuOneAfterPinReply(openReply: String): Boolean {
        val t = openReply.trim()
        if (t.isEmpty()) return true
        val low = t.lowercase()
        if (low.contains("invalid menu") || low.contains("invalid option")) return false
        if (low.contains("select valid") || low.contains("valid option")) return false
        if (BalanceParser.isHardFailure(openReply)) return false
        // Already on a balance / account line — "1" is wrong and triggers invalid menu.
        if (Regex("""(?i)hadhaageedu|hadhaagaagu|hadhaaga\s+waa|xisaabtaada""").containsMatchIn(openReply)) {
            return false
        }
        return BalanceParser.looksLikeZaadDooroServiceMenuOnly(openReply) ||
            shouldTryMenuBalanceOption(openReply)
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
            openerSteps.any { isZaadStyleBalanceOpener(it) }
        if (!zaadSession) return false
        if (openerSteps.any { isQuickBalanceMenuOneOpener(it) } &&
            !shouldOfferBalanceMenuOneAfterPinReply(lastResponse)
        ) {
            return false
        }
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
        val quickMenuOne =
            expanded.size == 1 && isQuickBalanceMenuOneOpener(expanded[0])

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
                UssdAccessibilityService.prepareArmedInjectSession()
                UssdPinBridge.abortSession()
                val pinDeferred = UssdPinBridge.beginPinSession(pinDigits)
                UssdAccessibilityService.scheduleArmedInjectKicks()
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
                    if (!carrierRejectedPinMessage(afterPinText) &&
                        !looksLikeCapturedAppDisclaimer(afterPinText)
                    ) {
                        submittedPinThisRun = true
                    }
                }

                // For *222# / *880# / *800# / *888#, some handsets need menu "1" after PIN; others already show balance.
                // Skip when [BalanceParser] already sees an amount (avoids redundant USSD) or when the
                // opener text is modem/carrier failure (avoid stacking sessions → AURTimeout).
                if (!sentMenuOneAfterPin && isZaadStyleBalanceOpener(singleOpener)) {
                    val openReply = (results.lastOrNull() as? UssdResult.Message)?.text.orEmpty()
                    val haveBalanceAlready = BalanceParser.parseLargestPositiveAmount(combinedText())
                        ?.let { it > BigDecimal.ZERO } == true
                    when {
                        haveBalanceAlready -> {
                            Log.d(
                                TAG,
                                "interactive: skip forced menu 1 — balance already in session after opener"
                            )
                        }
                        carrierRejectedPinMessage(openReply) -> {
                            Log.w(
                                TAG,
                                "interactive: skip forced menu 1 — carrier PIN error; re-check PIN in app settings"
                            )
                        }
                        looksLikeCapturedAppDisclaimer(openReply) -> {
                            Log.w(
                                TAG,
                                "interactive: skip forced menu 1 — captured app screen text, not USSD dialog"
                            )
                        }
                        BalanceParser.isHardFailure(openReply) -> {
                            Log.w(
                                TAG,
                                "interactive: skip forced menu 1 — opener shows modem/carrier failure"
                            )
                            UssdAccessibilityService.forceDismissDialog()
                        }
                        else -> {
                            val offerForcedMenuOne =
                                !quickMenuOne || shouldOfferBalanceMenuOneAfterPinReply(openReply)
                            if (offerForcedMenuOne) {
                                Log.d(TAG, "interactive: forced menu key 1 after opener PIN stage")
                                appendAndNotify(sendMenuOne(appCtx, prefs, executor, quickSend = quickMenuOne))
                                sentMenuOneAfterPin = true
                            } else {
                                Log.d(
                                    TAG,
                                    "interactive: defer menu 1 (*800/*888) — " +
                                        "PIN reply not picker-shaped; refresh USSD text then loop"
                                )
                                delay(450L)
                                UssdPinBridge.abortReadUssdTextCapture()
                                val readDef = UssdPinBridge.beginReadUssdTextCapture()
                                UssdAccessibilityService.scheduleReadCaptureAssisted()
                                val refreshed = try {
                                    withTimeoutOrNull(12_000L) { readDef.await() }.orEmpty()
                                } finally {
                                    UssdPinBridge.abortReadUssdTextCapture()
                                }
                                if (refreshed.isNotBlank() && refreshed.trim() != openReply.trim()) {
                                    appendAndNotify(UssdResult.Message(refreshed))
                                }
                            }
                        }
                    }
                }
                // *800# / *888#: no extra pause before the extra-step loop (menu 1 already races to second screen).
                val postOpenPauseMs = when {
                    useAxForOpen && quickMenuOne -> 0L
                    useAxForOpen && isZaadStyleBalanceOpener(singleOpener) ->
                        delayMs.coerceIn(150L..400L)
                    else -> maxOf(delayMs, MIN_DELAY_BEFORE_PIN_MS)
                }
                delay(postOpenPauseMs)
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
            if (last is UssdResult.Failure) {
                val recoverable = BalanceParser.parseLargestPositiveAmount(combinedText())
                if (recoverable != null && recoverable > BigDecimal.ZERO) {
                    Log.d(
                        TAG,
                        "interactive: trailing Failure ignored — balance already in session ($recoverable)"
                    )
                    return results
                }
                break
            }

            val lastText = (last as UssdResult.Message).text
            if (BalanceParser.isHardFailure(lastText)) {
                Log.w(TAG, "interactive: hard failure detected in loop, aborting")
                UssdAccessibilityService.forceDismissDialog()
                break
            }

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
                    appendAndNotify(sendMenuOne(appCtx, prefs, executor, quickSend = quickMenuOne))
                    sentMenuOneAfterPin = true
                    extra++
                    if (!quickMenuOne) delayStep()
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
