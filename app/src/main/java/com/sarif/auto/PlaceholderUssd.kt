package com.sarif.auto

import java.math.BigDecimal

object PlaceholderUssd {

    /** PIN sent on USSD lines is digits-only (network expects numeric entry after prompts). */
    private fun pinDigits(pin: String): String = pin.filter { it.isDigit() }

    /** Digits from [pin], or [SecurePrefs.DEFAULT_SERVICE_PIN] if none (e.g. empty field). */
    fun resolvePinDigitsForUssd(pin: String): String {
        val d = pinDigits(pin)
        return d.ifEmpty { SecurePrefs.DEFAULT_SERVICE_PIN }
    }

    private fun pinForUssd(pin: String): String = resolvePinDigitsForUssd(pin)

    fun expandBalanceSteps(raw: String, pin: String, recipient: String): List<String> {
        val pinOut = pinForUssd(pin)
        return raw.lines()
            .map { line ->
                line.trim()
                    .replace("{PIN}", pinOut)
                    .replace("{RECIPIENT}", recipient.trim())
            }
            .filter { it.isNotEmpty() }
    }

    /**
     * Same as [expandBalanceSteps], but if the template expands to **exactly one** step (e.g. only `*222#`)
     * and [pin] contains digits, appends those digits as a **second** USSD so they run after the network
     * asks for PIN. If you already use two+ lines (e.g. `*222#` + `{PIN}`), nothing is added.
     */
    fun expandBalanceStepsWithAutoPin(raw: String, pin: String, recipient: String): List<String> {
        val expanded = expandBalanceSteps(raw, pin, recipient)
        val p = pinForUssd(pin)
        if (expanded.size != 1) return expanded
        return expanded + p
    }

    fun expandSendSteps(raw: String, pin: String, recipient: String, amount: BigDecimal): List<String> {
        val amt = amount.stripTrailingZeros().toPlainString()
        val pinOut = pinForUssd(pin)
        return raw.lines()
            .map { line ->
                line.trim()
                    .replace("{PIN}", pinOut)
                    .replace("{RECIPIENT}", recipient.trim())
                    .replace("{AMOUNT}", amt)
            }
            .filter { it.isNotEmpty() }
    }
}
