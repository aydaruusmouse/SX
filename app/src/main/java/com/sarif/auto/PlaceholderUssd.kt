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

    /**
     * ZAAD / Telesom money transfer stacked code (`*220*…#`). Not `*222#` (that is balance menu only).
     */
    fun isStackedSendMoneyOpener(line: String): Boolean {
        val t = line.trim()
        return t.contains("#") && t.contains("*220")
    }

    /**
     * Same as [expandSendSteps], but if the template expands to **one** line only and it looks like
     * a `*220*…#` send opener, appends PIN digits as a second step.
     */
    fun expandSendStepsWithAutoPin(
        raw: String,
        pin: String,
        recipient: String,
        amount: BigDecimal
    ): List<String> {
        val expanded = expandSendSteps(raw, pin, recipient, amount)
        val p = pinForUssd(pin)
        if (expanded.size != 1) return expanded
        val first = expanded[0]
        if (p.isNotEmpty() && isStackedSendMoneyOpener(first)) {
            return expanded + p
        }
        return expanded
    }
}
