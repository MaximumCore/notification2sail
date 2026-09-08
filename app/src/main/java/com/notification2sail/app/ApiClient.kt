package com.notification2sail.app

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class Regatta(
    val url: String,
    val name: String?,
    val startDate: String?,
    val endDate: String?,
    val dateLegacy: String? = null
)

class RegattaApiClient(private val serverUrl: String) {
    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Helper function to safely post callbacks back to the UI thread
    private fun postOnMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    fun checkSubscriptionStatus(url: String, token: String, deviceId: String, callback: (isSubscribed: Boolean) -> Unit) {
        val cleanUrl = url.split("#!")[0]
        val request = Request.Builder()
            .url("$serverUrl/api/monitors?token=$token&deviceId=$deviceId")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postOnMain { callback(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(response.body?.string() ?: "")
                            val monitorsJson = json.getJSONArray("monitors")
                            var isSubscribed = false
                            for (i in 0 until monitorsJson.length()) {
                                val monitorUrl = monitorsJson.getJSONObject(i).getString("url").split("#!")[0]
                                if (monitorUrl == cleanUrl) {
                                    isSubscribed = true
                                    break
                                }
                            }
                            postOnMain { callback(isSubscribed) }
                            return
                        } catch (e: Exception) {
                            // Error parsing JSON or missing fields
                        }
                    }
                    postOnMain { callback(false) }
                }
            }
        })
    }

    fun executeSubscribe(url: String, token: String, deviceId: String, pageTitle: String, onResponse: (success: Boolean, errorMsg: String?) -> Unit) {
        val cleanUrl = url.split("#!")[0]
        val json = JSONObject().apply {
            put("fcmToken", token)
            put("deviceId", deviceId) // Send deviceId to server
            put("url", cleanUrl)
            put("name", pageTitle)
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url("$serverUrl/api/register").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postOnMain { onResponse(false, "OFFLINE") }
            }
            override fun onResponse(call: Call, response: Response) {
                postOnMain {
                    if (response.isSuccessful) onResponse(true, null)
                    else onResponse(false, "ERROR: ${response.code}")
                }
            }
        })
    }

    fun executeUnsubscribe(url: String, token: String, onComplete: () -> Unit) {
        val cleanUrl = url.split("#!")[0]
        val json = JSONObject().apply {
            put("fcmToken", token)
            put("url", cleanUrl)
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url("$serverUrl/api/unregister").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                postOnMain { onComplete() }
            }
        })
    }

    fun loadUserSettings(token: String, deviceId: String, onResult: (monitors: List<Regatta>, settings: JSONObject?) -> Unit, onFailure: () -> Unit) {
        val request = Request.Builder()
            .url("$serverUrl/api/monitors?token=$token&deviceId=$deviceId")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postOnMain { onFailure() }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(response.body?.string() ?: "")
                            val monitorsJson = json.optJSONArray("monitors") ?: JSONArray()
                            val settings = json.optJSONObject("settings")
                            
                            val monitorList = mutableListOf<Regatta>()
                            for (i in 0 until monitorsJson.length()) {
                                val item = monitorsJson.getJSONObject(i)
                                monitorList.add(Regatta(
                                    url = item.getString("url"),
                                    name = item.optString("name"),
                                    startDate = item.optString("startDate"),
                                    endDate = item.optString("endDate"),
                                    dateLegacy = item.optString("date")
                                ))
                            }
                            
                            postOnMain { onResult(monitorList, settings) }
                            return
                        } catch (e: Exception) {
                            // Error parsing response
                        }
                    }
                    postOnMain { onFailure() }
                }
            }
        })
    }

    fun saveUserSettings(token: String, details: Boolean, classes: Boolean, entries: Boolean, results: Boolean, noticeBoard: Boolean) {
        val json = JSONObject().apply {
            put("fcmToken", token)
            put("details", details)
            put("classes", classes)
            put("entries", entries)
            put("results", results)
            put("noticeBoard", noticeBoard)
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url("$serverUrl/api/settings").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }

    fun executeServerDelayedPush(token: String, callback: (Boolean) -> Unit) {
        val request = Request.Builder().url("$serverUrl/api/test-push?token=$token&delay=60").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { postOnMain { callback(false) } }
            override fun onResponse(call: Call, response: Response) { postOnMain { callback(true) } }
        })
    }
}
