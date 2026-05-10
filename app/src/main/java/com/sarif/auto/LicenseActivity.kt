package com.sarif.auto

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sarif.auto.databinding.ActivityLicenseBinding

class LicenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicenseBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ver = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (_: Exception) {
            null
        }
        binding.textLicenseVersion.text = getString(R.string.version_line, ver ?: "—")

        val prefs = SecurePrefs(this)
        if (!prefs.isUserRegistered || !prefs.isSessionActive) {
            startActivity(
                Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
            return
        }

        binding.btnActivateLicense.setOnClickListener {
            val key = binding.inputLicenseKey.text?.toString().orEmpty()
            if (prefs.activateLicense(key)) {
                Toast.makeText(this, R.string.toast_license_ok, Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, DashboardActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                )
                finish()
            } else {
                Toast.makeText(this, R.string.toast_license_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }
}
