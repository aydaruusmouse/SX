package com.sarif.auto

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sarif.auto.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: SecurePrefs
    private var registerMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs(this)

        registerMode = intent.getBooleanExtra(EXTRA_START_REGISTER, false) || !prefs.isUserRegistered
        applyModeUi()

        binding.btnAuthSubmit.setOnClickListener { submit() }
    }

    private fun applyModeUi() {
        if (registerMode) {
            binding.textLoginTitle.setText(R.string.register_title)
            binding.textLoginSubtitle.setText(R.string.register_subtitle)
            binding.btnAuthSubmit.setText(R.string.btn_register)
            binding.layoutConfirmPassword.visibility = View.VISIBLE
        } else {
            binding.textLoginTitle.setText(R.string.login_title)
            binding.textLoginSubtitle.setText(R.string.login_subtitle)
            binding.btnAuthSubmit.setText(R.string.btn_login)
            binding.layoutConfirmPassword.visibility = View.GONE
        }
    }

    private fun submit() {
        val phone = binding.inputPhone.text?.toString().orEmpty()
        val pass = binding.inputPassword.text?.toString().orEmpty()
        if (phone.isBlank() || pass.isEmpty()) {
            Toast.makeText(this, R.string.toast_auth_fill_fields, Toast.LENGTH_SHORT).show()
            return
        }
        if (registerMode) {
            val confirm = binding.inputConfirmPassword.text?.toString().orEmpty()
            if (confirm != pass) {
                Toast.makeText(this, R.string.toast_auth_password_mismatch, Toast.LENGTH_SHORT).show()
                return
            }
            if (!prefs.registerUser(phone, pass)) {
                Toast.makeText(this, R.string.toast_auth_weak, Toast.LENGTH_LONG).show()
                return
            }
        } else {
            if (!prefs.loginUser(phone, pass)) {
                Toast.makeText(this, R.string.toast_auth_failed, Toast.LENGTH_LONG).show()
                return
            }
        }
        Toast.makeText(this, R.string.toast_auth_ok, Toast.LENGTH_SHORT).show()
        val next = if (!prefs.isLicenseActivated) {
            Intent(this, LicenseActivity::class.java)
        } else {
            Intent(this, DashboardActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        startActivity(next)
        finish()
    }

    companion object {
        const val EXTRA_START_REGISTER = "start_register"
    }
}
