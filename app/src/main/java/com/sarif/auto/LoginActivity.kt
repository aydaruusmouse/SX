package com.sarif.auto

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sarif.auto.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

/**
 * Sign-in with phone, password, and license key (all issued by your admin / Laravel).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: SecurePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs(this)

        binding.textLoginTitle.setText(R.string.login_title)
        binding.textLoginSubtitle.setText(R.string.login_subtitle_with_license)
        binding.btnAuthSubmit.setText(R.string.btn_login)
        binding.layoutConfirmPassword.visibility = View.GONE

        if (prefs.isUserRegistered) {
            binding.inputPhone.setText(prefs.registeredPhoneDigits)
        }

        binding.btnAuthSubmit.setOnClickListener { submit() }
    }

    private fun submit() {
        val phoneRaw = binding.inputPhone.text?.toString().orEmpty()
        val pass = binding.inputPassword.text?.toString().orEmpty()
        val licenseKey = binding.inputLicenseKey.text?.toString().orEmpty().trim()
        if (phoneRaw.isBlank() || pass.isEmpty() || licenseKey.isEmpty()) {
            Toast.makeText(this, R.string.toast_auth_fill_fields_license, Toast.LENGTH_SHORT).show()
            return
        }
        val phone = SecurePrefs.normalizePhoneDigits(phoneRaw)
        if (phone.length !in 8..15 || pass.length < 6) {
            Toast.makeText(this, R.string.toast_auth_weak, Toast.LENGTH_LONG).show()
            return
        }

        prefs.getOrCreateDeviceId()
        binding.btnAuthSubmit.isEnabled = false
        lifecycleScope.launch {
            try {
                val token = AuthApi.login(
                    BuildConfig.LICENSE_API_BASE_URL,
                    phoneRaw,
                    pass,
                    licenseKey,
                    prefs.getOrCreateDeviceId()
                )
                if (!prefs.registerUser(phoneRaw, pass)) {
                    Toast.makeText(this@LoginActivity, R.string.toast_auth_weak, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (!prefs.setLicenseFromServerJwt(token, licenseKey)) {
                    Toast.makeText(this@LoginActivity, R.string.toast_license_invalid, Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(this@LoginActivity, R.string.toast_auth_ok, Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this@LoginActivity, DashboardActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                )
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Login API failed (baseUrl=${BuildConfig.LICENSE_API_BASE_URL})", e)
                val networkRoot = networkFailureRoot(e)
                val toastText = if (networkRoot != null) {
                    getString(R.string.toast_license_network, humanizeLoginError(networkRoot))
                } else {
                    val detail = e.message?.trim().orEmpty().ifEmpty { e.javaClass.simpleName }
                    getString(R.string.toast_login_failed_detail, detail)
                }
                Toast.makeText(this@LoginActivity, toastText, Toast.LENGTH_LONG).show()
            } finally {
                binding.btnAuthSubmit.isEnabled = true
            }
        }
    }

    /** OkHttp often wraps [ConnectException] / timeouts in [java.io.IOException]. */
    private fun networkFailureRoot(e: Throwable): Exception? {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is java.net.ConnectException,
                is java.net.UnknownHostException,
                is java.net.SocketTimeoutException -> return t as Exception
            }
            t = t.cause
        }
        return null
    }

    private fun humanizeLoginError(e: Exception): String {
        val raw = e.message?.trim().orEmpty().ifEmpty { e.javaClass.simpleName }
        val extra = when (e) {
            is java.net.ConnectException ->
                " — Laravel: php artisan serve --host=0.0.0.0 --port=8001. " +
                    "USB phone: local.properties license.api.mode=adb_reverse then adb reverse tcp:8001 tcp:8001. " +
                    "Wi‑Fi phone: license.api.base.url=http://MAC_LAN_IP:8001 (not 192.168.x.1 router)."
            is java.net.UnknownHostException ->
                " — Check BuildConfig LICENSE_API_BASE_URL."
            is java.net.SocketTimeoutException ->
                " — Server not reachable. If base URL is 127.0.0.1, run: adb reverse tcp:8001 tcp:8001. " +
                    "Else set license.api.base.url to your Mac Wi‑Fi IP."
            else -> ""
        }
        return raw + extra
    }

    companion object {
        private const val TAG = "SarifAuth"
    }
}
