package com.sarif.auto

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Chooses login → license → dashboard. Launcher activity.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val prefs = SecurePrefs(this)
        val nextClass = when {
            !prefs.isUserRegistered -> LoginActivity::class.java
            !prefs.isSessionActive -> LoginActivity::class.java
            !prefs.isLicenseActivated -> LicenseActivity::class.java
            else -> DashboardActivity::class.java
        }
        val i = Intent(this, nextClass)
        if (nextClass == LoginActivity::class.java && !prefs.isUserRegistered) {
            i.putExtra(LoginActivity.EXTRA_START_REGISTER, true)
        }
        startActivity(i)
        finish()
    }
}
