package com.uchoose.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Gets the phone's location with the built-in LocationManager (no Google Play Services needed). */
object LocationHelper {

    private const val FRESH_MS = 30 * 60 * 1000L   // a fix this recent is good enough

    private var lastFix: Location? = null

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        val now = System.currentTimeMillis()
        lastFix?.let { if (now - it.time < FRESH_MS) return it }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        // Restaurants are miles apart, so the phone's last known spot is usually fine.
        val cached = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (cached != null && now - cached.time < FRESH_MS) {
            lastFix = cached
            return cached
        }

        // Ask Wi-Fi/cell and GPS at the same time and use whichever answers first.
        val live = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in providers }
        val fresh = if (live.isEmpty()) null else withTimeoutOrNull(8_000) { firstFix(lm, live) }
        val result = fresh ?: cached
        if (result != null) lastFix = result
        return result
    }

    private suspend fun firstFix(lm: LocationManager, providers: List<String>): Location? = coroutineScope {
        val results = Channel<Location?>(providers.size)
        val jobs = providers.map { p ->
            launch { results.send(runCatching { requestOnce(lm, p) }.getOrNull()) }
        }
        var fix: Location? = null
        var received = 0
        while (fix == null && received < providers.size) {
            fix = results.receive()
            received++
        }
        jobs.forEach { it.cancel() }
        fix
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, { it.run() }) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }
}
