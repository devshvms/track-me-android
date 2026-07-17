package `in`.shvms.trackme.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.ui.localization.LocalAppStrings

@Composable
fun rememberIsOffline(): Boolean {
    val context = LocalContext.current
    var isOffline by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager

        fun checkOffline(): Boolean {
            val network = connectivityManager?.activeNetwork ?: return true
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true
            return !(capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        isOffline = checkOffline()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isOffline = false
            }

            override fun onLost(network: android.net.Network) {
                isOffline = true
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                networkCapabilities: android.net.NetworkCapabilities
            ) {
                isOffline = !(networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }

        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            isOffline = checkOffline()
        }

        onDispose {
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // The callback may already have been removed by the system.
            }
        }
    }

    return isOffline
}

@Composable
fun OfflineShieldBanner(modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${strings.offlineModeTitle}. ${strings.offlineModeDescription}"
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${strings.offlineModeTitle}: ${strings.offlineModeDescription}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
