package com.missingtable.scorer

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.missingtable.scorer.data.api.AuthInterceptor
import com.missingtable.scorer.data.api.MtApi
import com.missingtable.scorer.data.api.TokenAuthenticator
import com.missingtable.scorer.data.auth.TokenStore
import com.missingtable.scorer.data.db.MtDatabase
import com.missingtable.scorer.data.prefs.UiPrefs
import com.missingtable.scorer.data.repo.LiveMatchRepository
import com.missingtable.scorer.data.sync.ConnectivityWatcher
import com.missingtable.scorer.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
     * failure the previous cached session stands (canScore defaults open
     * until a first success — the server enforces the real permissions).
     */
    suspend fun refreshSession() {
        runCatching { api.me() }.onSuccess { me ->
            val p = me.user?.profile ?: return
            tokenStore.saveSession(p.role, p.teamId, p.clubId, p.displayName ?: p.username)
        }
    }
}

class MtApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.connectivity.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) container.syncEngine.kick()
            }
        )
    }
}
