package com.sarif.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sarif.auto.databinding.ActivityDashboardBinding
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "SarifAuto"
        private const val TAG_HOME = "dash_home"
        private const val TAG_LOG = "dash_log"
        private const val TAG_SETTINGS = "dash_settings"
        private const val TAG_ACCOUNT = "dash_account"
    }

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var prefs: SecurePrefs
    private lateinit var logStore: UssdLogStore

    private lateinit var homeFragment: DashboardHomeFragment
    private lateinit var logFragment: UssdLogFragment
    private lateinit var settingsFragment: SettingsFormFragment
    private lateinit var accountFragment: AccountFragment

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
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!requireAuth()) return

        prefs = SecurePrefs(this)
        logStore = UssdLogStore(this)

        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            homeFragment = DashboardHomeFragment()
            logFragment = UssdLogFragment()
            settingsFragment = SettingsFormFragment()
            accountFragment = AccountFragment()
            supportFragmentManager.commit {
                add(R.id.fragmentContainer, homeFragment, TAG_HOME)
                add(R.id.fragmentContainer, logFragment, TAG_LOG)
                add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                add(R.id.fragmentContainer, accountFragment, TAG_ACCOUNT)
                hide(logFragment)
                hide(settingsFragment)
                hide(accountFragment)
            }
        } else {
            homeFragment = supportFragmentManager.findFragmentByTag(TAG_HOME) as DashboardHomeFragment
            logFragment = supportFragmentManager.findFragmentByTag(TAG_LOG) as UssdLogFragment
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as SettingsFormFragment
            accountFragment = supportFragmentManager.findFragmentByTag(TAG_ACCOUNT) as AccountFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showTab(Tab.HOME)
                    true
                }
                R.id.nav_activity -> {
                    showTab(Tab.LOG)
                    true
                }
                R.id.nav_settings -> {
                    showTab(Tab.SETTINGS)
                    true
                }
                R.id.nav_account -> {
                    showTab(Tab.ACCOUNT)
                    true
                }
                else -> false
            }
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        } else {
            when (binding.bottomNav.selectedItemId) {
                R.id.nav_activity -> showTab(Tab.LOG)
                R.id.nav_settings -> showTab(Tab.SETTINGS)
                R.id.nav_account -> showTab(Tab.ACCOUNT)
                else -> showTab(Tab.HOME)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    com.sarif.auto.domain.UssdStateObserver.balanceUpdates.collect { v ->
                        homeFragment.setBalanceLabel(getString(R.string.label_last_balance_value, v))
                    }
                }
                launch {
                    com.sarif.auto.domain.UssdStateObserver.ussdBusy.collect {
                        refreshMonitorUi()
                    }
                }
                launch {
                    com.sarif.auto.domain.UssdStateObserver.backoffActive.collect { active ->
                        if (active) {
                            if (BalanceMonitorService.isServiceRunning) {
                                Log.d(TAG, "rate limit backoff (no toast — avoid stealing focus from USSD)")
                            } else {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    R.string.toast_rate_limit_backoff,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                launch {
                    com.sarif.auto.domain.UssdStateObserver.ussdSteps.collect { event ->
                        logStore.append(
                            UssdLogEntry(
                                id = System.nanoTime(),
                                stepIndex = event.stepIndex,
                                timeMs = System.currentTimeMillis(),
                                requestOpener = event.requestOpener.ifBlank { "*" },
                                simLabel = event.simLabel,
                                ok = !event.isFailure,
                                response = event.body.take(2000)
                            )
                        )
                        logFragment.refreshList()
                    }
                }
            }
        }
    }

    private enum class Tab { HOME, LOG, SETTINGS, ACCOUNT }

    private fun showTab(tab: Tab) {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            hide(homeFragment)
            hide(logFragment)
            hide(settingsFragment)
            hide(accountFragment)
            when (tab) {
                Tab.HOME -> {
                    show(homeFragment)
                    binding.toolbar.setTitle(R.string.app_name)
                }
                Tab.LOG -> {
                    show(logFragment)
                    binding.toolbar.setTitle(R.string.nav_activity)
                    logFragment.refreshList()
                }
                Tab.SETTINGS -> {
                    show(settingsFragment)
                    binding.toolbar.setTitle(R.string.nav_settings)
                }
                Tab.ACCOUNT -> {
                    show(accountFragment)
                    binding.toolbar.setTitle(R.string.nav_account)
                }
            }
        }
    }

    fun performLogout() {
        SecurePrefs(this).logout()
        Toast.makeText(this, R.string.toast_logged_out, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    fun performToggleMonitor() {
        if (BalanceMonitorService.isServiceRunning) {
            BalanceMonitorService.stop(this)
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
            refreshMonitorUi()
            return
        }
        startMonitorFromUi()
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requireAuth(): Boolean {
        val p = SecurePrefs(this)
        if (!p.isUserRegistered || !p.isSessionActive) {
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
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

    private fun startMonitorFromUi() {
        if (!hasRequiredPermissions()) {
            requestNeededPermissions()
            return
        }
        prefs = SecurePrefs(this)
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
        refreshMonitorUi()
    }

    private fun refreshMonitorUi() {
        val running = BalanceMonitorService.isServiceRunning
        if (::homeFragment.isInitialized) {
            homeFragment.setMonitorRunning(running)
        }
    }

    private fun loadBalanceLabelOnHome() {
        prefs = SecurePrefs(this)
        val lastBal = prefs.lastParsedBalancePlain
        if (lastBal.isNotBlank() && ::homeFragment.isInitialized) {
            homeFragment.setBalanceLabel(getString(R.string.label_last_balance_value, lastBal))
        }
    }

    override fun onStart() {
        super.onStart()
        refreshMonitorUi()
        loadBalanceLabelOnHome()
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
