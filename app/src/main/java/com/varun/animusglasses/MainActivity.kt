package com.varun.animusglasses

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.varun.animusglasses.ui.ChatScreen
import com.varun.animusglasses.ui.ScanScreen
import com.varun.animusglasses.ui.theme.AnimusGlassesTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {

    private val viewModel: AnimusViewModel by viewModels()
    private var started = false

    // Android permissions launcher
    private val androidPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            proceedAfterAndroidPermissions()
        }
    }

    // Meta SDK camera permission launcher
    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(status)
        permissionContinuation = null
    }

    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                wearablesPermissionLauncher.launch(permission)
            }
        }
    }

    private fun proceedAfterAndroidPermissions() {
        lifecycleScope.launch {
            // Check if SDK camera permission already granted — don't ask again if so
            val permissionCheck = Wearables.checkPermissionStatus(Permission.CAMERA)
            val alreadyGranted = permissionCheck.getOrNull() == PermissionStatus.Granted

            if (alreadyGranted) {
                viewModel.onPermissionsGranted(this@MainActivity)
            } else {
                val status = requestWearablesPermission(Permission.CAMERA)
                if (status == PermissionStatus.Granted) {
                    viewModel.onPermissionsGranted(this@MainActivity)
                } else {
                    viewModel.onPermissionDenied()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AnimusGlassesTheme {
                val uiState by viewModel.uiState.collectAsState()

                if (uiState.chatActive) {
                    ChatScreen(
                        uiState = uiState,
                        onSendMessage = { viewModel.sendMessage(it) },
                        onStartRecording = { viewModel.startRecording(this) },
                        onStopRecording = { viewModel.stopRecording() },
                        onTerminate = { viewModel.terminateChat() }
                    )
                } else {
                    ScanScreen(
                        uiState = uiState,
                        onScan = { viewModel.scan() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Only run once per app lifecycle to avoid repeated permission prompts
        if (!started) {
            started = true
            androidPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.INTERNET,
                    Manifest.permission.RECORD_AUDIO,
                )
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }
}
