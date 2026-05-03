package com.sarif.auto.domain

import java.util.regex.Pattern

/**
 * Defines parsing rules for a specific carrier or region (e.g., ZAAD Somaliland).
 * By externalizing these rules, they can be updated via JSON without updating the app.
 */
data class CarrierProfile(
    val id: String,
    val description: String,
    /** Hadhaaga / SLSH lines only — used first so menu noise (e.g. “jeeg 4”, “[-zaad”) never wins. */
    val strictBalanceRegexes: List<Pattern>,
    /** Broader fallbacks when strict patterns find nothing. */
    val balanceRegexes: List<Pattern>,
    val requiresExplicitMenuOne: Boolean = false,
    val minimumTransferAmount: String = "500" // e.g. TELESOM > 500
)

object CarrierProfileManager {
    // Default ZAAD Somaliland profile
    val defaultProfile = CarrierProfile(
        id = "zaad_somaliland",
        description = "Telesom ZAAD default parsing patterns",
        strictBalanceRegexes = listOf(
            Pattern.compile("""(?i)(?:hadhaageedu|hadhaaga|hadhaagu|hadhaagaagu|hadhaagaaga|haraaga)[^\d\n]*?([\d][\d,]*(?:\.\d+)?)"""),
            Pattern.compile("""(?i)slsh([\d][\d,]*(?:\.\d+)?)""")
        ),
        balanceRegexes = listOf(
            // No “zaad” here: it matches UI/branding and the next digit can be a menu index (e.g. 4).
            Pattern.compile("""(?i)(?:haraaga|lacag|hareeraha|irirsinka|jeeg)\s*[:\-]?\s*([\d][\d,\s]*(?:\.\d+)?)\s*(?:usd|\$)?"""),
            Pattern.compile("""(?i)(?:balance|bal|available|usd|\$)\s*[:\-]?\s*([\d][\d,\s]*(?:\.\d+)?)"""),
            Pattern.compile("""(?i)([\d][\d,\s]*\.\d{2})\s*(?:usd|sl|\$)"""),
            Pattern.compile("""(?i)\$\s*([\d][\d,\s]*(?:\.\d+)?)"""),
            Pattern.compile("""([\d][\d,\s]*\.\d{2})\b"""),
            Pattern.compile("""(?<![\d])([\d][\d,\s]*\.\d{1,4})(?!\d)"""),
            Pattern.compile("""\b(\d{1,3}(?:,\d{3})+(?:\.\d+)?)\b""")
        ),
        requiresExplicitMenuOne = true
    )

    /**
     * In a full implementation, this could fetch profiles from a server or local DB.
     */
    fun getActiveProfile(): CarrierProfile {
        return defaultProfile
    }
}
