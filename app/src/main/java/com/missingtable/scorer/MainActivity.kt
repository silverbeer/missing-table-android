package com.missingtable.scorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
                    navController.navigate(
                        "live/${match.id}?seasonId=${match.seasonId ?: -1}&ageGroupId=${match.ageGroupId ?: -1}"
                    )
                },
                onLogout = {
                    runBlocking { container.tokenStore.clear() }
                    navController.navigate("login") {
                        popUpTo("matches") { inclusive = true }
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
