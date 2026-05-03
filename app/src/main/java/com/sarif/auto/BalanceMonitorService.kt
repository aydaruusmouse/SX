package com.sarif.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal

class BalanceMonitorService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var loopJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = SecurePrefs(this)
        if (prefs.pin.isBlank()) {
            Log.w(TAG, "onStartCommand: no PIN, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        isServiceRunning = true
        Log.i(
            TAG,
            "onStartCommand: foreground + loop (subId=${prefs.subscriptionId}, interval=${prefs.loopIntervalSeconds}s, axMinGapMs=${prefs.axUssdMinCycleGapMs})"
        )
        startAsForeground(getString(R.string.notify_text))
        startLoop(prefs)
        return START_STICKY
    }

    private fun startAsForeground(contentText: String) {
        createChannel()
        val notification = buildMonitorNotification(contentText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildMonitorNotification(contentText: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun broadcastParsedBalance(amount: BigDecimal) {
        scope.launch {
            com.sarif.auto.domain.UssdStateObserver.emitBalance(amount.toPlainString())
        }
    }

    private fun broadcastUssdBusy(busy: Boolean) {
        scope.launch {
            com.sarif.auto.domain.UssdStateObserver.emitBusy(busy)
        }
    }

    private fun broadcastUssdStep(subId: Int, opener: String, stepIndex: Int, r: UssdResult) {
        val fail = r is UssdResult.Failure
        val body = when (r) {
            is UssdResult.Message -> r.text
            is UssdResult.Failure -> "${r.code}:${r.message}"
        }
        scope.launch {
            com.sarif.auto.domain.UssdStateObserver.emitStep(
                com.sarif.auto.domain.UssdStepEvent(
                    stepIndex = stepIndex,
                    isFailure = fail,
                    body = body,
                    requestOpener = opener,
                    simLabel = if (subId < 0) "def" else subId.toString()
                )
            )
        }
    }

    private fun openerLine(prefs: SecurePrefs): String {
        val lines = prefs.balanceUssdSteps.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.firstOrNull() ?: "*222#"
    }

    private fun postNotificationUpdate(contentText: String) {
        mainHandler.post {
            if (serviceJob.isCancelled) return@post
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildMonitorNotification(contentText))
            } catch (_: Exception) {
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notify_channel_desc)
        }
        nm.createNotificationChannel(ch)
    }

    private fun startLoop(prefs: SecurePrefs) {
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.Default) {
            val subId = prefs.subscriptionId
            val appCtx = applicationContext
            while (isActive) {
                runCycle(prefs, subId)
                val intervalMs = prefs.loopIntervalSeconds * 1000L
                // System *222# + Accessibility PIN: a new MMI too soon often returns
                // "Connection problem or invalid MMI code" / USSD timeout on the next cycle.
                val waitMs = if (prefs.useAccessibilityUssdPin &&
                    UssdAccessibilityService.isEnabled(appCtx)
                ) {
                    maxOf(intervalMs, prefs.axUssdMinCycleGapMs)
                } else {
                    intervalMs
                }
                if (waitMs > intervalMs) {
                    Log.d(
                        TAG,
                        "loop: wait ${waitMs}ms before next balance (interval was ${intervalMs}ms, AX USSD cooldown)"
                    )
                }
                delay(waitMs)
            }
        }
    }

    private suspend fun runCycle(prefs: SecurePrefs, subId: Int) {
        val pin = prefs.pin
        val recipient = prefs.recipientMsisdn
        val opener = openerLine(prefs)

        val openerSteps = PlaceholderUssd.expandBalanceSteps(
            prefs.balanceUssdSteps,
            pin,
            recipient
        )

        if (openerSteps.isEmpty()) {
            Log.w(TAG, "runCycle: no balance steps, skip")
            return
        }

        broadcastUssdBusy(true)
        try {
            Log.d(TAG, "runCycle: balance opener lines=${openerSteps.size} subId=$subId (interactive PIN/menu)")
            postNotificationUpdate(getString(R.string.notify_status_balance))

            val executor = UssdExecutor(this, subId)
            val balanceResults = try {
                BalanceUssdInteractive.runUntilBalanceParsed(
                    this@BalanceMonitorService,
                    executor,
                    prefs.balanceUssdSteps,
                    pin,
                    recipient,
                    prefs.stepDelayMs,
                    onEachStep = { step, r -> broadcastUssdStep(subId, opener, step, r) }
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "balance sendChain SecurityException", e)
                postNotificationUpdate(getString(R.string.notify_status_denied))
                return
            }

            val combined = balanceResults.joinToString("\n") { r ->
                when (r) {
                    is UssdResult.Message -> r.text
                    is UssdResult.Failure -> "FAIL:${r.code}:${r.message}"
                }
            }

            val snippet = combined.replace("\n", " ").trim().take(NOTIFY_SNIPPET_MAX)
            postNotificationUpdate(
                getString(R.string.notify_status_reply, snippet.ifEmpty { "—" })
            )

            // --- Phase 1: parse balance (no transfer yet) ---
            val parsedBalance = BalanceParser.parseLargestPositiveAmount(combined)
            if (parsedBalance == null || parsedBalance <= BigDecimal.ZERO) {
                if (BalanceParser.looksLikeUssdFailureWithoutBalanceLine(combined)) {
                    Log.w(TAG, "runCycle: carrier failure detected, adding 15s penalty delay to backoff rate limits")
                    scope.launch { com.sarif.auto.domain.UssdStateObserver.emitBackoff(true) }
                    delay(15_000L)
                    scope.launch { com.sarif.auto.domain.UssdStateObserver.emitBackoff(false) }
                }
                Log.d(TAG, "runCycle: balance not > 0 — skip transfer, next loop checks balance again")
                postNotificationUpdate(getString(R.string.notify_status_no_amount))
                delay(prefs.stepDelayMs)
                return
            }

            prefs.lastParsedBalancePlain = parsedBalance.toPlainString()
            broadcastParsedBalance(parsedBalance)
            postNotificationUpdate(getString(R.string.notify_status_parsed, parsedBalance.toPlainString()))

            // --- Phase 2: compute transfer amount once for this cycle (stored locally until send) ---
            val firstBalanceLine = openerSteps.firstOrNull().orEmpty()
            val skipTelesomSlshMinimum = firstBalanceLine.contains("*800") ||
                firstBalanceLine.contains("*888")
            val pendingTransferAmount = com.sarif.auto.domain.TransferCalculatorUseCase.resolveTransferAmountForSend(
                configuredSendAmount = prefs.sendTransferAmountPlain,
                balanceJustParsed = parsedBalance,
                lastParsedBalance = prefs.lastParsedBalancePlain,
                transferReserve = prefs.transferReservePlain,
                ussdContext = combined,
                skipTelesomSlshMinimum = skipTelesomSlshMinimum
            )
            if (pendingTransferAmount <= BigDecimal.ZERO) {
                Log.w(
                    TAG,
                    "runCycle: transfer amount is 0 — skip transfer, next loop checks balance again (balance=$parsedBalance)"
                )
                delay(prefs.stepDelayMs)
                return
            }

            Log.d(
                TAG,
                "runCycle: balance=$parsedBalance pendingTransferAmount=$pendingTransferAmount → transfer phase"
            )
            // Pause using only [SecurePrefs.stepDelayMs]. If *220* is often ignored right after
            // balance USSD, increase step delay in settings (a fixed extra seconds was confusing
            // and could block the transfer phase on some devices).
            UssdPinBridge.abortSession()
            UssdPinBridge.abortReadUssdTextCapture()
            val preTransferDelay = if (skipTelesomSlshMinimum) {
                kotlin.math.max(180L, kotlin.math.min(prefs.stepDelayMs, 420L))
            } else {
                prefs.stepDelayMs
            }
            delay(preTransferDelay)

            // --- Phase 3: send money using only pendingTransferAmount from phase 2 ---
            val sendSteps = PlaceholderUssd.expandSendStepsWithAutoPin(
                prefs.sendMoneySteps,
                pin,
                recipient,
                pendingTransferAmount,
                prefs.transferBankPinPlain
            )
            UssdPinBridge.setSendChainPreferredAmountDigits(
                PlaceholderUssd.formatSendAmountPlaceholder(pendingTransferAmount)
            )
            if (sendSteps.isNotEmpty()) {
                val firstSendForDismiss = sendSteps.firstOrNull()?.trim().orEmpty()
                if (firstSendForDismiss.isNotEmpty() &&
                    (
                        PlaceholderUssd.isAxPopupPinSendOpener(firstSendForDismiss) ||
                            skipTelesomSlshMinimum
                        )
                ) {
                    Log.d(TAG, "runCycle: dismiss lingering USSD overlay before transfer")
                    UssdAccessibilityService.dismissUssdBeforeNewSession()
                    // Let BACK close the balance dialog and avoid racing MainActivity relayout / system UI
                    // before *800#; 650ms was often too tight on Samsung.
                    delay(1100L)
                }
                postNotificationUpdate(getString(R.string.notify_status_sending))
                val sendOpener = sendSteps.firstOrNull().orEmpty()
                val firstSendTrimmed = sendSteps.firstOrNull()?.trim().orEmpty()
                val assumeAxMenuFollowUps = skipTelesomSlshMinimum &&
                    firstSendTrimmed.isNotEmpty() &&
                    !PlaceholderUssd.isAxPopupPinSendOpener(firstSendTrimmed) &&
                    SendMoneyUssdInteractive.isBareInSessionSendStep(firstSendTrimmed)
                try {
                    SendMoneyUssdInteractive.runSendChain(
                        this@BalanceMonitorService,
                        executor,
                        sendSteps,
                        prefs.stepDelayMs,
                        prefs,
                        initialStepIndex = balanceResults.size,
                        onEachStep = { step, r ->
                            broadcastUssdStep(subId, sendOpener.ifBlank { opener }, step, r)
                        },
                        assumeAccessibilityMenuFollowUps = assumeAxMenuFollowUps
                    )
                } catch (_: SecurityException) {
                    postNotificationUpdate(getString(R.string.notify_status_denied))
                    return
                }
            }

            postNotificationUpdate(getString(R.string.notify_status_cycle_done))
            delay(prefs.stepDelayMs)
        } finally {
            UssdPinBridge.abortSession()
            UssdPinBridge.clearSendMoneySessionHints()
            UssdPinBridge.abortReadUssdTextCapture()
            broadcastUssdBusy(false)
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isServiceRunning = false
        broadcastUssdBusy(false)
        UssdPinBridge.abortSession()
        stopLoop()
        scope.cancel()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SarifAuto"

        @Volatile
        var isServiceRunning: Boolean = false

        /** Bumped so devices that created the channel as LOW importance get a visible default again. */
        private const val CHANNEL_ID = "sarif_balance_monitor_v2"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_SNIPPET_MAX = 220

        fun start(context: Context) {
            val i = Intent(context, BalanceMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                @Suppress("DEPRECATION")
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BalanceMonitorService::class.java))
        }
    }
}
