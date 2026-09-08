package com.notification2sail.app

import android.content.Context
import android.util.Log
import java.util.UUID

object DeviceUuidManager {
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("UiSettings", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("permanent_device_id", null)
        if (deviceId == null) {
            deviceId = "user-" + UUID.randomUUID().toString()
            prefs.edit().putString("permanent_device_id", deviceId).apply()
            Log.d("DeviceUuidManager", "New permanent device ID generated: $deviceId")
        } else {
            Log.d("DeviceUuidManager", "Existing permanent device ID loaded: $deviceId")
        }
        return deviceId
    }
}
