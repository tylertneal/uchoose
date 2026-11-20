package com.uchoose.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import java.text.Normalizer
import java.util.Locale

/** A place the user chose never to see again, at any location. */
data class HiddenPlace(val id: String, val name: String)

/**
 * Turns a name into a matching key that ignores case, accents, punctuation and spacing,
 * so "McDonald's", "McDonalds" and "Mcdonald’s" all become "mcdonalds".
 */
fun nameKey(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")       // strip accents
        .lowercase(Locale.US)
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]"), "")      // drop apostrophes, spaces, punctuation

/** Keys this restaurant can be hidden by: its name, and its chain brand if the map lists one. */
val Restaurant.hideKeys: Set<String>
    get() = setOfNotNull(nameKey(name), brand?.let { nameKey(it) }).filter { it.isNotEmpty() }.toSet()

/**
 * The user's "don't show again" list, saved on the phone so it survives restarts.
 * Hiding works by name/brand, so hiding one McDonald's hides every McDonald's.
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
            if (tab <= 0) return@mapNotNull null
            val name = entry.substring(tab + 1)
            // Older versions saved "name@lat,lon" (one location only). Upgrade those to
            // name-based keys so they now hide every location of that place.
            HiddenPlace(nameKey(name), name)
        }.distinctBy { it.id }
        places.clear()
        places.addAll(saved.sortedBy { it.name.lowercase() })
        save()
    }

    fun ids(): Set<String> = places.map { it.id }.toSet()

    fun isHidden(r: Restaurant, hidden: Set<String> = ids()): Boolean = r.hideKeys.any { it in hidden }

    fun hide(r: Restaurant) {
        if (isHidden(r)) return
        // Prefer the chain brand (e.g. "McDonald's") so every branch is covered.
        val label = r.brand?.takeIf { it.isNotBlank() } ?: r.name
        places.add(HiddenPlace(nameKey(label), label))
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
