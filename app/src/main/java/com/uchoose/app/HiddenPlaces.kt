package com.uchoose.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import java.util.Locale

/** A restaurant the user chose never to see again. */
data class HiddenPlace(val id: String, val name: String)

/** Stable identity for a place: its name plus its location rounded to ~100 m. */
val Restaurant.placeId: String
    get() = name.trim().lowercase(Locale.US) + "@" +
        String.format(Locale.US, "%.3f,%.3f", lat, lon)

/**
 * The user's "don't show again" list, saved on the phone so it survives restarts.
 * [places] is Compose state, so screens update as soon as something is hidden or restored.
 */
object HiddenPlaces {
    private const val KEY = "hidden_places"
    private lateinit var prefs: SharedPreferences

    val places = mutableStateListOf<HiddenPlace>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("uchoose", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { entry ->
            val tab = entry.indexOf('\t')
            if (tab <= 0) null else HiddenPlace(entry.substring(0, tab), entry.substring(tab + 1))
        }
        places.clear()
        places.addAll(saved.sortedBy { it.name.lowercase() })
    }

    fun ids(): Set<String> = places.map { it.id }.toSet()

    fun hide(r: Restaurant) {
        if (places.any { it.id == r.placeId }) return
        places.add(HiddenPlace(r.placeId, r.name))
        places.sortBy { it.name.lowercase() }
        save()
    }

    fun restore(place: HiddenPlace) {
        places.removeAll { it.id == place.id }
        save()
    }

    fun restoreAll() {
        places.clear()
        save()
    }

    private fun save() {
        prefs.edit().putStringSet(KEY, places.map { "${it.id}\t${it.name}" }.toSet()).apply()
    }
}
