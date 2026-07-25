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
