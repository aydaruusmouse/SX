package com.sarif.auto

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.sarif.auto.databinding.ActivitySettingsBinding

/**
 * Stand-alone configuration screen (e.g. from accessibility settings). Content is [SettingsFormFragment].
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!requireAuth()) return

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.settingsFragmentHost, SettingsFormFragment())
            }
        }
    }

    private fun requireAuth(): Boolean {
        val p = SecurePrefs(this)
        if (!p.isUserRegistered || !p.isSessionActive) {
            startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            return false
        }
        if (!p.isLicenseActivated) {
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return false
        }
        return true
    }
}
