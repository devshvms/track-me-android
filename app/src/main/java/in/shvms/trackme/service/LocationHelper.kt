package `in`.shvms.trackme.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationHelper(private val context: Context) {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun startLocationTracking(callback: LocationCallback): Boolean {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        return try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Presence-only sampling — SCOPE_1.7.0 §4.6.
     *
     * `PRIORITY_BALANCED_POWER_ACCURACY`, not `HIGH_ACCURACY`: no ride is being recorded, so this
     * only has to answer "roughly where is everyone", and §7.4 budgets presence-without-a-ride at
     * under 4 pp/hour total. High accuracy at 2s would blow that for no product gain.
     *
     * Only ever used when there is **no** ride stream open — see [PresenceStreamPolicy]. Running
     * both at once would double the GPS draw, which §4.6 names as the reason to extend this service
     * rather than add a second one.
     */
    @SuppressLint("MissingPermission")
    fun startPresenceTracking(callback: LocationCallback): Boolean {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            PresenceStreamPolicy.PRESENCE_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(PresenceStreamPolicy.PRESENCE_MIN_INTERVAL_MS)
            .build()
        return try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun stopLocationTracking(callback: LocationCallback) {
        client.removeLocationUpdates(callback)
    }
}
