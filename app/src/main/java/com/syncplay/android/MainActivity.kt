package com.syncplay.android

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.syncplay.android.ui.MediaProjectionPermissionHelper
import com.syncplay.android.ui.NearbyWifiPermissionHelper
import com.syncplay.android.ui.navigation.SyncPlayNavGraph
import com.syncplay.android.ui.theme.Ink
import com.syncplay.android.ui.theme.SyncPlayTheme

class MainActivity : ComponentActivity() {
    private lateinit var nearbyPermissionHelper: NearbyWifiPermissionHelper
    private lateinit var mediaProjectionHelper: MediaProjectionPermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nearbyPermissionHelper = NearbyWifiPermissionHelper(this)
        mediaProjectionHelper = MediaProjectionPermissionHelper(this)

        setContent {
            val projectionHelper = remember { mediaProjectionHelper }
            SyncPlayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    SyncPlayNavGraph(
                        onRequestNearbyPermission = { onGranted ->
                            nearbyPermissionHelper.ensurePermission { granted ->
                                if (granted) onGranted()
                            }
                        },
                        onRequestAudioCapture = { onGranted ->
                            projectionHelper.ensureCapturePermissions { permitted ->
                                if (!permitted) return@ensureCapturePermissions
                                projectionHelper.requestMediaProjection { resultCode, data ->
                                    if (resultCode == Activity.RESULT_OK && data != null) {
                                        onGranted(resultCode, data)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
