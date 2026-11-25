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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pasarguard.vpn.R
import com.pasarguard.vpn.ui.components.DockDestination
import com.pasarguard.vpn.ui.components.FloatingPillBottomDock
import com.pasarguard.vpn.ui.screens.account.AccountScreen
import com.pasarguard.vpn.ui.screens.billing.CheckoutScreen
import com.pasarguard.vpn.ui.screens.billing.OrdersScreen
import com.pasarguard.vpn.ui.screens.billing.PlansScreen
import com.pasarguard.vpn.ui.screens.diagnostics.DiagnosticsScreen
import com.pasarguard.vpn.ui.screens.home.HomeScreen
import com.pasarguard.vpn.ui.screens.login.LoginScreen
import com.pasarguard.vpn.ui.screens.onboarding.OnboardingScreen
import com.pasarguard.vpn.ui.screens.profiles.ProfilesScreen
import com.pasarguard.vpn.ui.screens.servers.ServersScreen
import com.pasarguard.vpn.ui.screens.settings.LogsScreen
import com.pasarguard.vpn.ui.screens.settings.RoutingRulesScreen
import com.pasarguard.vpn.ui.screens.settings.SettingsScreen
import com.pasarguard.vpn.ui.screens.settings.SplitTunnelScreen
import com.pasarguard.vpn.ui.screens.stats.StatisticsScreen
import com.pasarguard.vpn.ui.screens.subscription.SubscriptionScreen

import com.pasarguard.vpn.data.repository.SupportDiagnostic
import com.pasarguard.vpn.ui.screens.support.ServiceStatusHost
import com.pasarguard.vpn.ui.screens.support.ServiceStatusScreen
import com.pasarguard.vpn.ui.screens.support.SupportScreen

object Routes {
    const val SUPPORT = "support"
    const val SUPPORT_THREAD = "support_thread/{threadId}"
    fun supportThread(id: String) = "support_thread/$id"
    const val SUPPORT_CONTEXT = "support_context/{network}/{failure}"
    const val SUPPORT_ORDER = "support_order/{orderId}"
    const val SERVICE_STATUS = "service_status"
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val ACCOUNT = "account"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SUBSCRIPTION = "subscription"
    const val RENEWAL = "renewal"
    const val CHECKOUT_PLAN = "checkout_plan/{planId}"
    const val CHECKOUT_ORDER = "checkout_order/{orderId}"
    const val ORDERS = "orders"
    const val SETTINGS = "settings"
    const val SPLIT_TUNNEL = "split_tunnel"
    const val DIAGNOSTICS = "diagnostics"
    const val ROUTING_RULES = "routing_rules"
    const val PROFILES = "profiles"
    const val STATISTICS = "statistics"
    const val LOGS = "logs"

    fun checkoutForPlan(planId: String): String = "checkout_plan/$planId"
    fun checkoutForOrder(orderId: String): String = "checkout_order/$orderId"
}

