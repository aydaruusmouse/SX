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

        if (injecting) return
        if (UssdPinBridge.sessionAgeMs() > SESSION_MAX_MS) {
            UssdPinBridge.abortSession()
            consecutiveFillNoClick = 0
            return
        }

        // Debounce hammering the wrong window (e.g. Transsion TextView “editable” toolbars)
        val now = SystemClock.elapsedRealtime()
        if (consecutiveFillNoClick > 0 && now - lastFailureElapsed < 400L) return

        val roots = collectCandidateRoots()
        for (root in roots) {
            try {
                val pkg = root.packageName?.toString().orEmpty()
                val blob = dumpNodeTexts(root)
                if (!isLikelyUssdWindow(pkg, blob)) continue
                if (pin != null && !looksLikeUssdPinPrompt(blob)) continue
                if (menuKey != null && !looksLikeUssdMenuPrompt(blob)) continue

                injecting = true
                val filled = fillInput(root, inputToInject)
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
                mainHandler.postDelayed({
                    val first = capturePhoneOrActiveWindowText()
                    mainHandler.postDelayed({
                        val second = capturePhoneOrActiveWindowText()
                        val merged = if (second.length > first.length) second else first
                        Log.d(TAG, "USSD AX: captured len=${merged.length} preview=${merged.take(200)}")
                        UssdPinBridge.finishWithCapturedText(merged.ifBlank { null })
                        injecting = false
                    }, CAPTURE_SECOND_PASS_MS)
                }, CAPTURE_DELAY_MS)
                return
            } finally {
                root.recycle()
            }
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
        // Odd OEM pkg: only if text is clearly carrier PIN (not generic "USSD" help copy)
        return blobLooksLikeCarrierPinScreen(blob)
    }

    private fun blobLooksLikeCarrierPinScreen(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("zaad") || b.contains("[-zaad")) return true
        if (b.contains("geli") && b.contains("pin")) return true
        if (b.contains("fadlan") && b.contains("pin")) return true
        if (b.contains("enter your pin")) return true
        return false
    }

    private fun capturePhoneOrActiveWindowText(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows
            if (wins != null) {
                val sb = StringBuilder()
                for (w in wins) {
                    val r = w.root ?: continue
                    try {
                        val pkg = r.packageName?.toString().orEmpty()
                        if (isPhoneStackPackage(pkg)) {
                            sb.append(dumpNodeTexts(r))
                        }
                    } finally {
                        r.recycle()
                    }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
        }
        val active = rootInActiveWindow ?: return ""
        try {
            return dumpNodeTexts(active)
        } finally {
            active.recycle()
        }
    }

    private fun isPhoneStackPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("android.phone") ||
            p.contains("server.telecom") ||
            p.contains("incallui") ||
            p.contains("smart.caller") ||
            (p.contains("dialer") && !p.equals(packageName, ignoreCase = true)) ||
            (p.contains("transsion") && (p.contains("call") || p.contains("phone")))
    }

    override fun onInterrupt() {
        UssdPinBridge.abortSession()
        UssdPinBridge.abortReadUssdTextCapture()
        injecting = false
        consecutiveFillNoClick = 0
    }

    private fun fillInput(root: AccessibilityNodeInfo, input: String): Boolean {
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

    private fun looksLikeUssdPinPrompt(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("enter your pin")) return true
        if (b.contains("geli") && b.contains("pin")) return true
        if (b.contains("fadlan") && b.contains("pin")) return true
        if ((b.contains("zaad") || b.contains("[-zaad")) && b.contains("pin")) return true
        return b.contains("pin") &&
            (b.contains("enter") || b.contains("geli") || b.contains("fadlan") || b.contains("your pin"))
    }

    private fun looksLikeUssdMenuPrompt(blob: String): Boolean {
        val b = blob.lowercase()
        if (looksLikeUssdPinPrompt(blob)) return false
        if (Regex("""(?m)^\s*1[\).\-\:]""").containsMatchIn(blob)) return true
        if (Regex("""(?m)^\s*2[\).\-\:]""").containsMatchIn(blob)) return true
        if (Regex("""(?i)\b(press|choose|select|dooro|riix)\s*\d+\b""").containsMatchIn(blob)) return true
        if (b.contains("itus hadhaaga")) return true
        if (b.contains("lacag dirid")) return true
        return false
    }

    companion object {
        private const val TAG = "SarifAuto"
        private const val CAPTURE_DELAY_MS = 900L
        private const val CAPTURE_SECOND_PASS_MS = 650L
        private const val SESSION_MAX_MS = 60_000L
        private const val MAX_FAIL_STREAK = 6

        @Volatile
        private var instance: UssdAccessibilityService? = null

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
