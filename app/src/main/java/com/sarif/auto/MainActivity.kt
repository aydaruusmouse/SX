package com.sarif.auto

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sarif.auto.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "SarifAuto"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SecurePrefs
    private lateinit var logStore: UssdLogStore
    private val servedAdapter = ServedRequestsAdapter()

    private fun observeUssdState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.sarif.auto.domain.UssdStateObserver.balanceUpdates.collect { v ->
                        binding.textLastBalance.text = getString(R.string.label_last_balance_value, v)
                    }
                }
                launch {
                    com.sarif.auto.domain.UssdStateObserver.ussdBusy.collect {
                        refreshServingUi()
                    }
                }
                launch {
                    com.sarif.auto.domain.UssdStateObserver.backoffActive.collect { active ->
                        if (active) {
                            if (BalanceMonitorService.isServiceRunning) {
                                Log.d(TAG, "rate limit backoff (no toast — avoid stealing focus from USSD)")
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.toast_rate_limit_backoff,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                launch {
                    com.sarif.auto.domain.UssdStateObserver.ussdSteps.collect { event ->
                        logStore.append(
                            UssdLogEntry(
                                id = System.nanoTime(),
                                stepIndex = event.stepIndex,
                                timeMs = System.currentTimeMillis(),
                                requestOpener = event.requestOpener.ifBlank { "*" },
                                simLabel = event.simLabel,
                                ok = !event.isFailure,
                                response = event.body.take(2000)
                            )
                        )
                        refreshServedList()
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allOk = granted.values.all { it }
        if (!allOk) {
            Toast.makeText(this, R.string.toast_permissions, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.toast_permissions_ok, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs(this)
        logStore = UssdLogStore(this)

        binding.recyclerServed.layoutManager = LinearLayoutManager(this)
        binding.recyclerServed.adapter = servedAdapter
        refreshServedList()

        loadFields()
        setupSimSpinner()
        refreshServingUi()

        binding.btnServing.setOnClickListener {
            if (BalanceMonitorService.isServiceRunning) {
                BalanceMonitorService.stop(this)
                Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
                refreshServingUi()
                return@setOnClickListener
            }
            startMonitorFromUi()
        }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun startMonitorFromUi() {
        if (!hasRequiredPermissions()) {
            requestNeededPermissions()
            return
        }
        saveFields()
        if (prefs.pin.isBlank()) {
            Toast.makeText(this, R.string.toast_need_pin, Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.useAccessibilityUssdPin && !UssdAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
        }
        val balanceSteps = PlaceholderUssd.expandBalanceStepsWithAutoPin(
            prefs.balanceUssdSteps,
            prefs.pin,
            prefs.recipientMsisdn
        )
        if (balanceSteps.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_ussd_steps, Toast.LENGTH_LONG).show()
            return
        }
        try {
            BalanceMonitorService.start(this)
        } catch (e: Exception) {
            val fgsBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            if (fgsBlocked) {
                Toast.makeText(this, R.string.toast_fgs_blocked, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.toast_service_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_LONG).show()
        refreshServingUi()
    }

    private fun refreshServingUi() {
        val running = BalanceMonitorService.isServiceRunning
        binding.btnServing.text = if (running) {
            getString(R.string.btn_stop_serving)
        } else {
            getString(R.string.btn_start_serving)
        }
    }

    private fun refreshServedList() {
        servedAdapter.setItems(logStore.loadRecent())
        val n = servedAdapter.itemCount
        if (n > 0) {
            binding.recyclerServed.scrollToPosition(n - 1)
        }
    }

    override fun onStart() {
        super.onStart()
        observeUssdState()
        refreshServingUi()
    }

    override fun onStop() {
        super.onStop()
    }

    private fun loadFields() {
        binding.inputPin.setText(prefs.pin)
        binding.inputRecipient.setText(prefs.recipientMsisdn)
        binding.inputTransferAmount.setText(prefs.sendTransferAmountPlain)
        binding.inputTransferReserve.setText(prefs.transferReservePlain)
        binding.inputTransferBankPin.setText(prefs.transferBankPinPlain)
        val lastBal = prefs.lastParsedBalancePlain
        if (lastBal.isNotBlank()) {
            binding.textLastBalance.text = getString(R.string.label_last_balance_value, lastBal)
        }
        binding.inputInterval.setText(prefs.loopIntervalSeconds.toString())
        binding.inputStepDelay.setText(prefs.stepDelayMs.toString())
        binding.inputAxUssdMinGap.setText(prefs.axUssdMinCycleGapMs.toString())
        binding.inputBalanceSteps.setText(prefs.balanceUssdSteps)
        binding.inputSendSteps.setText(prefs.sendMoneySteps)
        binding.switchAxUssdPin.isChecked = prefs.useAccessibilityUssdPin
    }

    private fun saveFields() {
        prefs.pin = binding.inputPin.text?.toString().orEmpty()
        prefs.recipientMsisdn = binding.inputRecipient.text?.toString()?.trim().orEmpty()
            .ifEmpty { "4671911" }
        prefs.sendTransferAmountPlain = binding.inputTransferAmount.text?.toString()?.trim().orEmpty()
        prefs.transferReservePlain = binding.inputTransferReserve.text?.toString()?.trim().orEmpty()
            .ifEmpty { SecurePrefs.DEFAULT_TRANSFER_RESERVE }
        prefs.transferBankPinPlain = binding.inputTransferBankPin.text?.toString()?.trim().orEmpty()
        prefs.loopIntervalSeconds = binding.inputInterval.text?.toString()?.toIntOrNull() ?: 5
        prefs.stepDelayMs = binding.inputStepDelay.text?.toString()?.toLongOrNull() ?: 1500L
        prefs.axUssdMinCycleGapMs = binding.inputAxUssdMinGap.text?.toString()?.toLongOrNull()
            ?: SecurePrefs.DEFAULT_AX_USSD_MIN_CYCLE_GAP_MS
        prefs.balanceUssdSteps = binding.inputBalanceSteps.text?.toString()?.trim().orEmpty()
            .ifEmpty { SecurePrefs.DEFAULT_BALANCE_STEPS }
        prefs.sendMoneySteps = binding.inputSendSteps.text?.toString()?.trim().orEmpty()
            .ifEmpty { SecurePrefs.DEFAULT_SEND_STEPS }

        val sel = binding.spinnerSim.selectedItemPosition
        val subs = readSubscriptions()
        prefs.subscriptionId = if (sel > 0 && sel <= subs.size) {
            subs[sel - 1].subscriptionId
        } else {
            -1
        }
        prefs.useAccessibilityUssdPin = binding.switchAxUssdPin.isChecked
    }

    private fun setupSimSpinner() {
        val subs = readSubscriptions()
        val labels = mutableListOf<String>()
        labels.add(getString(R.string.sim_default))
        subs.forEach { info ->
            val id = info.subscriptionId
            val carrier = info.carrierName?.toString().orEmpty()
            val label = if (carrier.isNotEmpty()) "$carrier (sub $id)" else "Subscription $id"
            labels.add(label)
        }
        binding.spinnerSim.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val target = prefs.subscriptionId
        if (target >= 0) {
            val idx = subs.indexOfFirst { it.subscriptionId == target }
            if (idx >= 0) {
                binding.spinnerSim.setSelection(idx + 1)
            }
        }
    }

    private fun readSubscriptions(): List<SubscriptionInfo> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val sm = getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return sm.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex } ?: emptyList()
    }

    private fun hasRequiredPermissions(): Boolean {
        val need = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return need.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(need.toTypedArray())
    }
}
