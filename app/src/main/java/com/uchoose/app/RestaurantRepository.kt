package com.uchoose.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Category(
    val title: String,
    val subtitle: String,
    val noun: String,        // used in messages, e.g. "No coffee shops found…"
    val resultLabel: String, // heading on the result screen
) {
    FAST_FOOD("Fast Food", "Quick & casual", "fast food spots", "TONIGHT YOU DINE AT"),
    SIT_DOWN("Sit Down", "Relaxed dining", "sit-down restaurants", "TONIGHT YOU DINE AT"),
    FINE_DINING("Fine Dining", "An elegant evening", "fine dining restaurants", "TONIGHT YOU DINE AT"),
    COFFEE("Coffee & Cafés", "A quiet cup", "coffee shops", "YOUR COFFEE AWAITS AT"),
    DESSERT("Dessert", "Something sweet", "dessert spots", "TREAT YOURSELF AT"),
}

/** The OpenStreetMap place type behind each result. */
enum class Kind { FAST_FOOD, RESTAURANT, CAFE, DESSERT }

data class Restaurant(
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: Kind,
    val cuisine: String?,
    val address: String?,
    val distanceMeters: Double,
) {
    val distanceMiles: Double get() = distanceMeters / 1609.344
}

/**
 * Finds restaurants with Nominatim, OpenStreetMap's free search service (no key needed).
 * It answers in well under a second, unlike the public Overpass servers, which are often overloaded.
 *
 * Nominatim's rules: identify the app in the User-Agent and send at most one request per second.
 * Results are cached for 30 minutes and pre-loaded from the home screen.
 *
 * OSM has no price level, so the categories are a best guess:
 *  - Fast Food   = amenity=fast_food
 *  - Fine Dining = restaurants whose cuisine or name suggests an upscale place
 *  - Sit Down    = every other restaurant
 *  - Coffee      = amenity=cafe
 *  - Dessert     = ice cream, bakeries, pastry & candy shops, plus cafés/fast food
 *                  whose cuisine is donuts, frozen yogurt, crêpes and the like
 */
object RestaurantRepository {

    private const val BASE = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "uChoose-Android/1.0 (github.com/tylertneal/uchoose)"

    private val fineCuisines = setOf(
        "steak_house", "steakhouse", "french", "fine_dining", "seafood",
        "oyster", "wine_bar",
    )

    private val fineNameWords = listOf(
        "steakhouse", "steak house", "chophouse", "chop house", "prime",
        "bistro", "brasserie", "trattoria", "osteria", "ristorante",
        "grille", "oyster", "oysters", "wine", "chateau", "supper club",
    ).map { Regex("\\b${Regex.escape(it)}\\b") }

    /** Extra searches that surface upscale places a generic "restaurant" search may miss. */
    private val fineSearches = listOf("steakhouse", "seafood", "bistro")

    private val chainSitDown = listOf(
        "applebee", "chili's", "olive garden", "denny", "ihop", "cracker barrel",
        "red robin", "tgi friday", "buffalo wild", "texas roadhouse", "outback",
        "red lobster", "longhorn", "ruby tuesday", "waffle house", "bob evans",
        "perkins", "golden corral", "o'charley", "friendly's", "hooters",
    )

    private data class Cached(val lat: Double, val lon: Double, val time: Long, val items: List<Restaurant>)
    private val cache = mutableMapOf<String, Cached>()
    private val cacheMutex = Mutex()
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    /** Called from the home screen so results are ready before a category is tapped. */
    suspend fun prefetch(lat: Double, lon: Double, radiusMeters: Int) {
        runCatching { base(lat, lon, radiusMeters) }
        runCatching { cafes(lat, lon, radiusMeters) }
        runCatching { desserts(lat, lon, radiusMeters) }
    }

