package com.sarif.auto

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Chooses login (phone + password + license) or dashboard. Launcher activity.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val prefs = SecurePrefs(this)
        val nextClass = when {
            !prefs.isUserRegistered -> LoginActivity::class.java
            !prefs.isSessionActive -> LoginActivity::class.java
            !prefs.isLicenseActivated -> LoginActivity::class.java
            else -> DashboardActivity::class.java
        }
        startActivity(Intent(this, nextClass))
        finish()
    }
}
