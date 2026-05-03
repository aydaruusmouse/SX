package com.sarif.auto.domain

import java.math.BigDecimal
import java.math.RoundingMode

object TransferCalculatorUseCase {

    private val TELESOM_MIN_TRANSFER_EXCLUSIVE = BigDecimal("500")

    enum class CurrencyHint {
        SLSH,
        USD,
        UNKNOWN
    }

    /**
     * Parsed from USSD so we only apply the >500 SLSH rule when the session is shilling-based.
     */
    fun currencyHintFromUssd(ussdText: String): CurrencyHint {
        if (ussdText.isBlank()) return CurrencyHint.UNKNOWN
        val t = ussdText.lowercase()
        val hasSlsh = Regex("(?i)slsh\\s*[\\d,]+").containsMatchIn(ussdText) ||
            (t.contains("slsh") && Regex("\\d").containsMatchIn(ussdText))
        val hasUsd = t.contains(" usd") ||
            Regex("(?i)[\\d,\\s]+\\.?\\d*\\s*usd\\b").containsMatchIn(ussdText) ||
            t.contains("$") ||
            t.contains("dollar")
        return when {
            hasUsd && !hasSlsh -> CurrencyHint.USD
            hasSlsh && !hasUsd -> CurrencyHint.SLSH
            else -> CurrencyHint.UNKNOWN
        }
    }

    /**
     * Resolves the amount to be used for the {AMOUNT} placeholder during transfer.
     * @param configuredSendAmount The explicitly configured amount, if any.
     * @param balanceJustParsed The balance parsed from the current USSD session.
     * @param lastParsedBalance The last known parsed balance from preferences.
     * @param transferReserve The amount to reserve/keep in the wallet.
     * @param ussdContext The raw text from the USSD session to infer currency.
     * @param skipTelesomSlshMinimum When true (e.g. balance checked with *800# / *888#), any
     * positive amount after reserve may transfer; the *222#-style **> 500** SLSH rule is not applied.
     */
    fun resolveTransferAmountForSend(
        configuredSendAmount: String,
        balanceJustParsed: BigDecimal,
        lastParsedBalance: String,
        transferReserve: String,
        ussdContext: String = "",
        skipTelesomSlshMinimum: Boolean = false
    ): BigDecimal {
        val hint = currencyHintFromUssd(ussdContext)
        val configured = configuredSendAmount.trim()
        val reserve = transferReserve.toBigDecimalOrNull()?.coerceAtLeast(BigDecimal.ZERO)
            ?: BigDecimal("100") // DEFAULT_TRANSFER_RESERVE

        if (balanceJustParsed > BigDecimal.ZERO) {
            val sweep = applyTelesomTransferRules(balanceJustParsed, reserve, hint, skipTelesomSlshMinimum)
            if (configured.isNotEmpty()) {
                val bd = configured.toBigDecimalOrNull()
                if (bd != null && bd > BigDecimal.ZERO) {
                    // Tiny “fixed” values (e.g. 4) are often stale; if balance-derived sweep is larger,
                    // prefer sweep (covers small USD wallets as well as SLSH).
                    val ignoreTinyFixed =
                        bd < BigDecimal.TEN &&
                            sweep > BigDecimal.ZERO &&
                            sweep > bd
                    // Configured amount can’t fit the wallet (e.g. balance $1 but field still says 4).
                    val configuredExceedsSweep =
                        sweep > BigDecimal.ZERO && bd > sweep
                    if (!ignoreTinyFixed && !configuredExceedsSweep) {
                        if (!skipTelesomSlshMinimum && hint != CurrencyHint.USD &&
                            bd <= TELESOM_MIN_TRANSFER_EXCLUSIVE
                        ) {
                            return BigDecimal.ZERO
                        }
                        return wholeUnitsDown(bd)
                    }
                }
            }
            return wholeUnitsDown(sweep)
        }

        if (configured.isNotEmpty()) {
            val bd = configured.toBigDecimalOrNull()
            if (bd != null && bd > BigDecimal.ZERO) {
                if (!skipTelesomSlshMinimum && hint != CurrencyHint.USD &&
                    bd <= TELESOM_MIN_TRANSFER_EXCLUSIVE
                ) {
                    return BigDecimal.ZERO
                }
                return wholeUnitsDown(bd)
            }
        }

        val last = lastParsedBalance.toBigDecimalOrNull()
        if (last != null && last > BigDecimal.ZERO) {
            return wholeUnitsDown(
                applyTelesomTransferRules(
                    last,
                    reserve,
                    currencyHintFromUssd(""),
                    skipTelesomSlshMinimum
                )
            )
        }

        return BigDecimal.ZERO
    }

    /** USSD `{AMOUNT}` is whole units only — 1.2 → 1 (truncate toward zero). */
    private fun wholeUnitsDown(amt: BigDecimal): BigDecimal {
        if (amt <= BigDecimal.ZERO) return BigDecimal.ZERO
        return amt.setScale(0, RoundingMode.DOWN)
    }

    /**
     * Leave [reserve] in wallet. For **SLSH**, enforce Telesom **> 500** and no full sweep where
     * applicable. For **USD**, only enforce positive amount after reserve.
     */
    private fun applyTelesomTransferRules(
        balance: BigDecimal,
        reserve: BigDecimal,
        currency: CurrencyHint,
        skipSlshMinimum: Boolean
    ): BigDecimal {
        val balW = balance.setScale(0, RoundingMode.DOWN)
        val resW = reserve.setScale(0, RoundingMode.DOWN)
        var amt = balW.subtract(resW).stripTrailingZeros()
        if (skipSlshMinimum && amt <= BigDecimal.ZERO && balW > BigDecimal.ZERO && resW >= balW) {
            // *800# / *888#: reserve defaults (e.g. 100) often exceed small USD balances — send full balance.
            amt = balW
        }
        if (amt <= BigDecimal.ZERO) return BigDecimal.ZERO
        if (currency == CurrencyHint.USD || skipSlshMinimum) {
            return amt
        }
        // SLSH + UNKNOWN: Telesom shilling minimum (>500)
        if (amt <= TELESOM_MIN_TRANSFER_EXCLUSIVE && balW > TELESOM_MIN_TRANSFER_EXCLUSIVE) {
            val bumped = TELESOM_MIN_TRANSFER_EXCLUSIVE.add(BigDecimal.ONE) // 501
            val maxSend = balW.subtract(BigDecimal.ONE).max(BigDecimal.ZERO)
            if (bumped <= maxSend) amt = bumped.min(maxSend)
        }
        if (amt <= BigDecimal.ZERO) return BigDecimal.ZERO
        if (amt <= TELESOM_MIN_TRANSFER_EXCLUSIVE) return BigDecimal.ZERO
        return amt
    }
}
