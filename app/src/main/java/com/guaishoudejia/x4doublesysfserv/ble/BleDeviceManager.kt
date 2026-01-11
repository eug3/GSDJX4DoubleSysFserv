package com.guaishoudejia.x4doublesysfserv.ble

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * BLE 设备管理器 - 负责 MAC 地址的持久化存储和管理
 */
class BleDeviceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ble_device_manager",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val TAG = "BleDeviceManager"
        private const val KEY_SAVED_DEVICE_ADDRESS = "saved_device_address"
        private const val KEY_DEVICE_NAME = "saved_device_name"
        private const val KEY_LAST_CONNECTED_TIME = "last_connected_time"
    }

    /**
     * 保存设备 MAC 地址
     */
    fun saveDevice(address: String, name: String = "") {
        prefs.edit().apply {
            putString(KEY_SAVED_DEVICE_ADDRESS, address)
            putString(KEY_DEVICE_NAME, name)
            putLong(KEY_LAST_CONNECTED_TIME, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "保存设备: $address ($name)")
    }

    /**
     * 获取已保存的设备 MAC 地址
     */
    fun getSavedDeviceAddress(): String? {
        return prefs.getString(KEY_SAVED_DEVICE_ADDRESS, null)
    }

    /**
     * 获取已保存的设备名称
     */
    fun getSavedDeviceName(): String {
        return prefs.getString(KEY_DEVICE_NAME, "未命名设备") ?: "未命名设备"
    }

    /**
     * 获取上次连接时间
     */
    fun getLastConnectedTime(): Long {
        return prefs.getLong(KEY_LAST_CONNECTED_TIME, 0L)
    }

    /**
     * 检查是否有已保存的设备
     */
    fun hasSavedDevice(): Boolean {
        return getSavedDeviceAddress() != null
    }

    /**
     * 忘记设备（删除保存的 MAC 地址）
     */
    fun forgetDevice() {
        prefs.edit().apply {
            remove(KEY_SAVED_DEVICE_ADDRESS)
            remove(KEY_DEVICE_NAME)
            remove(KEY_LAST_CONNECTED_TIME)
            apply()
        }
        Log.d(TAG, "忘记设备")
    }

    /**
     * 清除所有数据
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "清除所有数据")
    }
}