    /** Restaurants of [category] no farther than [radiusMeters] from (lat, lon). */
    suspend fun find(
        category: Category,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        hiddenIds: Set<String> = emptySet(),
    ): List<Restaurant> {
        val pool = when (category) {
            Category.COFFEE -> cafes(lat, lon, radiusMeters)
            Category.DESSERT -> desserts(lat, lon, radiusMeters) +
                cafes(lat, lon, radiusMeters) + base(lat, lon, radiusMeters)
            Category.FINE_DINING -> base(lat, lon, radiusMeters) + fineExtras(lat, lon, radiusMeters)
            else -> base(lat, lon, radiusMeters)
        }
        return pool
            .filter { it.distanceMeters <= radiusMeters && matches(category, it) }
            .filter { it.placeId !in hiddenIds }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.distanceMeters }
            .take(12)
            .shuffled()
    }

    // Nominatim returns at most 40 places per search, so wider circles get a second page.
    private fun pagesFor(radius: Int) = if (radius > 8_100) 2 else 1

    /** Fast food + restaurants. */
    private suspend fun base(lat: Double, lon: Double, radius: Int) = cached("base-$radius", lat, lon) {
        val pages = pagesFor(radius)
        search("[amenity=fast_food]", lat, lon, radius, pages) +
            search("[amenity=restaurant]", lat, lon, radius, pages)
    }

    private suspend fun cafes(lat: Double, lon: Double, radius: Int) = cached("cafe-$radius", lat, lon) {
        search("[amenity=cafe]", lat, lon, radius, pagesFor(radius))
    }

    private val dessertSearches = listOf(
        "[amenity=ice_cream]", "[shop=bakery]", "[shop=pastry]", "[shop=confectionery]",
        "donuts", "ice cream",
    )

    private val dessertNameWords = listOf(
        "donut", "donuts", "doughnut", "doughnuts", "ice cream", "creamery", "gelato",
        "frozen yogurt", "froyo", "yogurt", "custard", "cupcake", "cupcakes", "bakery",
        "sweets", "dessert", "desserts", "crepe", "crepes", "churro", "churros",
        "krispy kreme", "dunkin", "baskin", "dairy queen", "cold stone", "rita's",
        "insomnia cookies", "crumbl", "duck donuts", "menchie", "sweet frog", "nothing bundt",
    ).map { Regex("\\b${Regex.escape(it)}\\b") }

    private val dessertCuisines = setOf(
        "dessert", "ice_cream", "frozen_yogurt", "donut", "doughnut", "cupcake",
        "crepe", "crepes", "waffle", "cake", "pastry", "gelato", "chocolate",
        "bubble_tea", "milkshake", "shaved_ice", "churros",
    )

    private suspend fun desserts(lat: Double, lon: Double, radius: Int) = cached("dessert-$radius", lat, lon) {
        dessertSearches.flatMap { q ->
            runCatching { search(q, lat, lon, radius, 1) }.getOrDefault(emptyList())
        }
    }

    private fun isDessert(r: Restaurant): Boolean {
        if (r.kind == Kind.DESSERT) return true
        val name = r.name.lowercase()
        if (dessertNameWords.any { it.containsMatchIn(name) }) return true
        if (r.kind == Kind.RESTAURANT) return false
        val cuisines = r.cuisine?.lowercase()?.split(';', ',')?.map { it.trim() } ?: emptyList()
        return cuisines.any { it in dessertCuisines }
    }

    private suspend fun fineExtras(lat: Double, lon: Double, radius: Int) = cached("fine-$radius", lat, lon) {
        fineSearches.flatMap { term ->
            runCatching { search(term, lat, lon, radius, 1) }.getOrDefault(emptyList())
        }
    }

    private suspend fun cached(
        key: String,
        lat: Double,
        lon: Double,
        load: suspend () -> List<Restaurant>,
    ): List<Restaurant> = cacheMutex.withLock {
        cache[key]?.let { c ->
            val fresh = System.currentTimeMillis() - c.time < 30 * 60 * 1000
            if (fresh && distance(lat, lon, c.lat, c.lon) < 800) {
                return@withLock c.items.map { it.copy(distanceMeters = distance(lat, lon, it.lat, it.lon)) }
            }
        }
        val items = load()
        cache[key] = Cached(lat, lon, System.currentTimeMillis(), items)
        items
    }

    private fun matches(category: Category, r: Restaurant): Boolean {
        when (category) {
            Category.FAST_FOOD -> return r.kind == Kind.FAST_FOOD
            Category.COFFEE -> return r.kind == Kind.CAFE
            Category.DESSERT -> return isDessert(r)
            else -> if (r.kind != Kind.RESTAURANT) return false
        }
        val name = r.name.lowercase()
        val cuisines = r.cuisine?.lowercase()?.split(';', ',')?.map { it.trim() } ?: emptyList()
        val isChain = chainSitDown.any { name.contains(it) }
        val looksFine = !isChain && (
            cuisines.any { it in fineCuisines } ||
                fineNameWords.any { it.containsMatchIn(name) }
            )
        return if (category == Category.FINE_DINING) looksFine else !looksFine
    }

    /** Nominatim search inside a box around us, at most one request per second. */
    private suspend fun search(q: String, lat: Double, lon: Double, radius: Int, pages: Int): List<Restaurant> {
        val dLat = radius / 111_320.0
        val dLon = radius / (111_320.0 * cos(Math.toRadians(lat)))
        val viewbox = "${lon - dLon},${lat + dLat},${lon + dLon},${lat - dLat}"
        val results = mutableListOf<Restaurant>()
        val seenIds = mutableListOf<Long>()

        repeat(pages) {
            var url = BASE +
                "?q=" + URLEncoder.encode(q, "UTF-8") +
                "&viewbox=" + URLEncoder.encode(viewbox, "UTF-8") +
                "&bounded=1&limit=40&format=jsonv2&addressdetails=1&extratags=1"
            if (seenIds.isNotEmpty()) {
                url += "&exclude_place_ids=" + seenIds.joinToString(",")
            }
            val json = requestMutex.withLock {
                val wait = 1_100 - (System.currentTimeMillis() - lastRequestAt)
                if (wait > 0) delay(wait)
                try {
                    withContext(Dispatchers.IO) { get(url) }
                } finally {
                    lastRequestAt = System.currentTimeMillis()
                }
            }
            val (page, ids) = parse(json, lat, lon)
            results += page
            seenIds += ids
            if (ids.size < 40) return results // no more pages
        }
        return results
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "en")
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String, myLat: Double, myLon: Double): Pair<List<Restaurant>, List<Long>> {
        val arr = JSONArray(json)
        val out = mutableListOf<Restaurant>()
        val ids = mutableListOf<Long>()
        for (i in 0 until arr.length()) {
            val el = arr.getJSONObject(i)
            el.optLong("place_id", -1L).takeIf { it > 0 }?.let { ids += it }
            val type = el.optString("type")
            val kind = when (type) {
                "restaurant" -> Kind.RESTAURANT
                "fast_food" -> Kind.FAST_FOOD
                "cafe" -> Kind.CAFE
                "ice_cream", "bakery", "pastry", "confectionery" -> Kind.DESSERT
                else -> continue
            }
            val name = el.optString("name").takeIf { it.isNotBlank() } ?: continue
            val lat = el.optString("lat").toDoubleOrNull() ?: continue
            val lon = el.optString("lon").toDoubleOrNull() ?: continue

            val addr = el.optJSONObject("address")
            val street = listOfNotNull(
                addr?.optString("house_number")?.takeIf { it.isNotBlank() },
                addr?.optString("road")?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            val city = listOf("city", "town", "village", "suburb")
                .firstNotNullOfOrNull { k -> addr?.optString(k)?.takeIf { it.isNotBlank() } }
            val address = listOf(street, city ?: "").filter { it.isNotBlank() }
                .joinToString(", ").takeIf { it.isNotBlank() }

            out += Restaurant(
                name = name,
                lat = lat,
                lon = lon,
                kind = kind,
                cuisine = el.optJSONObject("extratags")?.optString("cuisine")?.takeIf { it.isNotBlank() },
                address = address,
                distanceMeters = distance(myLat, myLon, lat, lon),
            )
        }
        return out to ids
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
