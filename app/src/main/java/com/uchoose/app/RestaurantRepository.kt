package com.uchoose.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Category(val title: String, val subtitle: String) {
    FAST_FOOD("Fast Food", "Quick & casual"),
    SIT_DOWN("Sit Down", "Relaxed dining"),
    FINE_DINING("Fine Dining", "An elegant evening"),
}

data class Restaurant(
    val name: String,
    val lat: Double,
    val lon: Double,
    val cuisine: String?,
    val address: String?,
    val distanceMeters: Double,
) {
    val distanceMiles: Double get() = distanceMeters / 1609.344
}

/**
 * Finds restaurants with OpenStreetMap's free Overpass API (no key needed).
 *
 * OSM has no price level, so the categories are a best guess:
 *  - Fast Food   = amenity=fast_food
 *  - Fine Dining = restaurants whose cuisine or name suggests an upscale place
 *  - Sit Down    = every other restaurant
 */
object RestaurantRepository {

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private val fineCuisines = setOf(
        "steak_house", "steakhouse", "french", "fine_dining", "seafood",
        "oyster", "wine_bar",
    )

    private val fineNameWords = listOf(
        "steakhouse", "steak house", "chophouse", "chop house", "prime",
        "bistro", "brasserie", "trattoria", "osteria", "ristorante",
        "grille", "oyster", "oysters", "wine", "chateau", "supper club",
    ).map { Regex("\\b${Regex.escape(it)}\\b") }

    private val chainSitDown = listOf(
        "applebee", "chili's", "olive garden", "denny", "ihop", "cracker barrel",
        "red robin", "tgi friday", "buffalo wild", "texas roadhouse", "outback",
        "red lobster", "longhorn", "ruby tuesday", "waffle house", "bob evans",
        "perkins", "golden corral", "o'charley", "friendly's", "hooters",
    )

    private data class Cached(val lat: Double, val lon: Double, val time: Long, val items: List<Restaurant>)
    private val cache = mutableMapOf<Category, Cached>()

    suspend fun find(category: Category, lat: Double, lon: Double): List<Restaurant> {
        // Reuse results for "Spin again" if we haven't moved much in the last 15 minutes.
        cache[category]?.let { c ->
            val fresh = System.currentTimeMillis() - c.time < 15 * 60 * 1000
            if (fresh && distance(lat, lon, c.lat, c.lon) < 800) return c.items.shuffled()
        }

        // Start at about 5 miles and widen if there are only a few results.
        for (radius in listOf(8_000, 16_000, 32_000)) {
            val all = query(category, lat, lon, radius)
            val filtered = all.filter { matches(category, it) }
                .distinctBy { it.name.lowercase() }
                .sortedBy { it.distanceMeters }
            if (filtered.size >= 4 || radius == 32_000) {
                val picked = filtered.take(12)
                if (picked.size >= 2) cache[category] = Cached(lat, lon, System.currentTimeMillis(), picked)
                return picked.shuffled()
            }
        }
        return emptyList()
    }

    private fun matches(category: Category, r: Restaurant): Boolean {
        if (category == Category.FAST_FOOD) return true
        val name = r.name.lowercase()
        val cuisines = r.cuisine?.lowercase()?.split(';', ',')?.map { it.trim() } ?: emptyList()
        val isChain = chainSitDown.any { name.contains(it) }
        val looksFine = !isChain && (
            cuisines.any { it in fineCuisines } ||
                fineNameWords.any { it.containsMatchIn(name) }
            )
        return if (category == Category.FINE_DINING) looksFine else !looksFine
    }

    private suspend fun query(category: Category, lat: Double, lon: Double, radius: Int): List<Restaurant> =
        withContext(Dispatchers.IO) {
            val amenity = if (category == Category.FAST_FOOD) "fast_food" else "restaurant"
            val ql = """
                [out:json][timeout:25];
                (
                  node["amenity"="$amenity"]["name"](around:$radius,$lat,$lon);
                  way["amenity"="$amenity"]["name"](around:$radius,$lat,$lon);
                );
                out center tags;
            """.trimIndent()
            val body = "data=" + URLEncoder.encode(ql, "UTF-8")

            var lastError: Exception? = null
            for (endpoint in endpoints) {
                try {
                    val json = post(endpoint, body)
                    return@withContext parse(json, lat, lon)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No restaurant service reachable")
        }

    private fun post(endpoint: String, body: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "uChoose-Android/1.0")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String, myLat: Double, myLon: Double): List<Restaurant> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<Restaurant>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: continue
            val center = el.optJSONObject("center")
            val lat = if (el.has("lat")) el.getDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (el.has("lon")) el.getDouble("lon") else center?.optDouble("lon") ?: continue

            val street = listOfNotNull(
                tags.optString("addr:housenumber").takeIf { it.isNotBlank() },
                tags.optString("addr:street").takeIf { it.isNotBlank() },
            ).joinToString(" ")
            val city = tags.optString("addr:city").takeIf { it.isNotBlank() }
            val address = listOf(street, city ?: "").filter { it.isNotBlank() }
                .joinToString(", ").takeIf { it.isNotBlank() }

            out += Restaurant(
                name = name,
                lat = lat,
                lon = lon,
                cuisine = tags.optString("cuisine").takeIf { it.isNotBlank() },
                address = address,
                distanceMeters = distance(myLat, myLon, lat, lon),
            )
        }
        return out
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
