package com.missingtable.scorer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.missingtable.scorer.domain.MatchDetail
import com.missingtable.scorer.domain.NotificationIntent
import com.missingtable.scorer.ui.leaderboard.LeaderboardScreen
import com.missingtable.scorer.ui.live.LiveScreen
import com.missingtable.scorer.ui.login.LoginScreen
import com.missingtable.scorer.ui.matches.MatchListScreen
import com.missingtable.scorer.ui.profile.NotificationPrefsScreen
import com.missingtable.scorer.ui.profile.ProfileScreen
import com.missingtable.scorer.ui.table.TableScreen
import com.missingtable.scorer.ui.tournaments.TournamentsScreen
import com.missingtable.scorer.ui.team.TeamScreen
import com.missingtable.scorer.ui.team.TeamLink
import com.missingtable.scorer.ui.common.SyncStatusBanner
import com.missingtable.scorer.ui.common.rememberSyncStatus
import com.missingtable.scorer.ui.theme.MtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    /**
     * Match a tapped push points at (SB-1464), until AppNav has opened it.
     * Killed app: the launch intent, in onCreate. Running app: singleTop
     * delivers it to onNewIntent.
     */
    private val pendingMatchId = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MtApp).container
        val loggedIn = runBlocking { container.tokenStore.accessToken() != null }
        // Not on recreation, nor when relaunched from Recents: both replay the
        // original intent, and the match was already opened from it.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) pendingMatchId.value = matchIdFrom(intent)

        setContent {
            MtTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav(
                        container,
                        startDestination = if (loggedIn) "home" else "login",
                        pendingMatchId = pendingMatchId,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        matchIdFrom(intent)?.let { pendingMatchId.value = it }
    }

    private fun matchIdFrom(intent: Intent?): Int? {
        val extras = intent?.extras ?: return null
        return NotificationIntent.matchIdFrom(
            mapOf(
                NotificationIntent.EXTRA_MATCH_ID to extras.getString(NotificationIntent.EXTRA_MATCH_ID),
                NotificationIntent.EXTRA_URL to extras.getString(NotificationIntent.EXTRA_URL),
            )
        )
    }
}

/**
 * Team screen route; name and subtitle ride along because there is no team
 * endpoint (SB-1464), and so does the squad the caller showed (SB-1645, -1 = none).
 */
private fun teamRoute(link: TeamLink): String =
    "team/${link.teamId}?name=${android.net.Uri.encode(link.name)}" +
        "&subtitle=${android.net.Uri.encode(link.subtitle ?: "")}" +
        "&ageGroupId=${link.ageGroupId ?: -1}" +
        "&ageGroupName=${android.net.Uri.encode(link.ageGroupName ?: "")}"

