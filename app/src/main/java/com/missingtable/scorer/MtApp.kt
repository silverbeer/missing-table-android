package com.missingtable.scorer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.missingtable.scorer.data.api.AuthInterceptor
import com.missingtable.scorer.data.api.MtApi
import com.missingtable.scorer.data.api.TokenAuthenticator
import com.missingtable.scorer.data.auth.TokenStore
import com.missingtable.scorer.data.db.MtDatabase
import com.missingtable.scorer.data.prefs.UiPrefs
import com.missingtable.scorer.data.push.FirebaseFidSource
import com.missingtable.scorer.data.push.PushPrefs
import com.missingtable.scorer.data.push.PushRegistrar
import com.missingtable.scorer.data.push.TeamFollows
import com.missingtable.scorer.data.repo.LiveMatchRepository
import com.missingtable.scorer.data.sync.ConnectivityWatcher
import com.missingtable.scorer.data.sync.SyncEngine
import com.missingtable.scorer.domain.NotificationIntent
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    val tokenStore = TokenStore(app)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStore))
        .authenticator(TokenAuthenticator(BuildConfig.BASE_URL, tokenStore))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    okhttp3.logging.HttpLoggingInterceptor().apply {
                        level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                    }
                )
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: MtApi = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL + "/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MtApi::class.java)

    private val db = MtDatabase.build(app)

    val syncEngine = SyncEngine(db.pendingActionDao(), api, json, appScope)

    val connectivity = ConnectivityWatcher(app) { syncEngine.kick() }

    val liveRepo = LiveMatchRepository(db.pendingActionDao(), syncEngine, json, app)

    /** Persisted UI choices (SB-642) — survives logout, unlike the auth store. */
    val uiPrefs = UiPrefs(app)

    /** Push bookkeeping (SB-1464) — like uiPrefs, survives logout's tokenStore.clear(). */
    val pushPrefs = PushPrefs(app)

    val pushRegistrar = PushRegistrar(
        api = api,
        store = pushPrefs,
        isLoggedIn = { tokenStore.accessToken() != null },
        fidSource = FirebaseFidSource(app),
        appVersion = BuildConfig.VERSION_NAME,
        deviceLabel = Build.MODEL.orEmpty(),
        scope = appScope,
    )

    /** Followed teams, shared by every Follow button (SB-1464). */
    val teamFollows = TeamFollows(api)

    // Current season (SB-338): one lookup per process. /api/matches does NOT
    // default to the current season server-side, so every match/tournament
    // fetch must pass this explicitly or prior-season data leaks in.
    private var cachedSeasonId: Int? = null

    suspend fun currentSeasonId(): Int? {
        cachedSeasonId?.let { return it }
        return runCatching { api.currentSeason().id }.getOrNull()
            ?.also { cachedSeasonId = it }
    }

    /**
     * Pull /api/auth/me and cache role/team/club (SB-317). Best-effort: on
     * failure the previous cached session stands. Scoring permission is not
     * read from here: it is per match, as the server's can_manage (SB-1455).
     */
    suspend fun refreshSession() {
        runCatching { api.me() }.onSuccess { me ->
            val p = me.user?.profile ?: return@onSuccess
            tokenStore.saveSession(p.role, p.teamId, p.clubId, p.displayName ?: p.username, me.user?.id)
        }
        // Holding a token is enough to (re)attach this device for push and
        // load the follows the buttons render from (SB-1464) — whether or not
        // /me answered. Off the caller's path: login must not wait on Firebase.
        if (tokenStore.accessToken() != null) {
            pushRegistrar.launchRegister()
            appScope.launch { teamFollows.refresh() }
        }
    }

    /** Back in the foreground: retry a follows load that failed earlier (SB-1464). */
    fun onForeground() {
        appScope.launch {
            if (tokenStore.accessToken() != null) teamFollows.ensureLoaded()
        }
    }

    /**
     * End the session, whether the user logged out or it expired (SB-1464):
     * drop the follows, detach this device from push, clear the auth store.
     * [canCallServer] false on expiry — the token is gone, so the device is
     * detached by deleting its FID instead of by DELETE.
     */
    suspend fun teardownSession(canCallServer: Boolean) {
        teamFollows.clear()
        pushRegistrar.teardown(canCallServer) { tokenStore.clear() }
    }
}

class MtApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        initFirebase(this)
        createNotificationChannel(this)
        container = AppContainer(this)
        container.connectivity.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    container.syncEngine.kick()
                    container.onForeground()
                }
            }
        )
    }
}

/**
 * Firebase by hand (SB-1464): no google-services plugin, so FirebaseInitProvider
 * finds no config and leaves the default app uninitialised. Do it here from
 * the BuildConfig fields — and only when this build has them, so a checkout
 * without google-services.json simply runs without push.
 */
private fun initFirebase(context: Context) {
    if (BuildConfig.FIREBASE_APP_ID.isEmpty() || BuildConfig.FIREBASE_API_KEY.isEmpty()) return
    if (FirebaseApp.getApps(context).isNotEmpty()) return
    runCatching {
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }
}

/** The channel the server names in every push; must exist before the first one lands. */
private fun createNotificationChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
        NotificationChannel(
            NotificationIntent.CHANNEL_ID,
            NotificationIntent.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Kickoff, goals and results for the teams you follow" }
    )
}
