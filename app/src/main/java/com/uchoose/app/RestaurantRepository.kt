package com.uchoose.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
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
    val isFastFood: Boolean,
    val cuisine: String?,
    val address: String?,
    val distanceMeters: Double,
) {
    val distanceMiles: Double get() = distanceMeters / 1609.344
}

/**
 * Finds restaurants with OpenStreetMap's free Overpass API (no key needed).
 *
 * Speed tricks:
 *  - ONE request fetches fast food and restaurants together, so all three
 *    categories come from the same result.
 *  - The request goes to several public servers at once and the first answer wins.
 *  - Results are cached for 30 minutes, and the home screen pre-loads them in the
 *    background, so tapping a category is usually instant.
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
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    private const val NEAR_RADIUS = 8_000   // ~5 miles
    private const val WIDE_RADIUS = 24_000  // ~15 miles, used only if nearby is sparse

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

    // Network calls run here so a slow server we've stopped waiting on can't block anything.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Cached(val lat: Double, val lon: Double, val radius: Int, val time: Long, val items: List<Restaurant>)
    private val cache = mutableMapOf<Int, Cached>()
    private val mutex = Mutex()

    /** Called from the home screen so results are ready before a category is tapped. */
    suspend fun prefetch(lat: Double, lon: Double) {
        runCatching { nearby(lat, lon, NEAR_RADIUS) }
    }

    suspend fun find(category: Category, lat: Double, lon: Double): List<Restaurant> {
        var picked = filter(category, nearby(lat, lon, NEAR_RADIUS))
        if (picked.size < 4) {
            picked = filter(category, nearby(lat, lon, WIDE_RADIUS))
        }
        return picked.take(12).shuffled()
    }

    private fun filter(category: Category, all: List<Restaurant>): List<Restaurant> =
        all.filter { matches(category, it) }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.distanceMeters }

    private suspend fun nearby(lat: Double, lon: Double, radius: Int): List<Restaurant> =
        mutex.withLock {
            cache[radius]?.let { c ->
                val fresh = System.currentTimeMillis() - c.time < 30 * 60 * 1000
                if (fresh && distance(lat, lon, c.lat, c.lon) < 800) {
                    // Recompute distances from where we are now.
                    return@withLock c.items.map { it.copy(distanceMeters = distance(lat, lon, it.lat, it.lon)) }
                }
            }
            val items = query(lat, lon, radius)
            cache[radius] = Cached(lat, lon, radius, System.currentTimeMillis(), items)
            items
        }

    private fun matches(category: Category, r: Restaurant): Boolean {
        if (category == Category.FAST_FOOD) return r.isFastFood
        if (r.isFastFood) return false
        val name = r.name.lowercase()
        val cuisines = r.cuisine?.lowercase()?.split(';', ',')?.map { it.trim() } ?: emptyList()
        val isChain = chainSitDown.any { name.contains(it) }
        val looksFine = !isChain && (
            cuisines.any { it in fineCuisines } ||
                fineNameWords.any { it.containsMatchIn(name) }
            )
        return if (category == Category.FINE_DINING) looksFine else !looksFine
    }

    private suspend fun query(lat: Double, lon: Double, radius: Int): List<Restaurant> {
        val ql = """
            [out:json][timeout:20];
            (
              node["amenity"~"^(restaurant|fast_food)$"]["name"](around:$radius,$lat,$lon);
              way["amenity"~"^(restaurant|fast_food)$"]["name"](around:$radius,$lat,$lon);
            );
            out center tags;
        """.trimIndent()
        val body = "data=" + URLEncoder.encode(ql, "UTF-8")
        val json = withTimeout(25_000) { raceEndpoints(body) }
        return parse(json, lat, lon)
    }

    /** Sends the same request to every server at once and returns the first good answer. */
    private suspend fun raceEndpoints(body: String): String {
        val winner = CompletableDeferred<String>()
        val failures = AtomicInteger(0)
        var lastError: Exception? = null
        endpoints.forEach { endpoint ->
            ioScope.launch {
                try {
                    val text = post(endpoint, body)
                    // Overpass sometimes answers 200 with an error instead of data.
                    if (!text.trimStart().startsWith("{") || !text.contains("\"elements\"")) {
                        throw IllegalStateException("Bad response from $endpoint")
                    }
                    winner.complete(text)
                } catch (e: Exception) {
                    lastError = e
                    if (failures.incrementAndGet() == endpoints.size) {
                        winner.completeExceptionally(lastError ?: e)
                    }
                }
            }
        }
        return winner.await()
    }

    private fun post(endpoint: String, body: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 22_000
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
                isFastFood = tags.optString("amenity") == "fast_food",
                cuisine = tags.optString("cuisine").takeIf { it.isNotBlank() },
                address = address,
                distanceMeters = distance(myLat, myLon, lat, lon),
            )
        }
        return out
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