@Composable
private fun AppNav(
    container: AppContainer,
    startDestination: String,
    pendingMatchId: MutableStateFlow<Int?>,
) {
    val navController: NavHostController = rememberNavController()
    val scope = rememberCoroutineScope()
    val pending by pendingMatchId.collectAsState()
    // HomeShell's update check: null until it has answered, then whether
    // this build is hard-blocked (SB-328).
    val forceUpdate = remember { MutableStateFlow<Boolean?>(null) }

    // A tapped push opens its match (SB-1464) — but only once past login, so
    // a signed-out tap survives signing in, and login's own navigation to
    // home (which pops everything above login) cannot discard it. Never past
    // the force-update block: a blocked build stays on the gate, and the
    // match is left pending.
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        navController.currentBackStackEntryFlow.first { it.destination.route != "login" }
        container.tokenStore.accessTokenFlow.first { it != null }
        if (forceUpdate.first { it != null } == true) return@LaunchedEffect
        navController.navigate("match/$id")
        pendingMatchId.compareAndSet(id, null)
    }

    // Refresh cached role/team/club on cold start (SB-317) — best-effort.
    LaunchedEffect(Unit) {
        if (container.tokenStore.accessToken() != null) container.refreshSession()
    }

    // Session-expiry watchdog: TokenAuthenticator clears the store when the
    // refresh token is rejected (401). Token going non-null -> null while the
    // app is running means the session died out from under us — drop to login.
    // Expiry skips logout, so tear the push session down here too (SB-1464);
    // after an explicit logout this repeats a teardown that already ran,
    // which is a no-op.
    LaunchedEffect(Unit) {
        var hadToken = false
        container.tokenStore.accessTokenFlow.collect { token ->
            if (token != null) {
                hadToken = true
            } else if (hadToken) {
                hadToken = false
                scope.launch { container.teardownSession(canCallServer = false) }
                if (navController.currentDestination?.route != "login") {
                    navController.navigate("login") { popUpTo(0) { inclusive = true } }
                }
            }
        }
    }

    // App-wide sync banner (SB-1592): any unsent or rejected action, from any
    // match, on every screen. Insets: the banner takes the status bar, so the
    // screen below must not pad for it again.
    val syncStatus = rememberSyncStatus(container)
    val backEntry by navController.currentBackStackEntryAsState()
    val onLogin = backEntry?.destination?.route == "login"
    Column(Modifier.fillMaxSize()) {
        if (syncStatus != null) {
            SyncStatusBanner(
                container = container,
                status = syncStatus,
                onOpenMatch = { id ->
                    if (!onLogin) navController.navigate("match/$id") { launchSingleTop = true }
                },
                modifier = Modifier.statusBarsPadding(),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .then(if (syncStatus != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
        ) {
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
                    HomeShell(
                        container = container,
                        // Every tap opens the match first (SB-1455): fans get the
                        // preview and result, and scoring is a button there for those
                        // the server says may manage this match.
                        onOpenMatch = { match -> navController.navigate("match/${match.id}") },
                        onOpenTeam = { link -> navController.navigate(teamRoute(link)) },
                        onOpenNotificationPrefs = { navController.navigate("notification-prefs") },
                        onUpdateChecked = { forceUpdate.value = it },
                        onLogout = {
                            scope.launch {
                                // Device off the account while the DELETE can still
                                // authenticate, then the tokens (SB-1464); bounded, so
                                // logout never hangs on it.
                                container.teardownSession(canCallServer = true)
                                // The expiry watchdog may have got there first.
                                if (navController.currentDestination?.route != "login") {
                                    navController.navigate("login") {
                                        popUpTo("home") { inclusive = true }
                                    }
                                }
                            }
                        },
                    )
                }
                composable("team/{teamId}?name={name}&subtitle={subtitle}&ageGroupId={ageGroupId}&ageGroupName={ageGroupName}") { entry ->
                    val teamId = entry.arguments?.getString("teamId")?.toIntOrNull() ?: return@composable
                    TeamScreen(
                        container = container,
                        teamId = teamId,
                        name = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                        subtitle = entry.arguments?.getString("subtitle")?.takeIf { it.isNotBlank() },
                        onBack = { navController.popBackStack() },
                        ageGroupId = entry.arguments?.getString("ageGroupId")?.toIntOrNull()?.takeIf { it > 0 },
                        ageGroupName = entry.arguments?.getString("ageGroupName")?.takeIf { it.isNotBlank() },
                    )
                }
                composable("notification-prefs") {
                    NotificationPrefsScreen(container = container, onBack = { navController.popBackStack() })
                }
                composable(
                    "lineup/{matchId}?homeId={homeId}&awayId={awayId}&homeName={homeName}&awayName={awayName}&seasonId={seasonId}&ageGroupId={ageGroupId}&ageGroupName={ageGroupName}&started={started}&homeAgId={homeAgId}&awayAgId={awayAgId}"
                ) { entry ->
                    val args = entry.arguments
                    val matchId = args?.getString("matchId")?.toIntOrNull() ?: return@composable
                    val homeId = args.getString("homeId")?.toIntOrNull() ?: return@composable
                    val awayId = args.getString("awayId")?.toIntOrNull() ?: return@composable
                    val seasonId = args.getString("seasonId")?.toIntOrNull().takeIf { it != -1 }
                    val ageGroupId = args.getString("ageGroupId")?.toIntOrNull().takeIf { it != -1 }
                    // A side playing up fields its own age group (SB-1444); else the match's.
                    val homeAgId = args.getString("homeAgId")?.toIntOrNull().takeIf { it != -1 } ?: ageGroupId
                    val awayAgId = args.getString("awayAgId")?.toIntOrNull().takeIf { it != -1 } ?: ageGroupId
                    com.missingtable.scorer.ui.lineup.LineupScreen(
                        container = container,
                        matchId = matchId,
                        homeTeamId = homeId,
                        homeTeamName = args.getString("homeName") ?: "Home",
                        awayTeamId = awayId,
                        awayTeamName = args.getString("awayName") ?: "Away",
                        seasonId = seasonId,
                        ageGroupName = args.getString("ageGroupName")?.takeIf { it.isNotBlank() },
                        ageGroupIds = mapOf(homeId to homeAgId, awayId to awayAgId),
                        alreadyStarted = args.getString("started") == "true",
                        onBack = { navController.popBackStack() },
                        onStartMatch = {
                            // Arrived from the live screen (SB-677): go back to it
                            // rather than stacking a second copy.
                            if (args.getString("started") == "true") {
                                navController.popBackStack()
                            } else {
                                navController.navigate(
                                    "live/$matchId?seasonId=${seasonId ?: -1}&ageGroupId=${ageGroupId ?: -1}"
                                ) {
                                    popUpTo("matches")
                                }
                            }
                        },
                    )
                }
                composable("match/{matchId}") { entry ->
                    val matchId = entry.arguments?.getString("matchId")?.toIntOrNull() ?: return@composable
                    com.missingtable.scorer.ui.match.MatchDetailScreen(
                        container = container,
                        matchId = matchId,
                        onBack = { navController.popBackStack() },
                        onOpenTeam = { link -> navController.navigate(teamRoute(link)) },
                        onAction = { match, action ->
                            val common = "seasonId=${match.seasonId ?: -1}&ageGroupId=${match.ageGroupId ?: -1}"
                            when (action) {
                                MatchDetail.Action.LINEUP_AND_KICKOFF -> {
                                    val names = "homeName=${android.net.Uri.encode(match.homeTeamName)}" +
                                        "&awayName=${android.net.Uri.encode(match.awayTeamName)}" +
                                        "&ageGroupName=${android.net.Uri.encode(match.ageGroupName ?: "")}"
                                    navController.navigate(
                                        "lineup/${match.id}?homeId=${match.homeTeamId}&awayId=${match.awayTeamId}&$names&$common" +
                                            "&homeAgId=${match.homeAgeGroupId ?: -1}&awayAgId=${match.awayAgeGroupId ?: -1}"
                                    )
                                }
                                MatchDetail.Action.SCORE_LIVE -> navController.navigate("live/${match.id}?$common")
                                // The read-only live view (SB-318/320).
                                MatchDetail.Action.WATCH_LIVE ->
                                    navController.navigate("live/${match.id}?readOnly=true&$common")
                                // The post-match editor (SB-281).
                                MatchDetail.Action.EDIT_RESULT -> navController.navigate("postmatch/${match.id}?$common")
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
                        onOpenLineup = { st ->
                            // started=true so the lineup screen offers BACK TO MATCH
                            // rather than a second kickoff (SB-677).
                            val names = "homeName=${android.net.Uri.encode(st.homeTeamName)}" +
                                "&awayName=${android.net.Uri.encode(st.awayTeamName)}" +
                                "&ageGroupName=${android.net.Uri.encode(st.ageGroupName ?: "")}"
                            navController.navigate(
                                "lineup/$matchId?homeId=${st.homeTeamId}&awayId=${st.awayTeamId}&$names" +
                                    "&seasonId=${seasonId ?: -1}&ageGroupId=${ageGroupId ?: -1}&started=true"
                            )
                        },
                        readOnly = readOnly,
                    )
                }
            }
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
            "This version of Missing Table (${BuildConfig.VERSION_NAME}) is no longer " +
                "supported. Download the latest version to keep using it.",
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
    onOpenTeam: (TeamLink) -> Unit,
    onOpenNotificationPrefs: () -> Unit,
    onUpdateChecked: (forceUpdate: Boolean) -> Unit,
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
        // Answered either way: a failed check blocks nothing here either.
        onUpdateChecked(forceUpdate)
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
                HomeTab.Table -> TableScreen(container, onOpenTeam = onOpenTeam)
                HomeTab.Tournaments -> TournamentsScreen(container)
                HomeTab.Leaders -> LeaderboardScreen(container)
                HomeTab.Profile -> ProfileScreen(
                    container,
                    onLogout = onLogout,
                    onOpenTeam = onOpenTeam,
                    onOpenNotificationPrefs = onOpenNotificationPrefs,
                )
            }
        }
    }
}
