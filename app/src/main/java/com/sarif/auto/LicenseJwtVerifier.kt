package com.sarif.auto

import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object LicenseJwtVerifier {

    private const val CLOCK_ROLLBACK_TOLERANCE_MS = 120_000L

    sealed class Result {
        data object Ok : Result()
        data object MissingToken : Result()
        data object BadSignature : Result()
        data object WrongDevice : Result()
        data object Expired : Result()
        data object InvalidClaims : Result()
        data object ClockRollback : Result()
    }

    fun verify(context: Context, jwt: String?, deviceId: String): Result {
        if (jwt.isNullOrBlank()) return Result.MissingToken

        val prefs = SecurePrefs(context)
        val now = System.currentTimeMillis()
        val last = prefs.licenseLastVerifiedWallMs
        if (last > 0 && now < last - CLOCK_ROLLBACK_TOLERANCE_MS) {
            return Result.ClockRollback
        }

        val publicKey = loadRsaPublicPem(context) ?: return Result.BadSignature

        val algorithm = Algorithm.RSA256(publicKey, null)
        val verifier = JWT.require(algorithm)
            .withIssuer(BuildConfig.LICENSE_JWT_ISS)
            .withAudience(BuildConfig.LICENSE_JWT_AUD)
            .build()

        val decoded = try {
            verifier.verify(jwt)
        } catch (_: Exception) {
            return Result.BadSignature
        }

        val did = decoded.getClaim("did").asString()
        if (did.isNullOrBlank() || did != deviceId) {
            return Result.WrongDevice
        }

        val jwtPhone = decoded.getClaim("phone").asString()
        if (!jwtPhone.isNullOrBlank()) {
            val registered = prefs.registeredPhoneDigits
            if (registered.isNotEmpty() && jwtPhone != registered) {
                return Result.InvalidClaims
            }
        }

        val exp = decoded.expiresAt
        if (exp != null && exp.time < now - CLOCK_ROLLBACK_TOLERANCE_MS) {
            return Result.Expired
        }

        return Result.Ok
    }

    private fun loadRsaPublicPem(context: Context): RSAPublicKey? = try {
        val pem = context.assets.open("license_public.pem").bufferedReader().use { it.readText() }
        val stripped = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
        val encoded = Base64.getDecoder().decode(stripped)
        val kf = KeyFactory.getInstance("RSA")
        kf.generatePublic(X509EncodedKeySpec(encoded)) as RSAPublicKey
    } catch (_: Exception) {
        null
    }
}
