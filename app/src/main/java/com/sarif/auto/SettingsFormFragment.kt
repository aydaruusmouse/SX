package com.sarif.auto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sarif.auto.databinding.FragmentSettingsFormBinding

/**
 * Shared USSD configuration form — hosted in [DashboardActivity] (bottom nav) and [SettingsActivity].
 */
class SettingsFormFragment : Fragment() {

    private var _binding: FragmentSettingsFormBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: SecurePrefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SecurePrefs(requireContext())
        binding.btnSaveSettings.setOnClickListener {
            saveFields()
            Toast.makeText(requireContext(), R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
        }
        loadFields()
        setupSimSpinner()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            loadFields()
            setupSimSpinner()
        }
    }

    override fun onPause() {
        super.onPause()
        if (_binding != null) {
            saveFields()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadFields() {
        binding.inputPin.setText(prefs.pin)
        binding.inputRecipient.setText(prefs.recipientMsisdn)
        binding.inputTransferAmount.setText(prefs.sendTransferAmountPlain)
        binding.inputTransferReserve.setText(prefs.transferReservePlain)
        binding.inputTransferBankPin.setText(prefs.transferBankPinPlain)
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
            requireContext(),
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
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val sm = requireContext().getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return sm.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex } ?: emptyList()
    }
}
