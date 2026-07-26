package com.missingtable.scorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.missingtable.scorer.data.auth.Session
import com.missingtable.scorer.ui.leaderboard.LeaderboardScreen
import com.missingtable.scorer.ui.live.LiveScreen
import com.missingtable.scorer.ui.login.LoginScreen
import com.missingtable.scorer.ui.matches.MatchListScreen
import com.missingtable.scorer.ui.profile.ProfileScreen
import com.missingtable.scorer.ui.table.TableScreen
import com.missingtable.scorer.ui.tournaments.TournamentsScreen
import com.missingtable.scorer.ui.theme.MtTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MtApp).container
        val loggedIn = runBlocking { container.tokenStore.accessToken() != null }

        setContent {
            MtTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav(container, startDestination = if (loggedIn) "home" else "login")
                }
            }
        }
    }
}

@Composable
private fun AppNav(container: AppContainer, startDestination: String) {
    val navController: NavHostController = rememberNavController()

    // Refresh cached role/team/club on cold start (SB-317) — best-effort.
    LaunchedEffect(Unit) {
        if (container.tokenStore.accessToken() != null) container.refreshSession()
    }

    // Session-expiry watchdog: TokenAuthenticator clears the store when the
    // refresh token is rejected (401). Token going non-null -> null while the
    // app is running means the session died out from under us — drop to login.
    LaunchedEffect(Unit) {
        var hadToken = false
        container.tokenStore.accessTokenFlow.collect { token ->
            if (token != null) {
                hadToken = true
            } else if (hadToken) {
                hadToken = false
                if (navController.currentDestination?.route != "login") {
                    navController.navigate("login") { popUpTo(0) { inclusive = true } }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen(
                container = container,
                onLoggedIn = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
            )
        }
        composable("home") {
            val session by container.tokenStore.sessionFlow.collectAsState(initial = Session())
            HomeShell(
                container = container,
                onOpenMatch = { match ->
                    val common = "seasonId=${match.seasonId ?: -1}&ageGroupId=${match.ageGroupId ?: -1}"
                    when {
                        // Fans/players get the read-only live view (SB-318/320);
                        // scorers keep the lineup/live flow.
                        !session.canScore ->
                            navController.navigate("live/${match.id}?readOnly=true&$common")
                        match.matchStatus == "scheduled" -> {
                            val names = "homeName=${android.net.Uri.encode(match.homeTeamName)}" +
                                "&awayName=${android.net.Uri.encode(match.awayTeamName)}"
                            navController.navigate(
                                "lineup/${match.id}?homeId=${match.homeTeamId}&awayId=${match.awayTeamId}&$names&$common"
                            )
                        }
                        // Completed matches open the post-match editor (SB-281).
                        match.matchStatus == "completed" ->
                            navController.navigate("postmatch/${match.id}?$common")
                        else -> navController.navigate("live/${match.id}?$common")
                    }
                },
                onLogout = {
                    runBlocking { container.tokenStore.clear() }
                    navController.navigate("login") {
                        popUpTo("home") { inclusive = true }
                    }
                },
            )
        }
        composable(
            "lineup/{matchId}?homeId={homeId}&awayId={awayId}&homeName={homeName}&awayName={awayName}&seasonId={seasonId}&ageGroupId={ageGroupId}"
        ) { entry ->
            val args = entry.arguments
            val matchId = args?.getString("matchId")?.toIntOrNull() ?: return@composable
            val homeId = args.getString("homeId")?.toIntOrNull() ?: return@composable
            val awayId = args.getString("awayId")?.toIntOrNull() ?: return@composable
            val seasonId = args.getString("seasonId")?.toIntOrNull().takeIf { it != -1 }
            val ageGroupId = args.getString("ageGroupId")?.toIntOrNull().takeIf { it != -1 }
            com.missingtable.scorer.ui.lineup.LineupScreen(
                container = container,
                matchId = matchId,
                homeTeamId = homeId,
                homeTeamName = args.getString("homeName") ?: "Home",
                awayTeamId = awayId,
                awayTeamName = args.getString("awayName") ?: "Away",
                seasonId = seasonId,
                onBack = { navController.popBackStack() },
                onStartMatch = {
                    navController.navigate(
                        "live/$matchId?seasonId=${seasonId ?: -1}&ageGroupId=${ageGroupId ?: -1}"
                    ) {
                        popUpTo("matches")
                    }
                },
            )
        }
        composable("postmatch/{matchId}?seasonId={seasonId}&ageGroupId={ageGroupId}") { entry ->
            val matchId = entry.arguments?.getString("matchId")?.toIntOrNull() ?: return@composable
            val seasonId = entry.arguments?.getString("seasonId")?.toIntOrNull().takeIf { it != -1 }
            val ageGroupId = entry.arguments?.getString("ageGroupId")?.toIntOrNull().takeIf { it != -1 }
            com.missingtable.scorer.ui.postmatch.PostMatchScreen(
                container = container,
                matchId = matchId,
                seasonId = seasonId,
                onBack = { navController.popBackStack() },
                onReopen = {
                    navController.navigate(
                        "live/$matchId?seasonId=${seasonId ?: -1}&ageGroupId=${ageGroupId ?: -1}"
                    ) {
                        popUpTo("home")
                    }
                },
            )
        }
        composable("live/{matchId}?seasonId={seasonId}&ageGroupId={ageGroupId}&readOnly={readOnly}") { entry ->
            val matchId = entry.arguments?.getString("matchId")?.toIntOrNull() ?: return@composable
            val seasonId = entry.arguments?.getString("seasonId")?.toIntOrNull().takeIf { it != -1 }
            val ageGroupId = entry.arguments?.getString("ageGroupId")?.toIntOrNull().takeIf { it != -1 }
            val readOnly = entry.arguments?.getString("readOnly") == "true"
            LiveScreen(
                container = container,
                matchId = matchId,
                seasonId = seasonId,
                ageGroupId = ageGroupId,
                onBack = { navController.popBackStack() },
                readOnly = readOnly,
            )
        }
    }
}

/** Hard block for unsupported builds (SB-328) — only way forward is the download. */
@Composable
private fun ForceUpdateScreen(onDownload: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            "Update required",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        Text(
            "This version of MT Scorer (${BuildConfig.VERSION_NAME}) is no longer " +
                "supported. Download the latest version to keep scoring.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        androidx.compose.material3.Button(
            onClick = onDownload,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text("DOWNLOAD UPDATE") }
    }
}

private enum class HomeTab(val label: String) {
    Matches("Matches"), Table("Table"), Tournaments("Cups"), Leaders("Leaders"), Profile("Profile")
}

/** Bottom-nav shell (SB-318): Matches | Table | Cups | Leaders | Profile. */
@Composable
private fun HomeShell(
    container: AppContainer,
    onOpenMatch: (com.missingtable.scorer.data.api.MatchSummary) -> Unit,
    onLogout: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Matches) }
    var updateAvailable by rememberSaveable { mutableStateOf(false) }
    var forceUpdate by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Update check (SB-322/SB-328). The APK is sideloaded — this is the only
    // update channel. Below min_version_code → hard block (release builds
    // only; local debug builds are versionCode 1 and would always trip it).
    LaunchedEffect(Unit) {
        runCatching { container.api.apkUrl() }.onSuccess { resp ->
            val mine = BuildConfig.VERSION_CODE
            resp.versionCode?.let { updateAvailable = it > mine }
            resp.minVersionCode?.let { forceUpdate = !BuildConfig.DEBUG && mine < it }
        }
    }

    // Mint a fresh presigned URL at tap time (5-min TTL) and open the browser.
    val downloadLatest: () -> Unit = {
        scope.launch {
            runCatching { container.api.apkUrl() }.onSuccess { resp ->
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(resp.downloadUrl),
                    )
                )
            }
        }
    }

    if (forceUpdate) {
        ForceUpdateScreen(onDownload = downloadLatest)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    HomeTab.Matches -> Icons.AutoMirrored.Filled.List
                                    HomeTab.Table -> Icons.Filled.TableChart
                                    HomeTab.Tournaments -> Icons.Filled.EmojiEvents
                                    HomeTab.Leaders -> Icons.Filled.Leaderboard
                                    HomeTab.Profile -> Icons.Filled.Person
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                HomeTab.Matches -> MatchListScreen(
                    container = container,
                    onOpenMatch = onOpenMatch,
                    updateAvailable = updateAvailable,
                    onDownloadUpdate = downloadLatest,
                )
                HomeTab.Table -> TableScreen(container)
                HomeTab.Tournaments -> TournamentsScreen(container)
                HomeTab.Leaders -> LeaderboardScreen(container)
                HomeTab.Profile -> ProfileScreen(container, onLogout = onLogout)
            }
        }
    }
}
