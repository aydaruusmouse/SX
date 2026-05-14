package com.sarif.auto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object LicenseApi {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun activate(baseUrl: String, licenseKey: String, deviceId: String): String =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/api/v1/license/activate"
            val body = JSONObject()
                .put("license_key", licenseKey)
                .put("device_id", deviceId)
                .toString()
                .toRequestBody(jsonMedia)
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    throw IOException(msg ?: "HTTP ${resp.code}")
                }
                JSONObject(text).getString("token")
            }
        }
}
