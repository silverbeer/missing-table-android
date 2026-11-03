package com.missingtable.scorer.data.push

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Push bookkeeping (SB-1464). Its own DataStore, not `auth`: logout clears the
 * auth store, and the device id must outlive that long enough for logout to
 * DELETE it — and "already asked for notification permission" must outlive it
 * for good.
 */
private val Context.pushDataStore by preferencesDataStore(name = "push")

class PushPrefs(private val context: Context) : DeviceIdStore {

    private val fcmDeviceIdKey = stringPreferencesKey("fcm_device_id")
    private val lastRegisteredFidKey = stringPreferencesKey("last_registered_fid")
    private val askedNotifPermissionKey = booleanPreferencesKey("asked_notif_permission")

    override suspend fun fcmDeviceId(): String? = context.pushDataStore.data.first()[fcmDeviceIdKey]

    override suspend fun saveDevice(id: String, fid: String) {
        context.pushDataStore.edit {
            it[fcmDeviceIdKey] = id
            it[lastRegisteredFidKey] = fid
        }
    }

    override suspend fun clearDevice() {
        context.pushDataStore.edit {
            it.remove(fcmDeviceIdKey)
            it.remove(lastRegisteredFidKey)
        }
    }

    val askedNotifPermission = context.pushDataStore.data.map { it[askedNotifPermissionKey] ?: false }

    suspend fun setAskedNotifPermission() {
        context.pushDataStore.edit { it[askedNotifPermissionKey] = true }
    }
}

/** The slice of [PushPrefs] the registrar needs — a seam so it tests on the JVM. */
interface DeviceIdStore {
    suspend fun fcmDeviceId(): String?
    suspend fun saveDevice(id: String, fid: String)
    suspend fun clearDevice()
}
