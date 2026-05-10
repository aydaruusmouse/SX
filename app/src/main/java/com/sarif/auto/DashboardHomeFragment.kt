package com.sarif.auto

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sarif.auto.databinding.FragmentDashboardHomeBinding

class DashboardHomeFragment : Fragment() {

    private var _binding: FragmentDashboardHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnServing.setOnClickListener {
            (activity as? DashboardActivity)?.performToggleMonitor()
        }
        binding.btnOpenAccessibility.setOnClickListener {
            (activity as? DashboardActivity)?.openAccessibilitySettings()
        }
        bindDashboardMeta()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            bindDashboardMeta()
        }
    }

    private fun bindDashboardMeta() {
        val prefs = SecurePrefs(requireContext())
        binding.textMetricCycle.text = getString(R.string.dashboard_cycle_every, prefs.loopIntervalSeconds)
        binding.textMetricLicense.setText(
            if (prefs.isLicenseActivated) R.string.value_license_active
            else R.string.value_license_inactive
        )
        binding.progressMetricLicense.progress = if (prefs.isLicenseActivated) 100 else 38
    }

    fun setMonitorRunning(running: Boolean) {
        if (_binding == null) return
        val ctx = binding.root.context
        binding.btnServing.text = if (running) {
            ctx.getString(R.string.btn_stop_serving)
        } else {
            ctx.getString(R.string.btn_start_serving)
        }
        binding.textMetricMonitor.setText(
            if (running) R.string.dashboard_status_running else R.string.dashboard_status_idle
        )
        binding.progressMetricMonitor.progress = if (running) 100 else 32
    }

    fun setBalanceLabel(text: CharSequence) {
        if (_binding == null) return
        binding.textMetricBalance.text = text
        val hasAmount = text.any { it.isDigit() }
        binding.progressMetricBalance.progress = if (hasAmount) 88 else 24
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