@Composable
fun AppNavHost(
    startDestination: String,
    prefilledSubscriptionUrl: String?,
    paymentCallbackTimestamp: Long,
    toggleVpnTrigger: Long = 0L,
    openRouteTarget: String? = null,
    onConsumePrefilledUrl: () -> Unit,
    onConsumeToggleVpn: () -> Unit = {},
    onConsumeOpenRoute: () -> Unit = {},
    onCompleteOnboarding: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    ServiceStatusHost(onOpenStatus = { navController.navigate(Routes.SERVICE_STATUS) { launchSingleTop = true } }) {
        val dockDestinations = listOf(
            DockDestination(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
            DockDestination(Routes.SERVERS, R.string.nav_servers, Icons.Rounded.Dns),
            DockDestination(Routes.SUBSCRIPTION, R.string.nav_subscription, Icons.Rounded.CardMembership),
            DockDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings)
        )
        val mainRoutes = dockDestinations.map { it.route }.toSet()

        LaunchedEffect(paymentCallbackTimestamp) {
            if (paymentCallbackTimestamp > 0L && currentRoute != Routes.ORDERS) {
                navController.navigate(Routes.ORDERS) { launchSingleTop = true }
            }
        }

        LaunchedEffect(openRouteTarget) {
            if (!openRouteTarget.isNullOrBlank()) {
                navController.navigate(openRouteTarget) { launchSingleTop = true }
                onConsumeOpenRoute()
            }
        }

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
                        onBuySubscription = { navController.navigate(Routes.RENEWAL) { launchSingleTop = true } },
                        onOpenAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
                        onTrackPurchase = { navController.navigate(Routes.ORDERS) { launchSingleTop = true } },
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
                        onOpenServers = { navController.navigate(Routes.SERVERS) { launchSingleTop = true } },
                        onOpenRenewal = { navController.navigate(Routes.RENEWAL) { launchSingleTop = true } },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                        onOpenProfiles = { navController.navigate(Routes.PROFILES) { launchSingleTop = true } },
                        onOpenStatistics = { navController.navigate(Routes.STATISTICS) { launchSingleTop = true } },
                        toggleVpnTrigger = toggleVpnTrigger,
                        onConsumeToggleVpn = onConsumeToggleVpn
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
                        onOpenAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
                        onOpenRenewal = { navController.navigate(Routes.RENEWAL) { launchSingleTop = true } },
                        onOpenOrders = { navController.navigate(Routes.ORDERS) { launchSingleTop = true } }
                    )
                }

                composable(Routes.SUPPORT) {
                    SupportScreen(onBack = { navController.popBackStack() },
                        onAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } })
                }
                composable(Routes.SUPPORT_THREAD) { entry ->
                    val id = entry.arguments?.getString("threadId")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                    SupportScreen(onBack = { navController.popBackStack() },
                        onAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
                        initialThread = id)
                }
                composable(Routes.SUPPORT_CONTEXT) { entry ->
                    val network = entry.arguments?.getString("network")?.takeIf {
                        it in setOf("wifi", "cellular", "other", "offline")
                    } ?: "other"
                    val failure = entry.arguments?.getString("failure")?.takeIf {
                        it in setOf("dns", "network", "subscription", "server")
                    } ?: "unknown"
                    SupportScreen(onBack = { navController.popBackStack() },
                        onAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
                        diagnostic = SupportDiagnostic(network, failure))
                }
                composable(Routes.SUPPORT_ORDER) { entry ->
                    SupportScreen(onBack = { navController.popBackStack() },
                        onAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
                        suggestionOrder = entry.arguments?.getString("orderId")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) })
                }
                composable(Routes.SERVICE_STATUS) {
                    ServiceStatusScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.ACCOUNT) {
                    AccountScreen(
                        onBack = { navController.popBackStack() },
                        onPlans = { navController.navigate(Routes.RENEWAL) },
                        onSupport = { navController.navigate(Routes.SUPPORT) { launchSingleTop = true } },
                        onServiceStatus = { navController.navigate(Routes.SERVICE_STATUS) { launchSingleTop = true } },
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onOrder = { navController.navigate(Routes.checkoutForOrder(it)) },
                        onConnect = {
                            navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                        }
                    )
                }

                composable(Routes.RENEWAL) {
                    PlansScreen(
                        onBack = { navController.popBackStack() },
                        onSelectPlan = { planId ->
                            navController.navigate(Routes.checkoutForPlan(planId))
                        },
                        onResumeOrder = { orderId ->
                            navController.navigate(Routes.checkoutForOrder(orderId))
                        },
                        onOpenOrders = {
                            navController.navigate(Routes.ORDERS) { launchSingleTop = true }
                        }
                    )
                }

                composable(
                    route = Routes.CHECKOUT_PLAN,
                    arguments = listOf(navArgument("planId") { type = NavType.StringType })
                ) { entry ->
                    val planId = entry.arguments?.getString("planId")
                    CheckoutScreen(
                        onSupport = { orderId ->
                            navController.navigate(if (orderId == null) Routes.SUPPORT else "support_order/$orderId") { launchSingleTop = true }
                        },
                        planId = planId,
                        orderId = null,
                        onBack = { navController.popBackStack() },
                        onOpenOrders = {
                            navController.navigate(Routes.ORDERS) { launchSingleTop = true }
                        },
                        onViewSubscription = {
                            navController.navigate(Routes.SUBSCRIPTION) {
                                popUpTo(Routes.HOME)
                                launchSingleTop = true
                            }
                        },
                        onDoneHome = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(navController.graph.id)
                            }
                        }
                    )
                }

                composable(
                    route = Routes.CHECKOUT_ORDER,
                    arguments = listOf(navArgument("orderId") { type = NavType.StringType })
                ) { entry ->
                    val orderId = entry.arguments?.getString("orderId")
                    CheckoutScreen(
                        onSupport = { orderId ->
                            navController.navigate(if (orderId == null) Routes.SUPPORT else "support_order/$orderId") { launchSingleTop = true }
                        },
                        planId = null,
                        orderId = orderId,
                        onBack = { navController.popBackStack() },
                        onOpenOrders = {
                            navController.navigate(Routes.ORDERS) { launchSingleTop = true }
                        },
                        onViewSubscription = {
                            navController.navigate(Routes.SUBSCRIPTION) {
                                popUpTo(Routes.HOME)
                                launchSingleTop = true
                            }
                        },
                        onDoneHome = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(navController.graph.id)
                            }
                        }
                    )
                }

                composable(Routes.ORDERS) {
                    OrdersScreen(
                        onOpenAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
                        onBack = { navController.popBackStack() },
                        onOpenPlans = {
                            navController.navigate(Routes.RENEWAL) { launchSingleTop = true }
                        },
                        onResumeOrder = { orderId ->
                            navController.navigate(Routes.checkoutForOrder(orderId))
                        }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenSplitTunneling = { navController.navigate(Routes.SPLIT_TUNNEL) { launchSingleTop = true } },
                        onOpenRoutingRules = { navController.navigate(Routes.ROUTING_RULES) { launchSingleTop = true } },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                        onOpenProfiles = { navController.navigate(Routes.PROFILES) { launchSingleTop = true } },
                        onOpenStatistics = { navController.navigate(Routes.STATISTICS) { launchSingleTop = true } },
                        onOpenLogs = { navController.navigate(Routes.LOGS) { launchSingleTop = true } },
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

                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(onBack = { navController.popBackStack() },
                        onSupport = { report -> navController.navigate("support_context/${report.network}/${report.failure}") },
                        onServers = { navController.navigate(Routes.SERVERS) },
                        onSubscription = { navController.navigate(Routes.SUBSCRIPTION) })
                }

                composable(Routes.ROUTING_RULES) {
                    RoutingRulesScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PROFILES) {
                    ProfilesScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.STATISTICS) {
                    StatisticsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.LOGS) {
                    LogsScreen(onBack = { navController.popBackStack() })
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
}
