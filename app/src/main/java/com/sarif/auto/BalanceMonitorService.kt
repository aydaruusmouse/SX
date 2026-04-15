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
        Log.i(TAG, "onStartCommand: foreground + loop (subId=${prefs.subscriptionId}, interval=${prefs.loopIntervalSeconds}s)")
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
        sendBroadcast(
            Intent(BalanceUiContract.ACTION_BALANCE_UPDATE).apply {
                setPackage(packageName)
                putExtra(BalanceUiContract.EXTRA_BALANCE_PLAIN, amount.toPlainString())
            }
        )
    }

    private fun broadcastUssdBusy(busy: Boolean) {
        sendBroadcast(
            Intent(BalanceUiContract.ACTION_USSD_BUSY).apply {
                setPackage(packageName)
                putExtra(BalanceUiContract.EXTRA_BUSY, busy)
            }
        )
    }

    private fun broadcastUssdStep(subId: Int, opener: String, stepIndex: Int, r: UssdResult) {
        val fail = r is UssdResult.Failure
        val body = when (r) {
            is UssdResult.Message -> r.text
            is UssdResult.Failure -> "${r.code}:${r.message}"
        }
        sendBroadcast(
            Intent(BalanceUiContract.ACTION_USSD_STEP).apply {
                setPackage(packageName)
                putExtra(BalanceUiContract.EXTRA_STEP_INDEX, stepIndex)
                putExtra(BalanceUiContract.EXTRA_RESULT_IS_FAILURE, fail)
                putExtra(BalanceUiContract.EXTRA_STEP_BODY, body)
                putExtra(BalanceUiContract.EXTRA_REQUEST_OPENER, opener)
                putExtra(
                    BalanceUiContract.EXTRA_SIM_LABEL,
                    if (subId < 0) "def" else subId.toString()
                )
            }
        )
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
            while (isActive) {
                runCycle(prefs, subId)
                delay(prefs.loopIntervalSeconds * 1000L)
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

            val balance = BalanceParser.parseLargestPositiveAmount(combined)
            if (balance == null || balance <= BigDecimal.ZERO) {
                Log.d(TAG, "runCycle: no positive balance parsed (snippet len=${snippet.length})")
                postNotificationUpdate(getString(R.string.notify_status_no_amount))
                delay(prefs.stepDelayMs)
                return
            }

            val transferAmount = prefs.sendTransferAmount()
            Log.d(
                TAG,
                "runCycle: parsed balance=$balance step2 transferAmount=$transferAmount sendSteps next"
            )
            postNotificationUpdate(getString(R.string.notify_status_parsed, balance.toPlainString()))
            broadcastParsedBalance(balance)
            delay(prefs.stepDelayMs)

            val sendSteps = PlaceholderUssd.expandSendStepsWithAutoPin(
                prefs.sendMoneySteps,
                pin,
                recipient,
                transferAmount
            )
            if (sendSteps.isNotEmpty()) {
                postNotificationUpdate(getString(R.string.notify_status_sending))
                val sendOpener = sendSteps.firstOrNull().orEmpty()
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
                        }
                    )
                } catch (_: SecurityException) {
                    postNotificationUpdate(getString(R.string.notify_status_denied))
                    return
                }
            }

            postNotificationUpdate(getString(R.string.notify_status_cycle_done))
            delay(prefs.stepDelayMs)
        } finally {
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
