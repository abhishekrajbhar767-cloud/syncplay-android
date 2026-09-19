package com.syncplay.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.syncplay.android.SyncPlayApp
import com.syncplay.android.ui.client.ClientScreen
import com.syncplay.android.ui.client.ClientViewModel
import com.syncplay.android.ui.home.HomeScreen
import com.syncplay.android.ui.host.HostScreen
import com.syncplay.android.ui.host.HostViewModel

@Composable
fun SyncPlayNavGraph(
    onRequestNearbyPermission: (onGranted: () -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as SyncPlayApp
    val repository = app.partyRepository

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onHostParty = {
                    onRequestNearbyPermission {
                        navController.navigate(Routes.HOST)
                    }
                },
                onJoinParty = {
                    onRequestNearbyPermission {
                        navController.navigate(Routes.CLIENT)
                    }
                },
            )
        }

        composable(Routes.HOST) {
            val viewModel: HostViewModel = viewModel(
                factory = HostViewModel.factory(repository),
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            HostScreen(
                state = state,
                onStart = viewModel::startHosting,
                onStop = viewModel::stopHosting,
                onBack = {
                    repository.resetToIdle()
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.CLIENT) {
            val viewModel: ClientViewModel = viewModel(
                factory = ClientViewModel.factory(repository),
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ClientScreen(
                state = state,
                onStart = viewModel::startJoining,
                onLeave = viewModel::leaveParty,
                onBack = {
                    repository.resetToIdle()
                    navController.popBackStack()
                },
            )
        }
    }
}
