package com.sarif.auto

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Pattern

object BalanceParser {

    /**
     * Tries to find a monetary amount in USSD response text.
     * Prefers patterns like "Balance: 1,234.50" or "$12".
     */
    fun parseLargestPositiveAmount(text: String): BigDecimal? {
        if (text.isBlank()) return null
        val normalized = text.replace('\u00a0', ' ')
        val candidates = mutableListOf<BigDecimal>()

        val p1 = Pattern.compile(
            """(?i)(?:balance|bal|available|usd|\$)\s*[:\-]?\s*([\d][\d,\s]*(?:\.\d+)?)"""
        )
        val m1 = p1.matcher(normalized)
        while (m1.find()) {
            parseNumber(m1.group(1))?.let { candidates.add(it) }
        }

        val p2 = Pattern.compile("""([\d][\d,\s]*\.\d{2})\b""")
        val m2 = p2.matcher(normalized)
        while (m2.find()) {
            parseNumber(m2.group(1))?.let { candidates.add(it) }
        }

        val p3 = Pattern.compile("""\b(\d{1,3}(?:,\d{3})+(?:\.\d+)?)\b""")
        val m3 = p3.matcher(normalized)
        while (m3.find()) {
            parseNumber(m3.group(1))?.let { candidates.add(it) }
        }

        val p4 = Pattern.compile("""(?i)\$\s*([\d][\d,\s]*(?:\.\d+)?)""")
        val m4 = p4.matcher(normalized)
        while (m4.find()) {
            parseNumber(m4.group(1))?.let { candidates.add(it) }
        }

        val p5 = Pattern.compile(
            """(?i)(?:haraaga|lacag|hareeraha|irirsinka|jeeg|zaad)\s*[:\-]?\s*([\d][\d,\s]*(?:\.\d+)?)\s*(?:usd|\$)?"""
        )
        val m5 = p5.matcher(normalized)
        while (m5.find()) {
            parseNumber(m5.group(1))?.let { candidates.add(it) }
        }

        val p6 = Pattern.compile("""(?i)([\d][\d,\s]*\.\d{2})\s*(?:usd|sl|\$)""")
        val m6 = p6.matcher(normalized)
        while (m6.find()) {
            parseNumber(m6.group(1))?.let { candidates.add(it) }
        }

        return candidates.filter { it > BigDecimal.ZERO }
            .maxOrNull()
            ?.setScale(2, RoundingMode.HALF_UP)
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
