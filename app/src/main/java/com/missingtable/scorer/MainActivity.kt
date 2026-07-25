package com.missingtable.scorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.missingtable.scorer.ui.live.LiveScreen
import com.missingtable.scorer.ui.login.LoginScreen
import com.missingtable.scorer.ui.matches.MatchListScreen
import com.missingtable.scorer.ui.theme.MtTheme
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
                    AppNav(container, startDestination = if (loggedIn) "matches" else "login")
                }
            }
        }
    }
}

@Composable
private fun AppNav(container: AppContainer, startDestination: String) {
    val navController: NavHostController = rememberNavController()

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
                    navController.navigate("matches") {
                        popUpTo("login") { inclusive = true }
                    }
                },
            )
        }
        composable("matches") {
            MatchListScreen(
                container = container,
                onOpenMatch = { match ->
                    val common = "seasonId=${match.seasonId ?: -1}&ageGroupId=${match.ageGroupId ?: -1}"
                    if (match.matchStatus == "scheduled") {
                        val names = "homeName=${android.net.Uri.encode(match.homeTeamName)}" +
                            "&awayName=${android.net.Uri.encode(match.awayTeamName)}"
                        navController.navigate(
                            "lineup/${match.id}?homeId=${match.homeTeamId}&awayId=${match.awayTeamId}&$names&$common"
                        )
                    } else {
                        navController.navigate("live/${match.id}?$common")
                    }
                },
                onLogout = {
                    runBlocking { container.tokenStore.clear() }
                    navController.navigate("login") {
                        popUpTo("matches") { inclusive = true }
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
        composable("live/{matchId}?seasonId={seasonId}&ageGroupId={ageGroupId}") { entry ->
            val matchId = entry.arguments?.getString("matchId")?.toIntOrNull() ?: return@composable
            val seasonId = entry.arguments?.getString("seasonId")?.toIntOrNull().takeIf { it != -1 }
            val ageGroupId = entry.arguments?.getString("ageGroupId")?.toIntOrNull().takeIf { it != -1 }
            LiveScreen(
                container = container,
                matchId = matchId,
                seasonId = seasonId,
                ageGroupId = ageGroupId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
