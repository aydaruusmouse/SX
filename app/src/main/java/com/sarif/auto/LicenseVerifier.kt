package com.sarif.auto

/**
 * Offline license gate. Replace with server validation when you have a backend.
 * Demo key ships for development; production keys: `SARIF-` prefix and minimum length.
 */
object LicenseVerifier {

    private val DEMO_KEYS = setOf(
        "SARIF-DEMO-ACTIVATE",
        "SARIF-DEMO-2024",
    )

    fun isValid(rawKey: String): Boolean {
        val k = rawKey.trim().uppercase().replace("\\s+".toRegex(), "")
        if (k.isEmpty()) return false
        if (k in DEMO_KEYS) return true
        if (!k.startsWith("SARIF-")) return false
        val body = k.removePrefix("SARIF-").replace("-", "")
        if (body.length < 10) return false
        return body.all { it.isLetterOrDigit() }
    }
}
