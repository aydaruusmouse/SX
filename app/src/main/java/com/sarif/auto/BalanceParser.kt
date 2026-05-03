package com.sarif.auto

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Pattern

object BalanceParser {

    /**
     * Tries to find a monetary amount in USSD response text.
     * Prefers specific balance patterns first, falling back to generic patterns.
     */
    fun parseLargestPositiveAmount(text: String): BigDecimal? {
        if (text.isBlank()) return null
        val normalized = text.replace('\u00a0', ' ')

        // Ignore transfer/receive receipts. While they contain the balance, parsing them here
        // tricks the app into abandoning the active USSD session, leaving it hanging on the screen.
        if (isTransferOrReceiveReceipt(normalized)) return null

        // ZAAD "Dooro Adeega" main menu: numbered lines like "2. Lacag Dirid" — no SLSH yet.
        // Generic patterns misread menu indices as amounts (e.g. 2.00) if we don't bail out here.
        if (looksLikeZaadDooroServiceMenuOnlyInternal(normalized)) return null

        // After a TelephonyManager failure (session busy, etc.) the combined dump can still contain
        // the service menu ("1. … 2. …") but no real balance — generic patterns then pick junk like 2.00.
        if (looksLikeUssdFailureWithoutBalanceLine(normalized)) return null

        val profile = com.sarif.auto.domain.CarrierProfileManager.getActiveProfile()

        val strictHits = mutableListOf<BigDecimal>()
        for (pattern in profile.strictBalanceRegexes) {
            val matcher = pattern.matcher(normalized)
            while (matcher.find()) {
                parseNumber(matcher.group(1))?.let {
                    if (it > BigDecimal.ZERO) strictHits.add(it)
                }
            }
        }
        if (strictHits.isNotEmpty()) {
            return strictHits.maxOrNull()!!.setScale(2, RoundingMode.HALF_UP)
        }

        val candidates = mutableListOf<BigDecimal>()
        for (pattern in profile.balanceRegexes) {
            val matcher = pattern.matcher(normalized)
            var foundAny = false
            while (matcher.find()) {
                parseNumber(matcher.group(1))?.let {
                    if (it > BigDecimal.ZERO) {
                        candidates.add(it)
                        foundAny = true
                    }
                }
            }
            if (foundAny && candidates.isNotEmpty()) {
                return candidates.last().setScale(2, RoundingMode.HALF_UP)
            }
        }

        return null
    }

    /** Telesom ZAAD main picker after PIN: must press "1" (Itus Hadhaaga) — not a balance screen yet. */
    fun looksLikeZaadDooroServiceMenuOnly(s: String): Boolean = looksLikeZaadDooroServiceMenuOnlyInternal(s)

    private fun looksLikeZaadDooroServiceMenuOnlyInternal(s: String): Boolean {
        val t = s.lowercase()
        if (!t.contains("dooro") || !t.contains("adeega")) return false
        if (Regex("""(?i)slsh\s*[\d,]+""").containsMatchIn(s)) return false
        return true
    }

    /**
     * True when logs show an API/modem failure but no carrier balance line (SLSH / Hadhaaga…).
     * Prevents treating menu noise or IME junk as balance.
     */
    fun looksLikeUssdFailureWithoutBalanceLine(s: String): Boolean {
        val low = s.lowercase()
        val hasFail = low.contains("fail:-") ||
            low.contains("failurecode") ||
            low.contains("session busy") ||
            low.contains("wrong format") ||
            low.contains("service error") ||
            low.contains("invalid menu") ||
            low.contains("invalid option") ||
            low.contains("select valid") ||
            low.contains("valid option") ||
            low.contains("fadlan dooro")
        if (!hasFail) return false
        return !Regex("""(?i)slsh\s*[\d,]+|hadhaageedu|hadhaagaagu|hadhaagu\s+.*\d""").containsMatchIn(s)
    }

    /**
     * True when the text is a transfer or receive receipt.
     * Parsing balance from these during an active USSD session will hijack the session.
     */
    private fun isTransferOrReceiveReceipt(s: String): Boolean {
        val low = s.lowercase()
        if (low.contains("ayaad u dirtay") || low.contains("ka heshay") || low.contains("waxaad slsh")) {
            return true
        }
        if (low.contains("waxaad") && low.contains("sariftay")) return true
        if (low.contains("tixraac") && (low.contains("sariftay") || low.contains("waxaad"))) return true
        return false
    }

    /**
     * True when the text indicates a terminal carrier/modem error that should abort the session.
     */
    fun isHardFailure(text: String): Boolean {
        val t = text.lowercase()
        if (t.isBlank()) return false
        return t.contains("connection problem") ||
            t.contains("invalid mmi") ||
            t.contains("aurtimeout") ||
            t.contains("aur timeout") ||
            t.contains("internal server error") ||
            t.contains("internal service error") ||
            t.contains("server error") ||
            t.contains("service error") ||
            t.contains("session busy") ||
            t.contains("network error") ||
            t.contains("try again later") ||
            t.contains("ussd_return_failure") ||
            t.contains("invalid menu") ||
            t.contains("invalid option") ||
            t.contains("select valid") ||
            t.contains("valid option") ||
            t.contains("fadlan dooro")
    }

    private fun parseNumber(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) return null
        val s = raw.replace(" ", "").replace(",", "")
        return try {
            BigDecimal(s)
        } catch (_: Exception) {
            null
        }
    }
}
