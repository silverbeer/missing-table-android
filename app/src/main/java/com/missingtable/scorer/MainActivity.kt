package com.missingtable.scorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        super.onCreate(savedInstanceState)
        val container = (application as MtApp).container
        val loggedIn = runBlocking { container.tokenStore.accessToken() != null }

        setContent {
            MtTheme {
                val navController = rememberNavController()
                var startDestination by remember {
                    mutableStateOf(if (loggedIn) "matches" else "login")
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
        }
    }
}
