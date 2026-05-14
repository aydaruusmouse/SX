package com.sarif.auto

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs(context: Context) {

    private val appContext = context.applicationContext

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
        get() = prefs.getInt(KEY_INTERVAL, 2).coerceAtLeast(1)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(1)).apply()

    var stepDelayMs: Long
        get() = prefs.getLong(KEY_STEP_DELAY, DEFAULT_STEP_DELAY_MS).coerceAtLeast(80L)
        set(v) = prefs.edit().putLong(KEY_STEP_DELAY, v.coerceAtLeast(80L)).apply()

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

    /** USSD timing override; absent key → [UssdTimingKeys.Def.defaultMs]. */
    fun ussdTimingMs(def: UssdTimingKeys.Def): Long {
        val base = if (prefs.contains(def.key)) prefs.getLong(def.key, def.defaultMs) else def.defaultMs
        return if (def.isIntCount) base.coerceAtLeast(def.minMs.coerceAtLeast(1L))
        else base.coerceAtLeast(def.minMs)
    }

    fun setUssdTimingMs(def: UssdTimingKeys.Def, value: Long) {
        val coerced = if (def.isIntCount) value.coerceAtLeast(def.minMs.coerceAtLeast(1L))
        else value.coerceAtLeast(def.minMs)
        prefs.edit().putLong(def.key, coerced).apply()
    }

    fun ussdTimingCsv(key: String, defaultCsv: String): String {
        if (!prefs.contains(key)) return defaultCsv
        val s = prefs.getString(key, defaultCsv)?.trim().orEmpty()
        return if (s.isEmpty()) defaultCsv else s
    }

    fun setUssdTimingCsv(key: String, value: String) {
        prefs.edit().putString(key, value.trim()).apply()
    }

    /** Clears all USSD timing keys so defaults apply again. */
    fun clearUssdTimingOverrides() {
        val e = prefs.edit()
        UssdTimingKeys.DEFINITIONS.forEach { d -> e.remove(d.key) }
        e.remove(UssdTimingKeys.KEY_AX_INJECT_KICK_DELAYS_MS)
        e.remove(UssdTimingKeys.KEY_AX_READ_CAPTURE_TICK_MS)
        e.remove(UssdTimingKeys.KEY_AX_READ_CAPTURE_FINALIZE_MS)
        e.apply()
    }

    // —— License (gate before full app use; RS256 JWT from Laravel, verified offline) ——
    val isLicenseActivated: Boolean
        get() {
            val jwt = licenseJwt ?: return false
            if (!prefs.getBoolean(KEY_LICENSE_OK, false)) return false
            val deviceId = getOrCreateDeviceId()
            return when (LicenseJwtVerifier.verify(appContext, jwt, deviceId)) {
                LicenseJwtVerifier.Result.Ok -> {
                    prefs.edit().putLong(KEY_LICENSE_VERIFIED_AT, System.currentTimeMillis()).apply()
                    true
                }
                else -> {
                    clearLicenseState()
                    false
                }
            }
        }

    val licenseActivatedAtMs: Long
        get() = prefs.getLong(KEY_LICENSE_AT, 0L)

    /** Last characters stored from the activated license key (for display only). */
    val licenseKeyStoredSuffix: String
        get() = prefs.getString(KEY_LICENSE_LAST4, "")?.trim().orEmpty()

    val licenseJwt: String?
        get() = prefs.getString(KEY_LICENSE_JWT, null)?.trim()?.takeIf { it.isNotEmpty() }

    val licenseLastVerifiedWallMs: Long
        get() = prefs.getLong(KEY_LICENSE_VERIFIED_AT, 0L)

    /** Stable install-scoped device id for server binding (JWT `did` claim). */
    fun getOrCreateDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    /**
     * Persists license after server activation. Validates signature, issuer, audience, device, exp
     * (offline) before storing.
     */
    fun setLicenseFromServerJwt(jwt: String, rawKeyForDisplay: String): Boolean {
        val deviceId = getOrCreateDeviceId()
        val vr = LicenseJwtVerifier.verify(appContext, jwt.trim(), deviceId)
        if (vr != LicenseJwtVerifier.Result.Ok) {
            Log.e(TAG, "JWT verify failed after login: $vr (expected iss=${BuildConfig.LICENSE_JWT_ISS} aud=${BuildConfig.LICENSE_JWT_AUD})")
            return false
        }
        val k = rawKeyForDisplay.trim()
        val suffix = if (k.isEmpty()) "" else k.takeLast(4.coerceAtMost(k.length))
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_LICENSE_JWT, jwt.trim())
            .putBoolean(KEY_LICENSE_OK, true)
            .putLong(KEY_LICENSE_AT, now)
            .putLong(KEY_LICENSE_VERIFIED_AT, now)
            .putString(KEY_LICENSE_LAST4, suffix)
            .apply()
        return true
    }

    fun clearLicenseState() {
        prefs.edit()
            .remove(KEY_LICENSE_JWT)
            .putBoolean(KEY_LICENSE_OK, false)
            .remove(KEY_LICENSE_AT)
            .remove(KEY_LICENSE_LAST4)
            .remove(KEY_LICENSE_VERIFIED_AT)
            .apply()
    }

    /** Local demo keys only; production uses [setLicenseFromServerJwt]. */
    fun activateLicenseOfflineDemo(rawKey: String): Boolean {
        if (!LicenseVerifier.isValid(rawKey)) return false
        val k = rawKey.trim()
        prefs.edit()
            .remove(KEY_LICENSE_JWT)
            .putBoolean(KEY_LICENSE_OK, true)
            .putLong(KEY_LICENSE_AT, System.currentTimeMillis())
            .putString(KEY_LICENSE_LAST4, k.takeLast(4.coerceAtMost(k.length)))
            .apply()
        return true
    }

    // —— Auth: phone + password (portal creates user; app matches after remote login is wired) ——
    val registeredPhoneDigits: String
        get() = prefs.getString(KEY_AUTH_PHONE, "")?.trim().orEmpty()

    private val passwordHashSha256Hex: String
        get() = prefs.getString(KEY_AUTH_PASS_HASH, "") ?: ""

    val isSessionActive: Boolean
        get() = prefs.getBoolean(KEY_SESSION_ACTIVE, false)

    val isUserRegistered: Boolean
        get() = registeredPhoneDigits.isNotEmpty() && passwordHashSha256Hex.isNotEmpty()

    fun registerUser(phoneRaw: String, password: String): Boolean {
        val phone = normalizePhoneDigits(phoneRaw)
        if (phone.length !in AUTH_PHONE_MIN_LEN..AUTH_PHONE_MAX_LEN || password.length < 6) return false
        prefs.edit()
            .putString(KEY_AUTH_PHONE, phone)
            .putString(KEY_AUTH_PASS_HASH, CryptoUtil.sha256Hex(password))
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .apply()
        return true
    }

    fun loginUser(phoneRaw: String, password: String): Boolean {
        val phone = normalizePhoneDigits(phoneRaw)
        if (phone.isEmpty() || password.isEmpty()) return false
        if (phone != registeredPhoneDigits) return false
        if (CryptoUtil.sha256Hex(password) != passwordHashSha256Hex) return false
        prefs.edit().putBoolean(KEY_SESSION_ACTIVE, true).apply()
        return true
    }

    fun logout() {
        prefs.edit().putBoolean(KEY_SESSION_ACTIVE, false).apply()
    }

    companion object {
        private const val TAG = "SarifAuth"

        private const val AUTH_PHONE_MIN_LEN = 8
        private const val AUTH_PHONE_MAX_LEN = 15

        /** Digits only, for comparison (e.g. 252634000111 or leading 0 stripped by user habit). */
        fun normalizePhoneDigits(raw: String): String {
            val d = raw.filter { it.isDigit() }
            if (d.length > AUTH_PHONE_MAX_LEN) return d.takeLast(AUTH_PHONE_MAX_LEN)
            return d
        }

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
        private const val DEFAULT_STEP_DELAY_MS = 400L

        /** Default min gap between full balance USSD cycles when Accessibility PIN is used (ms). */
        const val DEFAULT_AX_USSD_MIN_CYCLE_GAP_MS = 2000L

        /** Floor for [axUssdMinCycleGapMs] (below this, *222# often fails on the next cycle). */
        private const val MIN_AX_USSD_CYCLE_GAP_MS = 250L

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

        private const val KEY_LICENSE_OK = "license_ok"
        private const val KEY_LICENSE_AT = "license_activated_at_ms"
        private const val KEY_LICENSE_LAST4 = "license_last4"
        private const val KEY_LICENSE_JWT = "license_jwt"
        private const val KEY_LICENSE_VERIFIED_AT = "license_last_verified_wall_ms"
        private const val KEY_DEVICE_ID = "license_device_install_id"
        private const val KEY_AUTH_PHONE = "auth_phone_digits"
        private const val KEY_AUTH_PASS_HASH = "auth_password_sha256"
        private const val KEY_SESSION_ACTIVE = "auth_session_active"
    }
}
