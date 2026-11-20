package com.pasarguard.vpn.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CardMembership
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pasarguard.vpn.R
import com.pasarguard.vpn.ui.components.DockDestination
import com.pasarguard.vpn.ui.components.FloatingPillBottomDock
import com.pasarguard.vpn.ui.screens.home.HomeScreen
import com.pasarguard.vpn.ui.screens.login.LoginScreen
import com.pasarguard.vpn.ui.screens.onboarding.OnboardingScreen
import com.pasarguard.vpn.ui.screens.renewal.RenewalScreen
import com.pasarguard.vpn.ui.screens.servers.ServersScreen
import com.pasarguard.vpn.ui.screens.settings.SettingsScreen
import com.pasarguard.vpn.ui.screens.settings.SplitTunnelScreen
import com.pasarguard.vpn.ui.screens.subscription.SubscriptionScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SUBSCRIPTION = "subscription"
    const val RENEWAL = "renewal"
    const val SETTINGS = "settings"
    const val SPLIT_TUNNEL = "split_tunnel"
}

@Composable
fun AppNavHost(
    startDestination: String,
    prefilledSubscriptionUrl: String?,
    paymentCallbackTimestamp: Long,
    onConsumePrefilledUrl: () -> Unit,
    onCompleteOnboarding: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val dockDestinations = listOf(
        DockDestination(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
        DockDestination(Routes.SERVERS, R.string.nav_servers, Icons.Rounded.Dns),
        DockDestination(Routes.SUBSCRIPTION, R.string.nav_subscription, Icons.Rounded.CardMembership),
        DockDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings)
    )
    val mainRoutes = dockDestinations.map { it.route }.toSet()

    // Navigate directly to Renewal when returning from Chrome Custom Tabs payment callback
    LaunchedEffect(paymentCallbackTimestamp) {
        if (paymentCallbackTimestamp > 0L && currentRoute != Routes.RENEWAL) {
            navController.navigate(Routes.RENEWAL) {
                launchSingleTop = true
            }
        }
    }

    // Navigate directly to Login if an import deep link arrives while unauthenticated
    LaunchedEffect(prefilledSubscriptionUrl) {
        if (!prefilledSubscriptionUrl.isNullOrBlank() && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) { launchSingleTop = true }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        onCompleteOnboarding()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.LOGIN) {
                LoginScreen(
                    prefilledSubscriptionUrl = prefilledSubscriptionUrl,
                    onLoginSuccess = {
                        onConsumePrefilledUrl()
                        navController.navigate(Routes.HOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onOpenServers = {
                        navController.navigate(Routes.SERVERS) {
                            launchSingleTop = true
                        }
                    },
                    onOpenRenewal = {
                        navController.navigate(Routes.RENEWAL) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Routes.SERVERS) {
                ServersScreen(
                    onServerChosen = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(Routes.SUBSCRIPTION) {
                SubscriptionScreen(
                    onOpenRenewal = {
                        navController.navigate(Routes.RENEWAL) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Routes.RENEWAL) {
                RenewalScreen(
                    paymentCallbackTrigger = paymentCallbackTimestamp,
                    onBack = { navController.popBackStack() },
                    onDone = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSplitTunneling = {
                        navController.navigate(Routes.SPLIT_TUNNEL) {
                            launchSingleTop = true
                        }
                    },
                    onLoggedOut = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.SPLIT_TUNNEL) {
                SplitTunnelScreen(onBack = { navController.popBackStack() })
            }
        }

        if (currentRoute in mainRoutes) {
            FloatingPillBottomDock(
                destinations = dockDestinations,
                currentRoute = currentRoute,
                onSelect = { target ->
                    if (target != currentRoute) {
                        navController.navigate(target) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
