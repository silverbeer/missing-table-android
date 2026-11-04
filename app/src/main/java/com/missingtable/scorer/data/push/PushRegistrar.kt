package com.missingtable.scorer.data.push

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.missingtable.scorer.data.api.FcmDeviceRequest
import com.missingtable.scorer.data.api.MtApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where this install's identity for FCM comes from — Firebase in the app, a
 * fake on the JVM.
 */
interface FidSource {
    /** Make sure FCM knows this installation, then return its FID; null if unavailable. */
    suspend fun fid(): String?

    /**
     * Invalidate this installation's FID, so sends to it fail and the server
     * prunes the row as expired. Best-effort; returns whether it happened.
     */
    suspend fun deleteInstallation(): Boolean
}

/**
 * Ties this install to the signed-in user for push (SB-1464).
 *
 * The server targets FCM by Firebase Installation ID, not by registration
 * token, so the FID is what we send. It never comes back: the server returns a
 * row id, and that id is what logout deletes.
 *
 * Every step is best-effort. An emulator without Play services, a build with
 * no Firebase config, or a flaky network must never take the app down — push
 * just stays off until the next successful register().
 *
 * Sessions are numbered. [teardown] bumps the number and cancels in-flight
 * registers; a register that started under an older number never POSTs, and
 * never saves what a POST returned. That is what stops a register queued
 * behind logout re-attaching the device under the outgoing token.
 */
class PushRegistrar(
    private val api: MtApi,
    private val store: DeviceIdStore,
    private val isLoggedIn: suspend () -> Boolean,
    private val fidSource: FidSource,
    private val appVersion: String,
    private val deviceLabel: String,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val generation = AtomicInteger(0)
    private val tearingDown = AtomicBoolean(false)
    private val jobs: MutableSet<Job> = Collections.synchronizedSet(mutableSetOf())

    // A POST that may have reached the server without our seeing the reply.
    private val postInFlight = AtomicBoolean(false)

    // FID already POSTed in this process for this session; guarded by mutex.
    private var postedFid: String? = null
    private var postedGeneration = -1

    private fun current(gen: Int) = !tearingDown.get() && generation.get() == gen

    /** Register (or refresh) this device. Returns whether the server now has it. */
    suspend fun register(): Boolean {
        val gen = generation.get()
        if (!current(gen)) return false
        return runCatching {
            mutex.withLock {
                if (!current(gen) || !isLoggedIn()) return@withLock false
                val id = fidSource.fid() ?: return@withLock false
                // FirebaseMessaging.register() reports back via onRegistered,
                // which lands here again with the same FID: one POST will do.
                if (postedFid == id && postedGeneration == gen) return@withLock true
                if (!current(gen) || !isLoggedIn()) return@withLock false
                postInFlight.set(true)
                val resp = try {
                    api.registerFcmDevice(
                        FcmDeviceRequest(
                            fid = id,
                            appVersion = appVersion.take(50),
                            deviceLabel = deviceLabel.take(100),
                        )
                    )
                } finally {
                    postInFlight.set(false)
                }
                if (!current(gen)) return@withLock false
                store.saveDevice(resp.id, id)
                postedFid = id
                postedGeneration = gen
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Fire-and-forget [register], for callers outside a coroutine (FCM
     * callbacks, session refresh). Ignored mid-teardown; cancelled by it.
     */
    fun launchRegister() {
        if (tearingDown.get()) return
        val job = scope.launch { register() }
        jobs += job
        job.invokeOnCompletion { jobs -= job }
    }

    /**
     * End this device's session: stop registering, detach it from the
     * account, then [clearSession] (the auth store) — all while new registers
     * are refused, so nothing can re-attach under the outgoing token.
     *
     * [canCallServer] false is session expiry: there is no token left to
     * DELETE with. Whenever the row could not be confirmed gone (2xx or 404),
     * the FID itself is deleted instead, so pushes to it fail and the server
     * prunes it. Lock wait plus DELETE is bounded by [UNREGISTER_TIMEOUT_MS].
     */
    suspend fun teardown(canCallServer: Boolean, clearSession: suspend () -> Unit = {}) {
        tearingDown.set(true)
        generation.incrementAndGet()
        synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
        try {
            val detached = withTimeoutOrNull(UNREGISTER_TIMEOUT_MS) {
                mutex.withLock {
                    val id = store.fcmDeviceId()
                    when {
                        id == null -> !postInFlight.get()
                        !canCallServer || !isLoggedIn() -> false
                        else -> runCatching { api.deleteFcmDevice(id) }.getOrNull()
                            ?.let { it.isSuccessful || it.code() == 404 } ?: false
                    }
                }
            } ?: false
            if (!detached) {
                runCatching { withTimeoutOrNull(FID_DELETE_TIMEOUT_MS) { fidSource.deleteInstallation() } }
            }
            postedFid = null
            postedGeneration = -1
            store.clearDevice()
            clearSession()
        } finally {
            tearingDown.set(false)
        }
    }

    companion object {
        const val UNREGISTER_TIMEOUT_MS = 3_000L
        const val FID_DELETE_TIMEOUT_MS = 2_000L
    }
}

/** [FidSource] backed by Firebase; inert when this build has no Firebase config. */
class FirebaseFidSource(private val context: Context) : FidSource {

    // FCM registration once per process — and again after the FID is deleted,
    // since the next one is a new installation FCM has never seen.
    private val fcmRegistered = AtomicBoolean(false)

    override suspend fun fid(): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        // The server addresses this install by FID, which reaches nothing
        // until FCM knows it. Play services can hang; bound both waits.
        if (fcmRegistered.compareAndSet(false, true)) {
            val ok = runCatching {
                // Task<Void>: the result is null, so success is "returned in time".
                withTimeoutOrNull(AWAIT_TIMEOUT_MS) { FirebaseMessaging.getInstance().register().await(); true } ?: false
            }.onFailure { Log.w(TAG, "FCM register() failed", it) }
                .getOrDefault(false)
            if (!ok) {
                // Don't hand the server an FID FCM doesn't know: its first send
                // would come back UNREGISTERED and prune the row. Retry next time.
                fcmRegistered.set(false)
                return null
            }
        }
        return runCatching {
            withTimeoutOrNull(AWAIT_TIMEOUT_MS) { FirebaseInstallations.getInstance().id.await() }
        }.getOrNull()
    }

    override suspend fun deleteInstallation(): Boolean {
        if (FirebaseApp.getApps(context).isEmpty()) return false
        fcmRegistered.set(false)
        return runCatching {
            withTimeoutOrNull(AWAIT_TIMEOUT_MS) { FirebaseInstallations.getInstance().delete().await(); true } ?: false
        }.getOrDefault(false)
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val TAG = "PushRegistrar"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
