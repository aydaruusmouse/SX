package com.sarif.auto

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred

/**
 * Coordinates [UssdAccessibilityService] with [BalanceUssdInteractive].
 */
object UssdPinBridge {

    private val lock = Any()

    @Volatile
    private var armedInputInternal: String? = null

    @Volatile
    private var armedInputKindInternal: InputKind? = null

    @Volatile
    private var armedAtElapsed: Long = 0L

    /** When true, [UssdAccessibilityService] uses shorter post-inject read delays (send-money chain). */
    @Volatile
    private var fastTransferPostInjectCapture: Boolean = false

    /**
     * During send-money, balance-derived `{AMOUNT}` (whole units). When the USSD screen is
     * “Fadlan Geli lacagta”, inject this instead of a stray template menu digit (e.g. `4`).
     */
    @Volatile
    var sendChainPreferredAmountDigits: String? = null
        private set

    fun setSendChainPreferredAmountDigits(digits: String?) {
        sendChainPreferredAmountDigits = digits?.trim()?.takeIf { it.isNotEmpty() }
    }

    private var pending: CompletableDeferred<String?>? = null

    fun setFastTransferCapture(fast: Boolean) {
        fastTransferPostInjectCapture = fast
    }

    fun isFastTransferCapture(): Boolean = fastTransferPostInjectCapture

    /** Read-only: merge on-screen USSD text after Telephony follow-up (e.g. menu key `1`). */
    private var readCapturePending: CompletableDeferred<String?>? = null
    private var readCaptureBest: String = ""

    val armedPin: String?
        get() = if (armedInputKindInternal == InputKind.PIN) armedInputInternal else null

    val armedMenuKey: String?
        get() = if (armedInputKindInternal == InputKind.MENU) armedInputInternal else null

    fun beginPinSession(pin: String): CompletableDeferred<String?> = synchronized(lock) {
        beginInputSessionLocked(pin, InputKind.PIN)
    }

    fun beginMenuSession(menuKey: String): CompletableDeferred<String?> = synchronized(lock) {
        beginInputSessionLocked(menuKey, InputKind.MENU)
    }

    private fun beginInputSessionLocked(
        input: String,
        kind: InputKind
    ): CompletableDeferred<String?> {
        abortReadUssdTextCaptureLocked()
        armedInputInternal = input
        armedInputKindInternal = kind
        armedAtElapsed = SystemClock.elapsedRealtime()
        return CompletableDeferred<String?>().also { pending = it }
    }

    fun sessionAgeMs(): Long = SystemClock.elapsedRealtime() - armedAtElapsed

    /** Stop watching for PIN prompt (after submit); capture still delivered via [finishWithCapturedText]. */
    fun clearArmedPin() = synchronized(lock) {
        clearArmedInputLocked()
    }

    fun clearArmedInput() = synchronized(lock) {
        clearArmedInputLocked()
    }

    fun finishWithCapturedText(text: String?) = synchronized(lock) {
        clearArmedInputLocked()
        pending?.complete(text)
        pending = null
    }

    fun abortSession() = synchronized(lock) {
        clearArmedInputLocked()
        pending?.complete(null)
        pending = null
    }

    /** Clears send-money-only context (call when the monitor cycle ends). */
    fun clearSendMoneySessionHints() {
        sendChainPreferredAmountDigits = null
    }

    /**
     * Starts collecting accessibility snapshots of the phone USSD dialog.
     * [UssdAccessibilityService] calls [offerReadCaptureCandidate] from events/ticks;
     * [finalizeReadUssdTextCaptureFromAccessibility] completes the deferred with the longest good blob.
     */
    fun beginReadUssdTextCapture(): CompletableDeferred<String?> = synchronized(lock) {
        readCapturePending?.complete(null)
        readCaptureBest = ""
        armedAtElapsed = SystemClock.elapsedRealtime()
        CompletableDeferred<String?>().also { readCapturePending = it }
    }

    fun hasReadCapturePending(): Boolean = synchronized(lock) { readCapturePending != null }

    fun offerReadCaptureCandidate(blob: String) {
        synchronized(lock) {
            if (readCapturePending == null) return
            val t = blob.trim()
            if (!isAcceptableReadCaptureBlob(t)) return
            if (t.length > readCaptureBest.length) readCaptureBest = t
        }
    }

    fun finalizeReadUssdTextCaptureFromAccessibility() {
        synchronized(lock) {
            val d = readCapturePending ?: return
            val best = readCaptureBest
            readCapturePending = null
            readCaptureBest = ""
            d.complete(best.ifEmpty { null })
        }
    }

    fun abortReadUssdTextCapture() = synchronized(lock) {
        abortReadUssdTextCaptureLocked()
    }

    private fun abortReadUssdTextCaptureLocked() {
        readCapturePending?.complete(null)
        readCapturePending = null
        readCaptureBest = ""
    }

    private fun clearArmedInputLocked() {
        armedInputInternal = null
        armedInputKindInternal = null
    }

    /** Ignore app disclaimer / junk that sometimes appears in dumps. */
    private fun isAcceptableReadCaptureBlob(blob: String): Boolean {
        if (blob.length < 8) return false
        val b = blob.lowercase()
        if (b.contains("ussd behavior depends")) return false
        if (b.contains("storing a pin on the device")) return false
        if (blobLooksLikeUssdDialogContent(blob)) return true
        return blob.length >= 48
    }

    private fun blobLooksLikeUssdDialogContent(blob: String): Boolean {
        val b = blob.lowercase()
        if (b.contains("zaad")) return true
        if (b.contains("[-zaad")) return true
        if (b.contains("balance")) return true
        if (b.contains("haraad")) return true
        if (b.contains("lacag")) return true
        if (b.contains("$")) return true
        if (b.contains("geli") && b.contains("pin")) return true
        if (b.contains("fadlan") && b.contains("pin")) return true
        if (Regex("""\d{1,3}[.,]\d{2,3}\b""").containsMatchIn(blob)) return true
        return false
    }

    private enum class InputKind {
        PIN,
        MENU
    }
}
