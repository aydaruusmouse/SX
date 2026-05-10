package com.sarif.auto

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sarif.auto.databinding.FragmentAccountBinding
import java.text.DateFormat
import java.util.Date

class AccountFragment : Fragment() {

    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnLogout.setOnClickListener {
            (activity as? DashboardActivity)?.performLogout()
        }
        bindAccountInfo()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            bindAccountInfo()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            bindAccountInfo()
        }
    }

    private fun bindAccountInfo() {
        val prefs = SecurePrefs(requireContext())
        binding.textAccountPhone.text = formatPhoneDigits(prefs.registeredPhoneDigits)
        binding.textSessionStatus.setText(R.string.value_session_signed_in)

        binding.textLicenseStatus.setText(
            if (prefs.isLicenseActivated) R.string.value_license_active
            else R.string.value_license_inactive
        )

        val at = prefs.licenseActivatedAtMs
        binding.textLicenseDate.text = if (at > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(at))
        } else {
            getString(R.string.value_not_available)
        }

        val suffix = prefs.licenseKeyStoredSuffix
        binding.textLicenseKeyHint.text = if (suffix.isNotEmpty()) {
            getString(R.string.value_license_key_ends, suffix)
        } else {
            getString(R.string.value_license_key_unknown)
        }

        binding.textAppVersion.text = try {
            val ver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().packageManager.getPackageInfo(
                    requireContext().packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            }
            getString(R.string.account_app_version_line, ver ?: "—")
        } catch (_: Exception) {
            getString(R.string.account_app_version_line, "—")
        }
    }

    /** Spacing for readability; digits are stored without a leading +. */
    private fun formatPhoneDigits(digits: String): String {
        if (digits.isEmpty()) return getString(R.string.value_not_available)
        val d = digits.filter { it.isDigit() }
        return "+${d.chunked(3).joinToString(" ")}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
