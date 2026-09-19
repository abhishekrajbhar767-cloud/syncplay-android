package com.syncplay.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.syncplay.android.ui.NearbyWifiPermissionHelper
import com.syncplay.android.ui.navigation.SyncPlayNavGraph
import com.syncplay.android.ui.theme.Ink
import com.syncplay.android.ui.theme.SyncPlayTheme

class MainActivity : ComponentActivity() {
    private lateinit var permissionHelper: NearbyWifiPermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionHelper = NearbyWifiPermissionHelper(this)

        setContent {
            SyncPlayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    SyncPlayNavGraph(
                        onRequestNearbyPermission = { onGranted ->
                            permissionHelper.ensurePermission { granted ->
                                if (granted) onGranted()
                            }
                        },
                    )
                }
            }
        }
    }
}
