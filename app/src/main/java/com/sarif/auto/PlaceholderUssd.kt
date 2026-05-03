package com.sarif.auto

import java.math.BigDecimal
import java.math.RoundingMode

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

    /**
     * Telesom/ZAAD *220* USSD expects a **whole shilling** amount — a decimal point often breaks or
     * rejects the session. Truncate toward zero; if that lands exactly on 500 but the real amount
     * was greater than 500, use 501 (carrier requires amount strictly above 500).
     */
    private fun amountDigitsForUssd(amount: BigDecimal): String {
        val minExclusive = BigDecimal("500")
        var whole = amount.setScale(0, RoundingMode.DOWN)
        if (whole <= minExclusive && amount > minExclusive) {
            whole = minExclusive.add(BigDecimal.ONE)
        }
        return whole.toPlainString()
    }

    /**
     * Value for `{AMOUNT}` in send steps: **integer only** (truncate toward zero). USSD often rejects
     * a decimal point (e.g. 1.4 → `1`, 5.9 → `5`). Telesom &gt;500 bump still applies via [amountDigitsForUssd].
     */
    fun formatSendAmountPlaceholder(amount: BigDecimal): String {
        if (amount <= BigDecimal.ZERO) return "0"
        return amountDigitsForUssd(amount)
    }

    private fun bankPinDigits(bankPin: String): String = bankPin.filter { it.isDigit() }

    /** Expands `{PIN}`, `{RECIPIENT}`, `{AMOUNT}` ([formatSendAmountPlaceholder]), `{BANK_PIN}`. */
    fun expandSendSteps(
        raw: String,
        pin: String,
        recipient: String,
        amount: BigDecimal,
        bankPin: String = ""
    ): List<String> {
        val amt = formatSendAmountPlaceholder(amount)
        val pinOut = pinForUssd(pin)
        val bankOut = bankPinDigits(bankPin)
        return raw.lines()
            .map { line ->
                line.trim()
                    .replace("{PIN}", pinOut)
                    .replace("{RECIPIENT}", recipient.trim())
                    .replace("{AMOUNT}", amt)
                    .replace("{BANK_PIN}", bankOut)
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
     * `*800#`-style transfer entry: system USSD popup first, then service PIN via Accessibility
     * (same session pattern as [isStackedSendMoneyOpener], but menu steps follow instead of one-shot code).
     */
    fun isEightHundredTransferOpener(line: String): Boolean {
        val t = line.trim().replace('\uFF0A', '*').replace('\u2217', '*')
        return t.contains("#") && t.contains("*800") && !t.contains("*220")
    }

    /** First send step that must open via system popup + AX service PIN before follow-up menu keys. */
    fun isAxPopupPinSendOpener(line: String): Boolean {
        return isStackedSendMoneyOpener(line) || isEightHundredTransferOpener(line)
    }

    /**
     * Same as [expandSendSteps], but if the template expands to **one** line only and it looks like
     * a `*220*…#` or `*800#` send opener, appends PIN digits as a second step.
     */
    fun expandSendStepsWithAutoPin(
        raw: String,
        pin: String,
        recipient: String,
        amount: BigDecimal,
        bankPin: String = ""
    ): List<String> {
        val expanded = expandSendSteps(raw, pin, recipient, amount, bankPin)
        val p = pinForUssd(pin)
        if (expanded.size != 1) return expanded
        val first = expanded[0]
        if (p.isNotEmpty() && isAxPopupPinSendOpener(first)) {
            return expanded + p
        }
        return expanded
    }
}
