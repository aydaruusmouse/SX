package com.sarif.auto

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
/**
 * Enters the PIN into the **system USSD dialog** when [UssdPinBridge] is armed.
 */
class UssdAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var injecting = false
    private var consecutiveFillNoClick = 0
    private var lastFailureElapsed = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        UssdPinBridge.abortReadUssdTextCapture()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val pin = UssdPinBridge.armedPin
        val menuKey = UssdPinBridge.armedMenuKey
        val inputToInject = pin ?: menuKey
        if (inputToInject == null) {
            if (UssdPinBridge.hasReadCapturePending()) {
                tickReadCaptureSnapshot()
            }
            return
        }
        tryInjectArmedInput(pin, menuKey, inputToInject)
    }

    /**
     * Runs one inject attempt (same as [onAccessibilityEvent] when armed). Used on events and on
     * timed kicks so menu “1” is not blocked waiting for the next slow OEM accessibility event.
     */
    private fun tryInjectArmedInput(
        pin: String?,
        menuKey: String?,
        inputToInject: String
    ) {
        if (injecting) return
        if (UssdPinBridge.sessionAgeMs() > SESSION_MAX_MS) {
            UssdPinBridge.abortSession()
            consecutiveFillNoClick = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        val debounceMs = if (menuKey != null) 55L else 400L
        if (consecutiveFillNoClick > 0 && now - lastFailureElapsed < debounceMs) return

        val roots = collectCandidateRoots()
        for (root in roots) {
            try {
                val pkg = root.packageName?.toString().orEmpty()
                val blob = dumpNodeTexts(root)
                if (!isLikelyUssdWindow(pkg, blob)) continue
                if (pin != null && !looksLikeArmedPinEntryScreen(blob)) continue
                val fillText = when {
                    pin != null -> pin
                    menuKey != null -> {
                        val pref = UssdPinBridge.sendChainPreferredAmountDigits
                        if (looksLikeAmountEntryPrompt(blob) && !pref.isNullOrBlank()) {
                            if (pref != menuKey) {
                                Log.d(TAG, "USSD AX: lacagta screen — inject $pref not template $menuKey")
                            }
                            pref
                        } else {
                            menuKey
                        }
                    }
                    else -> inputToInject
                }
                if (menuKey != null && !looksLikeUssdMenuPrompt(blob) && !looksLikeAmountEntryPrompt(blob) &&
                    !looksLikeMiscInfoEntryPrompt(blob)
                ) {
                    // Bank sirta screen: menu path cannot inject. Skip (a) short amount leftovers 12–9999,
                    // (b) spurious "1" — template "1" is often for maclumadka, but if UI is already bank
                    // PIN, typing 1 blocks {BANK_PIN}. Maclumad uses looksLikeMiscInfoEntryPrompt, not bank.
                    if (looksLikeBankSecretNumberPrompt(blob) &&
                        menuKey.isNotEmpty() &&
                        menuKey.all { it.isDigit() } &&
                        (menuKey.length in 2..4 || menuKey == "1")
                    ) {
                        Log.w(
                            TAG,
                            "USSD AX: bank sirta screen but armed menuKey=$menuKey — skip inject, advance to next step"
                        )
                        consecutiveFillNoClick = 0
                        val fast = UssdPinBridge.isFastTransferCapture()
                        mainHandler.postDelayed({
                            val merged = capturePhoneOrActiveWindowText().ifBlank { blob }
                            Log.d(TAG, "USSD AX: skip-inject capture len=${merged.length} preview=${merged.take(160)}")
                            UssdPinBridge.finishWithCapturedText(merged.ifBlank { null })
                        }, if (fast) 100L else 160L)
                        return
                    }
                    continue
                }

                injecting = true
                val wasMenuOnly = menuKey != null
                val filled = fillInput(root, fillText, preferPlainFieldForMenu = wasMenuOnly)
                val clicked = if (filled) clickConfirm(root) else false
                if (!filled || !clicked) {
                    Log.w(TAG, "USSD AX: pkg=$pkg fill=$filled click=$clicked (fail streak=$consecutiveFillNoClick)")
                    consecutiveFillNoClick++
                    lastFailureElapsed = SystemClock.elapsedRealtime()
                    injecting = false
                    if (consecutiveFillNoClick >= MAX_FAIL_STREAK) {
                        Log.e(TAG, "USSD AX: abort after $MAX_FAIL_STREAK failed inject attempts")
                        consecutiveFillNoClick = 0
                        UssdPinBridge.finishWithCapturedText(null)
                    }
                    continue
                }

                consecutiveFillNoClick = 0
                UssdPinBridge.clearArmedInput()
                // Release before capture delays: PIN session can complete and [BalanceUssdInteractive]
                // arms menu "1" immediately; kicks must not hit `if (injecting) return` during 420ms+ waits.
                injecting = false
                val fastXfer = UssdPinBridge.isFastTransferCapture()
                val delay1 = when {
                    fastXfer && wasMenuOnly -> 140L
                    wasMenuOnly -> CAPTURE_DELAY_MENU_MS
                    fastXfer -> 220L
                    else -> CAPTURE_DELAY_AFTER_PIN_MS
                }
                val delay2 = when {
                    fastXfer && wasMenuOnly -> 120L
                    wasMenuOnly -> CAPTURE_SECOND_MENU_MS
                    fastXfer -> 240L
                    else -> CAPTURE_SECOND_PASS_MS
                }
                val thirdMenuMs = if (fastXfer && wasMenuOnly) 180L else CAPTURE_THIRD_MENU_MS
                mainHandler.postDelayed({
                    val first = capturePhoneOrActiveWindowText()
                    fun deliverOrRetryCapture(merged: String, attempt: Int) {
                        if (isPlausibleUssdCapture(merged) || attempt >= 5) {
                            if (!isPlausibleUssdCapture(merged)) {
                                Log.w(TAG, "USSD AX: weak capture after retries attempt=$attempt len=${merged.length}")
                            }
                            Log.d(TAG, "USSD AX: captured len=${merged.length} preview=${merged.take(200)}")
                            UssdPinBridge.finishWithCapturedText(merged.ifBlank { null })
                            return
                        }
                        val wait = (350L + 180L * attempt).coerceAtMost(1000L)
                        Log.w(TAG, "USSD AX: implausible capture (launcher/widget?) retry in ${wait}ms")
                        mainHandler.postDelayed({
                            val snap = capturePhoneOrActiveWindowText()
                            deliverOrRetryCapture(selectBetterUssdCapture(merged, snap), attempt + 1)
                        }, wait)
                    }
                    // After PIN, ZAAD often shows “Dooro Adeega” menu quickly — avoid full 900+650ms wait.
                    val fastMenu = !wasMenuOnly && looksLikePostPinServiceMenu(first)
                    if (fastMenu) {
                        Log.d(TAG, "USSD AX: fast capture after PIN (service menu)")
                        mainHandler.postDelayed({
                            val second = capturePhoneOrActiveWindowText()
                            val merged = selectBetterUssdCapture(first, second)
                            deliverOrRetryCapture(merged, 0)
                        }, CAPTURE_FAST_PIN_MENU_SECOND_MS)
                    } else {
                        mainHandler.postDelayed({
                            val second = capturePhoneOrActiveWindowText()
                            var merged = selectBetterUssdCapture(first, second)
                            if (wasMenuOnly) {
                                // Samsung: balance line can appear after IME/focus settles — one more read.
                                mainHandler.postDelayed({
                                    val third = capturePhoneOrActiveWindowText()
                                    merged = selectBetterUssdCapture(merged, third)
                                    deliverOrRetryCapture(merged, 0)
                                }, thirdMenuMs)
                            } else {
                                deliverOrRetryCapture(merged, 0)
                            }
                        }, delay2)
                    }
                }, delay1)
                return
            } finally {
                root.recycle()
            }
        }
    }

    private fun resetInjectDebouncingForNewSession() {
        consecutiveFillNoClick = 0
        lastFailureElapsed = 0L
    }

    /** True when the focused window is our UI — global BACK would call Activity.onBackPressed and exit. */
    private fun isOurAppFocusedWindow(): Boolean {
        val active = rootInActiveWindow ?: return false
        try {
            return active.packageName?.toString().equals(packageName, ignoreCase = true)
        } finally {
            active.recycle()
        }
    }

    private fun tickReadCaptureSnapshot() {
        if (!UssdPinBridge.hasReadCapturePending()) return
        val blob = capturePhoneOrActiveWindowText()
        if (blob.isNotBlank()) {
            UssdPinBridge.offerReadCaptureCandidate(blob)
            Log.d(TAG, "USSD AX: read-capture tick len=${blob.length} preview=${blob.take(120)}")
        }
    }

    private fun finalizeReadCaptureSnapshot() {
        if (!UssdPinBridge.hasReadCapturePending()) return
        tickReadCaptureSnapshot()
        UssdPinBridge.finalizeReadUssdTextCaptureFromAccessibility()
    }

    private fun collectCandidateRoots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows
            if (wins != null && wins.isNotEmpty()) {
                val scored = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
                for (w in wins) {
                    val r = w.root ?: continue
                    val pkg = r.packageName?.toString().orEmpty()
                    scored.add(phoneWindowSortScore(pkg) to r)
                }
                scored.sortByDescending { it.first }
                for ((_, r) in scored) list.add(r)
            }
        }
        if (list.isEmpty()) {
            rootInActiveWindow?.let { list.add(it) }
        }
        return list
    }

    /** Prefer system phone / telecom layers so we do not pick [packageName] first. */
    private fun phoneWindowSortScore(pkg: String): Int {
        val p = pkg.lowercase()
        return when {
            p.contains("android.phone") -> 100
            p.contains("server.telecom") -> 95
            p.contains("incallui") -> 90
            p.contains("smart.caller") -> 85
            p.contains("dialer") -> 80
            p.contains("transsion") && (p.contains("call") || p.contains("phone")) -> 75
            p.contains("mediatek") && p.contains("telephony") -> 70
            else -> 0
        }
    }

    /**
     * USSD runs in the phone stack. Never treat our own UI as USSD: the disclaimer contains
     * the word "USSD", which previously matched and drove "Start monitor" clicks.
     */
    private fun isLikelyUssdWindow(pkg: String, blob: String): Boolean {
        if (pkg.equals(packageName, ignoreCase = true)) return false
        val p = pkg.lowercase()
        if (p.contains("android.phone")) return true
        if (p.contains("server.telecom")) return true
        if (p.contains("incallui")) return true
        if (p.contains("dialer")) return true
        if (p.contains("smart.caller")) return true
        if (p.contains("transsion") && (p.contains("call") || p.contains("phone"))) return true
        if (p.contains("mediatek") && p.contains("telephony")) return true
        if (p.contains("samsung") && (p.contains("phone") || p.contains("call") || p.contains("telecom") ||
                p.contains("telephonyui") || p.contains("incallui"))) return true
        // Odd OEM pkg: only if text is clearly carrier USSD (not generic "USSD" help copy)
        return blobLooksLikeCarrierPinScreen(blob) ||
            blobLooksLikeCarrierUssdSnapshot(blob) ||
            looksLikeAmountEntryPrompt(blob) ||
            looksLikeMiscInfoEntryPrompt(blob) ||
            blobLooksLikeLenientUssdOverlay(blob)
    }

    /** USSD copy often uses “gali” (not “geli”), e.g. “Fadlan gali pinka” (bank PIN). */
    private fun blobHasSomaliEnterVerb(b: String): Boolean =
        b.contains("geli") || b.contains("gali")

    private fun blobLooksLikeCarrierPinScreen(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("zaad") || b.contains("[-zaad")) return true
        if (looksLikeBankSecretNumberPrompt(blob)) return true
        if (blobHasSomaliEnterVerb(b) && (b.contains("pin") || b.contains("pink"))) return true
        if (b.contains("fadlan") && (b.contains("pin") || b.contains("pink"))) return true
        if (b.contains("enter your pin")) return true
        return false
    }

    /** True when dump is Samsung launcher, Bixby widget strip, or dial-pad chrome — not USSD. */
    private fun looksLikeNonUssdPhoneChrome(blob: String): Boolean {
        val b = blob.lowercase()
        // Launcher pager without carrier text (USSD never uses this copy).
        if (b.contains("page 1 of 2") && !hasCarrierUssdAnchor(blob)) return true
        if (b.contains("page 1 of 2") && (b.contains("widget") || b.contains("weather") || b.contains("camera"))) {
            return true
        }
        if (b.contains("current weather") && b.contains("forecast")) return true
        if (b.contains("today widget") || b.contains("today, thursday") || b.contains("today, friday")) return true
        if (b.contains("partly cloudy") || b.contains("rain likely tomorrow")) return true
        if (b.contains("more options") && b.contains("voicemail") && b.contains("search")) return true
        if (b.contains("asterisk") && b.contains("wxyz") && b.contains("voicemail")) return true
        return false
    }

    /**
     * Sarif / ZAAD flash receipt in the USSD stack (e.g. “Tixraac… Waxaad … u sariftay SLSH…”).
     * Not an interactive step — must not score above “Fadlan Geli lacagta” or the amount is skipped.
     */
    private fun looksLikeFlashTransferReceipt(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("ayaad u dirtay") || b.contains("ka heshay")) return true
        if (b.contains("waxaad slsh")) return true
        if (b.contains("waxaad") && b.contains("sariftay")) return true
        if (b.contains("tixraac") && (b.contains("sariftay") || b.contains("waxaad"))) return true
        return false
    }

    /** Somali/Telesom USSD almost always mentions service or money; launcher dumps do not. */
    private fun hasCarrierUssdAnchor(blob: String): Boolean {
        if (looksLikeFlashTransferReceipt(blob)) return true
        val b = blob.lowercase()
        return b.contains("zaad") || b.contains("sarifka") || b.contains("adeeg") ||
            b.contains("hadhaag") || b.contains("xisaab") || b.contains("dooro") ||
            b.contains("itus hadhaaga") || b.contains("lacag") || b.contains("geli") ||
            b.contains("gali") || b.contains("fadlan") || b.contains("dara-salaam") ||
            b.contains("maclum") || b.contains("macluum") || b.contains("bank") ||
            Regex("""\$[\d,.]+""").containsMatchIn(blob) ||
            Regex("""(?i)slsh\s*[\d,]+""").containsMatchIn(blob)
    }

    /** Prefer dialogs that look like live USSD over stale balance-only dumps when several phone windows exist. */
    private fun ussdBlobPriorityScore(blob: String): Int {
        if (blob.isBlank()) return 0
        if (blobContainsAppDisclaimerJunk(blob)) return 0
        if (looksLikeNonUssdPhoneChrome(blob)) return 0
        // Success receipt must be capturable (focus often returns to our app here). Keep below
        // interactive prompts (95) so "Fadlan Geli lacagta" still wins when both appear in dumps.
        if (looksLikeFlashTransferReceipt(blob)) return 84
        if (blobLooksLikeCarrierPinScreen(blob)) return 92
        if (looksLikeAmountEntryPrompt(blob) || looksLikeMiscInfoEntryPrompt(blob) ||
            looksLikeBankSecretNumberPrompt(blob)
        ) {
            return 95
        }
        if (blobLooksLikeCarrierUssdSnapshot(blob)) return 100
        if (blobLooksLikeLenientUssdOverlay(blob) && hasCarrierUssdAnchor(blob)) return 80
        if (blobLooksLikeLenientUssdOverlay(blob)) return 25
        return 5
    }

    private fun selectBetterUssdCapture(a: String, b: String): String {
        val sa = ussdBlobPriorityScore(a)
        val sb = ussdBlobPriorityScore(b)
        return when {
            sb > sa -> b
            sa > sb -> a
            b.length > a.length -> b
            else -> a
        }
    }

    /** True if this is real carrier USSD text, not the home screen / widgets. */
    private fun isPlausibleUssdCapture(blob: String): Boolean {
        if (blob.isBlank()) return false
        return ussdBlobPriorityScore(blob) >= 70
    }

    private fun captureBestScoredUssdFromWindows(wins: List<AccessibilityWindowInfo>): Pair<String, Int> {
        var best = ""
        var bestSc = -1
        for (w in wins) {
            val r = w.root ?: continue
            try {
                val pkg = r.packageName?.toString().orEmpty()
                if (pkg.equals(packageName, ignoreCase = true)) continue
                val blob = dumpNodeTexts(r).trim()
                if (blob.isEmpty()) continue
                val sc = ussdBlobPriorityScore(blob)
                if (sc <= 0) continue
                if (sc > bestSc || (sc == bestSc && blob.length > best.length)) {
                    bestSc = sc
                    best = blob
                }
            } finally {
                r.recycle()
            }
        }
        return best to bestSc
    }

    private fun capturePhoneOrActiveWindowText(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows
            if (wins != null) {
                val (bestAll, scAll) = captureBestScoredUssdFromWindows(wins)
                if (scAll >= 70 && bestAll.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "USSD AX: capture best-window sc=$scAll len=${bestAll.length} preview=${bestAll.take(120)}"
                    )
                    return bestAll
                }
                var bestPhoneBlob = ""
                var bestPhoneScore = -1
                for (w in wins) {
                    val r = w.root ?: continue
                    try {
                        val pkg = r.packageName?.toString().orEmpty()
                        if (isPhoneStackPackage(pkg) || pkg.lowercase().contains("systemui")) {
                            val blob = dumpNodeTexts(r).trim()
                            if (blob.isEmpty()) continue
                            val score = ussdBlobPriorityScore(blob)
                            if (score > bestPhoneScore || (score == bestPhoneScore && blob.length > bestPhoneBlob.length)) {
                                bestPhoneScore = score
                                bestPhoneBlob = blob
                            }
                        }
                    } finally {
                        r.recycle()
                    }
                }
                if (bestPhoneBlob.isNotEmpty() && bestPhoneScore >= 80) return bestPhoneBlob
                // Samsung / OEM: USSD can stay open while focus returns to our app — window may not
                // match [isPhoneStackPackage] but still holds the balance/menu text.
                for (w in wins) {
                    val r = w.root ?: continue
                    try {
                        val pkg = r.packageName?.toString().orEmpty()
                        if (pkg.equals(packageName, ignoreCase = true)) continue
                        val blob = dumpNodeTexts(r)
                        if (blobLooksLikeCarrierUssdSnapshot(blob)) {
                            Log.d(TAG, "USSD AX: capture from non-active window pkg=${pkg.take(48)} len=${blob.length}")
                            return blob
                        }
                    } finally {
                        r.recycle()
                    }
                }
                // Balance-only screens often lack "Dooro Adeega" / menu lines — still USSD overlay.
                var lenientBest = ""
                for (w in wins) {
                    val r = w.root ?: continue
                    try {
                        val pkg = r.packageName?.toString().orEmpty()
                        if (pkg.equals(packageName, ignoreCase = true)) continue
                        val blob = dumpNodeTexts(r).trim()
                        if (!blobLooksLikeLenientUssdOverlay(blob) || !hasCarrierUssdAnchor(blob)) continue
                        if (blob.length > lenientBest.length) lenientBest = blob
                    } finally {
                        r.recycle()
                    }
                }
                if (lenientBest.isNotEmpty()) {
                    Log.d(TAG, "USSD AX: capture lenient overlay len=${lenientBest.length} preview=${lenientBest.take(120)}")
                    return lenientBest
                }
            }
        }
        val active = rootInActiveWindow ?: return ""
        try {
            val pkg = active.packageName?.toString().orEmpty()
            if (pkg.equals(packageName, ignoreCase = true)) {
                // USSD can remain behind our UI; scan windows again for the richest carrier snapshot.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val wins = windows
                    if (wins != null) {
                        var best = ""
                        var bestSc = -1
                        for (w in wins) {
                            val r = w.root ?: continue
                            try {
                                val p = r.packageName?.toString().orEmpty()
                                if (p.equals(packageName, ignoreCase = true)) continue
                                val blob = dumpNodeTexts(r).trim()
                                if (blob.isEmpty()) continue
                                val sc = ussdBlobPriorityScore(blob)
                                if (sc > bestSc || (sc == bestSc && blob.length > best.length)) {
                                    bestSc = sc
                                    best = blob
                                }
                            } finally {
                                r.recycle()
                            }
                        }
                        if (best.isNotEmpty() && bestSc >= 80 && hasCarrierUssdAnchor(best)) {
                            Log.d(TAG, "USSD AX: capture from background while app focused len=${best.length}")
                            return best
                        }
                    }
                }
                Log.w(TAG, "USSD AX: skip capture — active window is our app (focus left USSD dialog)")
                return ""
            }
            val blob = dumpNodeTexts(active)
            if (blobContainsAppDisclaimerJunk(blob)) return ""
            if (looksLikeNonUssdPhoneChrome(blob) || ussdBlobPriorityScore(blob) < 70) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val wins = windows
                    if (wins != null) {
                        val (b2, s2) = captureBestScoredUssdFromWindows(wins)
                        if (s2 >= 70 && b2.isNotEmpty()) return b2
                    }
                }
                return ""
            }
            return blob
        } finally {
            active.recycle()
        }
    }

    /** True when dumped text is clearly the carrier USSD dialog, not random system chrome. */
    private fun blobLooksLikeCarrierUssdSnapshot(blob: String): Boolean {
        if (blob.length < 12) return false
        if (blobContainsAppDisclaimerJunk(blob)) return false
        if (looksLikeNonUssdPhoneChrome(blob)) return false
        val b = blob.lowercase()

        if (looksLikeFlashTransferReceipt(blob)) return false

        if (b.contains("dooro") && b.contains("adeega")) return true
        if (b.contains("itus hadhaaga")) return true
        if (b.contains("hadhaageedu") || b.contains("hadhaagaagu")) return true
        if (b.contains("hadhaaga") && Regex("""\d""").containsMatchIn(blob)) return true
        if (b.contains("slsh") || b.contains("[-zaad")) return true
        if (b.contains("lacag dirid") || b.contains("lacag labixid")) return true
        if (looksLikeAmountEntryPrompt(blob)) return true
        if (looksLikeMiscInfoEntryPrompt(blob)) return true
        if (looksLikeBankSecretNumberPrompt(blob)) return true
        if (blobHasSomaliEnterVerb(blob) && (b.contains("pin") || b.contains("pink"))) return true
        if (b.contains("fadlan") && (b.contains("pin") || b.contains("pink"))) return true
        if (b.contains("zaad") && b.contains("pin")) return true
        return false
    }

    /**
     * Broader match when the balance screen uses short Somali/English lines without the main menu header.
     */
    private fun blobLooksLikeLenientUssdOverlay(blob: String): Boolean {
        if (blob.length < 24) return false
        if (blobContainsAppDisclaimerJunk(blob)) return false
        if (looksLikeNonUssdPhoneChrome(blob)) return false
        val b = blob.lowercase()
        
        if (b.contains("ayaad u dirtay") || b.contains("ka heshay") || b.contains("waxaad slsh")) {
            return false
        }
        
        if (b.contains("start monitor") || b.contains("stacked code per step")) return false
        if (b.contains("ussd behavior depends")) return false
        if (Regex("""(?i)slsh\s*[\d,]+|slsh\s*\d""").containsMatchIn(blob)) return true
        if (Regex("""(?i)hadhaag|haraaga|hareeraha|irirsinka|jeeg""").containsMatchIn(blob)) return true
        if (Regex("""(?i)\b(waa|waa\s+)\s*slsh""").containsMatchIn(blob)) return true
        // Numbered menu + Send/Cancel exists on dialer too — require a carrier/menu anchor.
        if (Regex("""(?m)^\s*\d+[\).\-\:]\s+\p{L}""").containsMatchIn(blob) && b.contains("send") && b.contains("cancel")) {
            return hasCarrierUssdAnchor(blob)
        }
        if (b.contains("send") && b.contains("cancel") && blob.length >= 48 &&
            Regex("""\d{2,}""").containsMatchIn(blob)
        ) {
            return hasCarrierUssdAnchor(blob)
        }
        return false
    }

    private fun blobContainsAppDisclaimerJunk(blob: String): Boolean {
        val b = blob.lowercase()
        return b.contains("ussd behavior depends") && b.contains("stacked code per step")
    }

    private fun isPhoneStackPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.equals(packageName, ignoreCase = true)) return false
        return p.contains("android.phone") ||
            p.contains("server.telecom") ||
            p.contains("incallui") ||
            p.contains("smart.caller") ||
            (p.contains("dialer") && !p.equals(packageName, ignoreCase = true)) ||
            (p.contains("transsion") && (p.contains("call") || p.contains("phone"))) ||
            p.contains("telephonyui") ||
            (p.contains("samsung") && (p.contains("phone") || p.contains("call") || p.contains("telecom"))) ||
            (p.contains("sec.android") && (p.contains("phone") || p.contains("call") || p.contains("telephony"))) ||
            p.contains("mediatek") && p.contains("telephony")
    }

    override fun onInterrupt() {
        UssdPinBridge.abortSession()
        UssdPinBridge.abortReadUssdTextCapture()
        injecting = false
        consecutiveFillNoClick = 0
    }

    private fun fillInput(
        root: AccessibilityNodeInfo,
        input: String,
        preferPlainFieldForMenu: Boolean
    ): Boolean {
        // After PIN, focus can remain on the password box while the service menu is visible — send
        // menu digits to a visible non-password EditText first when present.
        val amountOrShortMenuPlain = preferPlainFieldForMenu && (
            (input.all { it.isDigit() } && input.length in 1..12) ||
                input.matches(Regex("""^\d+(\.\d{1,8})?$"""))
            )
        if (amountOrShortMenuPlain) {
            findPlainUssdEditText(root)?.use { target ->
                if (setText(target, input)) {
                    Log.d(TAG, "USSD AX: SET_TEXT on plain USSD EditText (menu/amount)")
                    return true
                }
            }
        }
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focus ->
            try {
                val cn = focus.className?.toString().orEmpty()
                if (focus.isEditable && (cn.contains("EditText") || focus.isPassword)) {
                    if (setText(focus, input)) {
                        Log.d(TAG, "USSD AX: SET_TEXT on focused EditText/password")
                        return true
                    }
                }
            } finally {
                focus.recycle()
            }
        }
        findPinEditText(root)?.use { target ->
            if (setText(target, input)) {
                Log.d(TAG, "USSD AX: SET_TEXT on USSD EditText (class=${target.className})")
                return true
            }
        }
        return false
    }

    /** USSD response / menu input line — not the PIN/password field. */
    private fun findPlainUssdEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cn = node.className?.toString().orEmpty()
        if (node.isEditable && cn.contains("EditText", ignoreCase = true) && !node.isPassword) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                findPlainUssdEditText(c)?.let { return it }
            } finally {
                c.recycle()
            }
        }
        return null
    }

    private inline fun <T> AccessibilityNodeInfo.use(block: (AccessibilityNodeInfo) -> T): T {
        try {
            return block(this)
        } finally {
            recycle()
        }
    }

    private fun setText(node: AccessibilityNodeInfo, pin: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Only real [EditText] or password fields — avoid Transsion TextView that reports [isEditable]
     * for floating toolbars / selection handles.
     */
    private fun findPinEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cn = node.className?.toString().orEmpty()
        val isPinField = node.isEditable &&
            (cn.contains("EditText", ignoreCase = true) || node.isPassword)
        if (isPinField) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                findPinEditText(c)?.let { return it }
            } finally {
                c.recycle()
            }
        }
        return null
    }

    private fun clickConfirm(root: AccessibilityNodeInfo): Boolean {
        val labels = listOf(
            "ok", "send", "submit", "confirm", "done", "apply",
            "xaqiij", "dir", "go", "haah", "hagaag", "hagaaq", "diiwaan", "xaqiiji"
        )
        if (tryClickLabels(root, labels)) return true
        if (tryClickByViewId(root)) return true
        if (clickAnyButtonClass(root)) return true
        return false
    }

    private fun tryClickByViewId(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isClickable && (
                id.contains("button1") || id.contains("positive") ||
                    id.contains("ok") || id.contains("confirm") || id.contains("send")
                )
        ) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "USSD AX: clicked id=$id")
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                if (tryClickByViewId(c)) return true
            } finally {
                c.recycle()
            }
        }
        return false
    }

    private fun clickAnyButtonClass(node: AccessibilityNodeInfo): Boolean {
        val cn = node.className?.toString().orEmpty()
        if (node.isClickable && cn.contains("Button", ignoreCase = true)) {
            val t = node.text?.toString().orEmpty()
            val tLower = t.trim().lowercase()
            val denied = DENIED_BUTTON_TEXTS.any { denied ->
                tLower == denied || (denied.length > 3 && tLower.contains(denied))
            }
            if (!denied && t.length <= 24) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "USSD AX: clicked Button text=$t class=$cn")
                    return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                if (clickAnyButtonClass(c)) return true
            } finally {
                c.recycle()
            }
        }
        return false
    }

    private fun tryClickLabels(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        if (node.isClickable) {
            if (labels.any { text == it || (it.length > 2 && text.contains(it)) }) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "USSD AX: clicked label text=$text")
                    return true
                }
            }
            if (labels.any { desc.contains(it) }) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "USSD AX: clicked desc=$desc")
                    return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                if (tryClickLabels(c, labels)) return true
            } finally {
                c.recycle()
            }
        }
        return false
    }

    private fun dumpNodeTexts(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        appendTexts(node, sb, 0)
        return sb.toString()
    }

    private fun appendTexts(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 45) return
        node.text?.let { if (it.isNotEmpty()) sb.append(it).append('\n') }
        node.contentDescription?.let { if (it.isNotEmpty()) sb.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                appendTexts(c, sb, depth + 1)
            } finally {
                c.recycle()
            }
        }
    }

    /**
     * Dara-Salaam / bank USSD: “Fadlan Geli Numberkaaga sirta ee Bangiga” (no English “pin” word).
     */
    private fun looksLikeBankSecretNumberPrompt(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("numberkaaga") && b.contains("sirt")) return true
        if (b.contains("bangiga") && b.contains("sirt")) return true
        if (b.contains("bangi") && b.contains("sirt") && b.contains("number")) return true
        // “Numberkaaga … Bangiga” without “sirt” in some carrier builds.
        if (b.contains("numberkaaga") && (b.contains("bangiga") || b.contains("bangi"))) return true
        if (blobHasSomaliEnterVerb(b) && b.contains("account") && (b.contains("bang") || b.contains("bank"))) {
            return true
        }
        // Shorter carrier copy: “geli/gali … bang” without explicit “sirt” (bank BIN still goes here).
        if (blobHasSomaliEnterVerb(b) && (b.contains("bang") || b.contains("bank"))) return true
        return false
    }

    /**
     * Screens where an armed digit string is typed into the USSD field: service PIN, bank secret,
     * or maclumadka / reference (`{BANK_PIN}` e.g. 333999) — those often lack the word “pin”.
     */
    private fun looksLikeArmedPinEntryScreen(blob: String): Boolean {
        if (looksLikeUssdPinPrompt(blob)) return true
        if (looksLikeMiscInfoEntryPrompt(blob)) return true
        return false
    }

    private fun looksLikeUssdPinPrompt(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("enter your pin")) return true
        if (looksLikeBankSecretNumberPrompt(blob)) return true
        if (b.contains("pinka") || b.contains("pinkada") || b.contains("pink-ka")) return true
        if (blobHasSomaliEnterVerb(b) && (b.contains("pin") || b.contains("pink"))) return true
        if (b.contains("fadlan") && (b.contains("pin") || b.contains("pink"))) return true
        if ((b.contains("zaad") || b.contains("[-zaad")) && b.contains("pin")) return true
        return (b.contains("pin") || b.contains("pink")) &&
            (b.contains("enter") || blobHasSomaliEnterVerb(b) || b.contains("fadlan") || b.contains("your pin"))
    }

    /** Right after PIN submit, carrier shows service list (same lines as menu picker). */
    private fun looksLikePostPinServiceMenu(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("dooro") && b.contains("adeega")) return true
        if (b.contains("itus hadhaaga") && Regex("""(?m)^\s*1[\).\-\:]""").containsMatchIn(blob)) return true
        return false
    }

    /** “Fadlan Geli lacagta” / enter amount — not a numbered menu but still needs menu-key inject path. */
    private fun looksLikeAmountEntryPrompt(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("pink") || b.contains("pin")) return false
        if (blobHasSomaliEnterVerb(b) && b.contains("lacag")) return true
        if (b.contains("fadlan") && b.contains("lacag")) return true
        return false
    }

    /**
     * “Fadlan gali maclumadka” / macluumaad (enter reference or extra info) — often **1** then bank PIN.
     */
    private fun looksLikeMiscInfoEntryPrompt(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("maclum") || b.contains("macluum")) return true
        if (blobHasSomaliEnterVerb(b) && b.contains("xog")) return true
        return false
    }

    private fun looksLikeUssdMenuPrompt(blob: String): Boolean {
        val b = blob.lowercase()
        if (looksLikeUssdPinPrompt(blob)) return false
        // Strong menu cues first: the same window dump often still contains PIN labels from the
        // previous step: an early `looksLikeUssdPinPrompt` would wrongly block menu "1" injection.
        if (b.contains("dooro") && b.contains("adeega")) return true
        if (Regex("""(?m)^\s*1[\).\-\:]""").containsMatchIn(blob)) return true
        if (Regex("""(?m)^\s*2[\).\-\:]""").containsMatchIn(blob)) return true
        if (Regex("""(?i)\b(press|choose|select|dooro|riix)\s*\d+\b""").containsMatchIn(blob)) return true
        if (b.contains("itus hadhaaga")) return true
        if (b.contains("lacag dirid")) return true
        if (looksLikeUssdPinPrompt(blob)) return false
        return false
    }

    companion object {
        private const val TAG = "SarifAuto"
        private const val CAPTURE_SECOND_PASS_MS = 650L
        /** First read after PIN+Send — shorter than old 900ms so “Dooro Adeega” feels snappy. */
        private const val CAPTURE_DELAY_AFTER_PIN_MS = 420L
        /** Second read when first pass already looks like service menu (was ~650ms). */
        private const val CAPTURE_FAST_PIN_MENU_SECOND_MS = 110L
        /** Faster follow-up read after menu digit + Send (PIN path keeps long delays). */
        private const val CAPTURE_DELAY_MENU_MS = 450L
        private const val CAPTURE_SECOND_MENU_MS = 420L
        private const val CAPTURE_THIRD_MENU_MS = 750L
        private const val SESSION_MAX_MS = 60_000L
        private const val MAX_FAIL_STREAK = 6

        @Volatile
        private var instance: UssdAccessibilityService? = null

        /** Clears inject debounce so PIN→menu transition is not delayed ~400ms+ waiting out streak. */
        fun prepareArmedInjectSession() {
            instance?.resetInjectDebouncingForNewSession()
        }

        fun forceDismissDialog() {
            val s = instance ?: return
            s.mainHandler.post {
                if (s.isOurAppFocusedWindow()) {
                    Log.w(TAG, "forceDismiss: skip BACK — Sarif Auto is focused (would close the app)")
                    return@post
                }
                s.performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }

        /**
         * Closes a lingering USSD overlay (e.g. balance "OK") before dialing a new `*800#` transfer.
         * Skips when our activity has focus — [GLOBAL_ACTION_BACK] would finish [MainActivity] instead.
         */
        fun dismissUssdBeforeNewSession() {
            val s = instance ?: return
            s.mainHandler.post {
                if (s.isOurAppFocusedWindow()) {
                    Log.w(TAG, "dismissUssd: skip BACK — Sarif Auto is focused (would close the app)")
                    return@post
                }
                s.performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }

        /**
         * Proactive inject passes — Transsion/OEMs often deliver sparse events; kicks avoid 5–10s waits
         * before typing menu “1”.
         */
        fun scheduleArmedInjectKicks() {
            val s = instance ?: return
            val runTry: () -> Unit = {
                val pin = UssdPinBridge.armedPin
                val menuKey = UssdPinBridge.armedMenuKey
                val input = pin ?: menuKey
                if (input != null) {
                    s.tryInjectArmedInput(pin, menuKey, input)
                }
            }
            s.mainHandler.post { runTry() }
            val delays = longArrayOf(12L, 28L, 55L, 95L, 150L, 230L, 360L)
            for (d in delays) {
                s.mainHandler.postDelayed({ runTry() }, d)
            }
        }

        /**
         * Snapshot the phone USSD window a few times then finalize [UssdPinBridge.beginReadUssdTextCapture].
         * No-op if this service is not connected.
         */
        fun scheduleReadCaptureAssisted() {
            val s = instance ?: return
            val delays = longArrayOf(250L, 700L, 1400L, 2600L)
            for (d in delays) {
                s.mainHandler.postDelayed({ s.tickReadCaptureSnapshot() }, d)
            }
            s.mainHandler.postDelayed({ s.finalizeReadCaptureSnapshot() }, 4200L)
            s.mainHandler.postDelayed({ s.finalizeReadCaptureSnapshot() }, 8500L)
        }

        /** Labels on Sarif Auto main screen — never auto-click via accessibility. */
        private val DENIED_BUTTON_TEXTS = setOf(
            "start monitor",
            "stop",
            "open accessibility settings"
        )

        fun isEnabled(context: android.content.Context): Boolean {
            val cn = android.content.ComponentName(context, UssdAccessibilityService::class.java)
            val flat = cn.flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (part in splitter) {
                if (part.equals(flat, ignoreCase = true)) return true
            }
            return false
        }
    }
}
