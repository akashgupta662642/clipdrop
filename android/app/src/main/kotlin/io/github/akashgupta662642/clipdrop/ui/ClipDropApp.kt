package io.github.akashgupta662642.clipdrop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.akashgupta662642.clipdrop.ui.home.HomeScreen
import io.github.akashgupta662642.clipdrop.ui.pairing.PairScreen
import io.github.akashgupta662642.clipdrop.ui.pairing.ScanScreen
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object ScanRoute

/** Confirms and runs pairing for a scanned or linked `clipdrop://pair` [uri]. */
@Serializable
data class PairRoute(val uri: String)

/**
 * The app's navigation graph. [pairingLink] is a `clipdrop://pair` URI that opened the app; it only
 * prefills the pairing screen, which always asks the user to confirm.
 */
@Composable
fun ClipDropApp(pairingLink: String?, onPairingLinkHandled: () -> Unit) {
    val navController = rememberNavController()

    LaunchedEffect(pairingLink) {
        if (pairingLink != null) {
            navController.navigate(PairRoute(pairingLink)) { popUpTo<HomeRoute>() }
            onPairingLinkHandled()
        }
    }

    NavHost(navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(onPair = { navController.navigate(ScanRoute) })
        }
        composable<ScanRoute> {
            ScanScreen(
                onScanned = { uri -> navController.navigate(PairRoute(uri)) { popUpTo<HomeRoute>() } },
                onBack = { navController.popBackStack() },
            )
        }
        composable<PairRoute> {
            PairScreen(
                onDone = { navController.popBackStack<HomeRoute>(inclusive = false) },
                onScanAgain = { navController.navigate(ScanRoute) { popUpTo<HomeRoute>() } },
            )
        }
    }
}
