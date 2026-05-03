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
     * Placeholders: {RECIPIENT}, {AMOUNT}, {PIN}, {BANK_PIN} (see [transferBankPinPlain]).
     * [resolveTransferAmountForSend] picks `{AMOUNT}`: non-empty [sendTransferAmountPlain], else
     * [balanceJustParsed] / [lastParsedBalancePlain]. No silent fallback to 500.
     */
    var sendMoneySteps: String
        get() = prefs.getString(
            KEY_SEND_STEPS,
            DEFAULT_SEND_STEPS
        ) ?: DEFAULT_SEND_STEPS
        set(v) = prefs.edit().putString(KEY_SEND_STEPS, v).apply()

    /** Digits substituted for `{BANK_PIN}` in [sendMoneySteps] (bank / secondary PIN after amount). */
    var transferBankPinPlain: String
        get() = prefs.getString(KEY_TRANSFER_BANK_PIN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TRANSFER_BANK_PIN, v.trim()).apply()

    /**
     * Step-2 transfer amount for `{AMOUNT}`. **Leave empty** to use the balance value from the
     * current cycle ([lastParsedBalancePlain] / parsed amount).
     */
    var sendTransferAmountPlain: String
        get() = prefs.getString(KEY_SEND_TRANSFER_AMOUNT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SEND_TRANSFER_AMOUNT, v.trim()).apply()

    /** Last positive balance from USSD (updated whenever the monitor parses a balance). */
    var lastParsedBalancePlain: String
        get() = prefs.getString(KEY_LAST_PARSED_BALANCE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_PARSED_BALANCE, v.trim()).apply()

    /**
     * Shillings kept in the wallet when `{AMOUNT}` is taken from balance (empty transfer field).
     * For **SLSH** with *222#-style balance, Telesom requires send amount **> 500**; **USD** has no
     * that floor. Balance via *800# / *888# skips that minimum (any amount > 0 after reserve).
     */
    var transferReservePlain: String
        get() = prefs.getString(KEY_TRANSFER_RESERVE, DEFAULT_TRANSFER_RESERVE) ?: DEFAULT_TRANSFER_RESERVE
        set(v) = prefs.edit().putString(KEY_TRANSFER_RESERVE, v.trim()).apply()



    var loopIntervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, 5).coerceAtLeast(1)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(1)).apply()

    var stepDelayMs: Long
        get() = prefs.getLong(KEY_STEP_DELAY, DEFAULT_STEP_DELAY_MS).coerceAtLeast(200L)
        set(v) = prefs.edit().putLong(KEY_STEP_DELAY, v.coerceAtLeast(200L)).apply()

    /**
     * When [useAccessibilityUssdPin] is on, minimum pause after a full balance USSD run before the
     * next *222# (modem often errors if the gap is too short).
     */
    var axUssdMinCycleGapMs: Long
        get() = prefs.getLong(KEY_AX_USSD_MIN_GAP, DEFAULT_AX_USSD_MIN_CYCLE_GAP_MS)
            .coerceAtLeast(MIN_AX_USSD_CYCLE_GAP_MS)
        set(v) = prefs.edit().putLong(KEY_AX_USSD_MIN_GAP, v.coerceAtLeast(MIN_AX_USSD_CYCLE_GAP_MS))
            .apply()

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
        const val DEFAULT_SEND_STEPS =
            "*800#\n" +
                "{PIN}\n" +
                "5\n" +
                "2\n" +
                "4\n" +
                "1\n" +
                "{AMOUNT}\n" +
                "1\n" +
                "{BANK_PIN}\n" +
                "1"

        /** Default step-2 transfer amount for {AMOUNT}. */
        const val DEFAULT_SEND_TRANSFER_AMOUNT = "500"

        /** Default reserve when using balance as {AMOUNT} (not full sweep). */
        const val DEFAULT_TRANSFER_RESERVE = "100"

        /** Extra time after first USSD before sending PIN (some networks are slow). */
        private const val DEFAULT_STEP_DELAY_MS = 1500L

        /** Default min gap between full balance USSD cycles when Accessibility PIN is used (ms). */
        const val DEFAULT_AX_USSD_MIN_CYCLE_GAP_MS = 4500L

        /** Floor for [axUssdMinCycleGapMs] (below this, *222# often fails on the next cycle). */
        private const val MIN_AX_USSD_CYCLE_GAP_MS = 500L

        private const val KEY_PIN = "pin"
        private const val KEY_RECIPIENT = "recipient"
        private const val KEY_BALANCE_STEPS = "balance_steps"
        private const val KEY_SEND_STEPS = "send_steps"
        private const val KEY_TRANSFER_BANK_PIN = "transfer_bank_pin"
        private const val KEY_SEND_TRANSFER_AMOUNT = "send_transfer_amount"
        private const val KEY_LAST_PARSED_BALANCE = "last_parsed_balance"
        private const val KEY_TRANSFER_RESERVE = "transfer_reserve"
        private const val KEY_INTERVAL = "interval_sec"
        private const val KEY_STEP_DELAY = "step_delay_ms"
        private const val KEY_AX_USSD_MIN_GAP = "ax_ussd_min_cycle_gap_ms"
        private const val KEY_SUB_ID = "subscription_id"
        private const val KEY_USE_AX_USSD_PIN = "use_ax_ussd_pin"
    }
}
