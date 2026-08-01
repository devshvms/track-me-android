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

    fun stopLocationTracking(callback: LocationCallback) {
        client.removeLocationUpdates(callback)
    }
}
