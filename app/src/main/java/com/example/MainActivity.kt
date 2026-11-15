package com.example

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.SonaGlassShell
import com.example.ui.SonaViewModel
import com.example.ui.theme.SonaMusicTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SonaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleUsbIntent(intent)

        setContent {
            SonaMusicTheme {
                SonaGlassShell(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.usbDacManager.scanConnectedDacs(requestPermissionIfMissing = true)
        }
    }
}
