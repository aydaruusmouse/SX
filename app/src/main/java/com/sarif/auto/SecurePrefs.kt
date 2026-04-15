package com.sarif.auto

import android.content.Context
import java.math.BigDecimal
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "sarif_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var pin: String
        get() {
            val raw = prefs.getString(KEY_PIN, DEFAULT_SERVICE_PIN) ?: DEFAULT_SERVICE_PIN
            return raw.ifBlank { DEFAULT_SERVICE_PIN }
        }
        set(v) = prefs.edit().putString(KEY_PIN, v).apply()

    var recipientMsisdn: String
        get() = prefs.getString(KEY_RECIPIENT, "4671911") ?: "4671911"
        set(v) = prefs.edit().putString(KEY_RECIPIENT, v).apply()

    /** One USSD per line for balance. A single line like *222# is enough — service PIN is auto-sent as step 2. Use {PIN} on extra lines if needed. */
    var balanceUssdSteps: String
        get() = prefs.getString(
            KEY_BALANCE_STEPS,
            DEFAULT_BALANCE_STEPS
        ) ?: DEFAULT_BALANCE_STEPS
        set(v) = prefs.edit().putString(KEY_BALANCE_STEPS, v).apply()

    /**
     * One USSD per line after a positive balance is detected.
     * Placeholders: {RECIPIENT}, {AMOUNT}, {PIN}
     * [sendTransferAmountPlain] substitutes {AMOUNT} (fixed transfer, e.g. 500), not the parsed balance.
     * Example:
     * *220*{RECIPIENT}*{AMOUNT}#
     * {PIN}
     */
    var sendMoneySteps: String
        get() = prefs.getString(
            KEY_SEND_STEPS,
            DEFAULT_SEND_STEPS
        ) ?: DEFAULT_SEND_STEPS
        set(v) = prefs.edit().putString(KEY_SEND_STEPS, v).apply()

    /** Amount sent in step 2 for {AMOUNT} (e.g. 500 for *220*4671911*500#). */
    var sendTransferAmountPlain: String
        get() = prefs.getString(KEY_SEND_TRANSFER_AMOUNT, DEFAULT_SEND_TRANSFER_AMOUNT)
            ?: DEFAULT_SEND_TRANSFER_AMOUNT
        set(v) = prefs.edit().putString(KEY_SEND_TRANSFER_AMOUNT, v.trim()).apply()

    /** Positive amount for step 2 [sendMoneySteps] `{AMOUNT}`; falls back to [DEFAULT_SEND_TRANSFER_AMOUNT]. */
    fun sendTransferAmount(): BigDecimal {
        val bd = sendTransferAmountPlain.toBigDecimalOrNull()
        if (bd != null && bd > BigDecimal.ZERO) return bd
        return BigDecimal(DEFAULT_SEND_TRANSFER_AMOUNT)
    }

    var loopIntervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, 5).coerceAtLeast(1)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(1)).apply()

    var stepDelayMs: Long
        get() = prefs.getLong(KEY_STEP_DELAY, DEFAULT_STEP_DELAY_MS).coerceAtLeast(200L)
        set(v) = prefs.edit().putLong(KEY_STEP_DELAY, v.coerceAtLeast(200L)).apply()

    var subscriptionId: Int
        get() = prefs.getInt(KEY_SUB_ID, -1)
        set(v) = prefs.edit().putInt(KEY_SUB_ID, v).apply()

    /**
     * Use [UssdAccessibilityService] to type the PIN into the system USSD dialog.
     * Required on many devices where a second TelephonyManager USSD call for digits never completes.
     */
    var useAccessibilityUssdPin: Boolean
        get() = prefs.getBoolean(KEY_USE_AX_USSD_PIN, false)
        set(v) = prefs.edit().putBoolean(KEY_USE_AX_USSD_PIN, v).apply()

    companion object {
        /** Used when PIN field is empty, for USSD follow-up and {PIN} substitution. */
        const val DEFAULT_SERVICE_PIN = "6690"

        const val DEFAULT_BALANCE_STEPS = "*222#"
        const val DEFAULT_SEND_STEPS = "*220*{RECIPIENT}*{AMOUNT}#\n{PIN}"

        /** Default step-2 transfer amount for {AMOUNT}. */
        const val DEFAULT_SEND_TRANSFER_AMOUNT = "500"

        /** Extra time after first USSD before sending PIN (some networks are slow). */
        private const val DEFAULT_STEP_DELAY_MS = 1500L

        private const val KEY_PIN = "pin"
        private const val KEY_RECIPIENT = "recipient"
        private const val KEY_BALANCE_STEPS = "balance_steps"
        private const val KEY_SEND_STEPS = "send_steps"
        private const val KEY_SEND_TRANSFER_AMOUNT = "send_transfer_amount"
        private const val KEY_INTERVAL = "interval_sec"
        private const val KEY_STEP_DELAY = "step_delay_ms"
        private const val KEY_SUB_ID = "subscription_id"
        private const val KEY_USE_AX_USSD_PIN = "use_ax_ussd_pin"
    }
}
