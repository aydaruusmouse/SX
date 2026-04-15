package com.sarif.auto

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.sarif.auto.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SecurePrefs
    private lateinit var logStore: UssdLogStore
    private val servedAdapter = ServedRequestsAdapter()

    private val balanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BalanceUiContract.ACTION_BALANCE_UPDATE) return
            val v = intent.getStringExtra(BalanceUiContract.EXTRA_BALANCE_PLAIN) ?: return
            binding.textLastBalance.text = getString(R.string.label_last_balance_value, v)
        }
    }

    private val ussdUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BalanceUiContract.ACTION_USSD_BUSY -> {
                    refreshServingUi()
                }
                BalanceUiContract.ACTION_USSD_STEP -> {
                    val step = intent.getIntExtra(BalanceUiContract.EXTRA_STEP_INDEX, 0)
                    val fail = intent.getBooleanExtra(BalanceUiContract.EXTRA_RESULT_IS_FAILURE, false)
                    val body = intent.getStringExtra(BalanceUiContract.EXTRA_STEP_BODY).orEmpty().take(2000)
                    val opener = intent.getStringExtra(BalanceUiContract.EXTRA_REQUEST_OPENER).orEmpty()
                    val sim = intent.getStringExtra(BalanceUiContract.EXTRA_SIM_LABEL).orEmpty()
                    logStore.append(
                        UssdLogEntry(
                            id = System.nanoTime(),
                            stepIndex = step,
                            timeMs = System.currentTimeMillis(),
                            requestOpener = opener.ifBlank { "*" },
                            simLabel = sim,
                            ok = !fail,
                            response = body
                        )
                    )
                    refreshServedList()
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
        ContextCompat.registerReceiver(
            this,
            balanceReceiver,
            IntentFilter(BalanceUiContract.ACTION_BALANCE_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val ussdFilter = IntentFilter().apply {
            addAction(BalanceUiContract.ACTION_USSD_BUSY)
            addAction(BalanceUiContract.ACTION_USSD_STEP)
        }
        ContextCompat.registerReceiver(
            this,
            ussdUiReceiver,
            ussdFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshServingUi()
    }

    override fun onStop() {
        try {
            unregisterReceiver(balanceReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            unregisterReceiver(ussdUiReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStop()
    }

    private fun loadFields() {
        binding.inputPin.setText(prefs.pin)
        binding.inputRecipient.setText(prefs.recipientMsisdn)
        binding.inputTransferAmount.setText(prefs.sendTransferAmountPlain)
        binding.inputInterval.setText(prefs.loopIntervalSeconds.toString())
        binding.inputStepDelay.setText(prefs.stepDelayMs.toString())
        binding.inputBalanceSteps.setText(prefs.balanceUssdSteps)
        binding.inputSendSteps.setText(prefs.sendMoneySteps)
        binding.switchAxUssdPin.isChecked = prefs.useAccessibilityUssdPin
    }

    private fun saveFields() {
        prefs.pin = binding.inputPin.text?.toString().orEmpty()
        prefs.recipientMsisdn = binding.inputRecipient.text?.toString()?.trim().orEmpty()
            .ifEmpty { "4671911" }
        prefs.sendTransferAmountPlain = binding.inputTransferAmount.text?.toString()?.trim().orEmpty()
            .ifEmpty { SecurePrefs.DEFAULT_SEND_TRANSFER_AMOUNT }
        prefs.loopIntervalSeconds = binding.inputInterval.text?.toString()?.toIntOrNull() ?: 5
        prefs.stepDelayMs = binding.inputStepDelay.text?.toString()?.toLongOrNull() ?: 1500L
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
