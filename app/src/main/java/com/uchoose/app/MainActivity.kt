package com.uchoose.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

sealed interface Screen {
    data object Home : Screen
    data class Wheel(val category: Category) : Screen
    data class Result(val category: Category, val restaurant: Restaurant) : Screen
    data object Hidden : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UChooseTheme {
                Surface(Modifier.fillMaxSize(), color = Palette.Background) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val context = LocalContext.current

    fun hasLocation() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    val prefs = remember {
        HiddenPlaces.init(context)
        context.getSharedPreferences("uchoose", Context.MODE_PRIVATE)
    }
    var radiusMiles by remember { mutableIntStateOf(prefs.getInt("radius_miles", 5)) }
    val radiusMeters = (radiusMiles * 1609.344).toInt()

    // Warm up location + restaurant data while the user is still on the home screen.
    LaunchedEffect(radiusMiles) {
        if (hasLocation()) {
            val loc = LocationHelper.current(context)
            if (loc != null) RestaurantRepository.prefetch(loc.latitude, loc.longitude, radiusMeters)
        }
    }

    var pending by remember { mutableStateOf<Category?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        val cat = pending
        pending = null
        if (granted && cat != null) {
            permissionDenied = false
            screen = Screen.Wheel(cat)
        } else {
            permissionDenied = true
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = when (val s = screen) {
            Screen.Hidden -> Screen.Home
            is Screen.Result -> Screen.Wheel(s.category)
            else -> Screen.Home
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                permissionDenied = permissionDenied,
                radiusMiles = radiusMiles,
                hiddenCount = HiddenPlaces.places.size,
                onShowHidden = { screen = Screen.Hidden },
                onRadiusChange = {
                    radiusMiles = it
                    prefs.edit().putInt("radius_miles", it).apply()
                },
            ) { cat ->
                if (hasLocation()) {
                    screen = Screen.Wheel(cat)
                } else {
                    pending = cat
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
            }
            is Screen.Wheel -> WheelScreen(
                category = s.category,
                radiusMiles = radiusMiles,
                onBack = { screen = Screen.Home },
                onChosen = { screen = Screen.Result(s.category, it) },
            )
            is Screen.Result -> ResultScreen(
                category = s.category,
                restaurant = s.restaurant,
                onSpinAgain = { screen = Screen.Wheel(s.category) },
                onHome = { screen = Screen.Home },
                onHide = {
                    HiddenPlaces.hide(s.restaurant)
                    screen = Screen.Wheel(s.category)
                },
            )
            Screen.Hidden -> HiddenScreen(onBack = { screen = Screen.Home })
        }
    }
}

// ---------- Home ----------

@Composable
fun HomeScreen(
    permissionDenied: Boolean,
    radiusMiles: Int,
    hiddenCount: Int,
    onShowHidden: () -> Unit,
    onRadiusChange: (Int) -> Unit,
    onPick: (Category) -> Unit,
) {
    // Everything fits on one screen, no scrolling: the cards share whatever height is left
    // between the header and the distance picker, up to a comfortable maximum each.
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("uChoose", style = Type.Display)
        Spacer(Modifier.height(6.dp))
        Text("Where shall we dine?", style = Type.Body.copy(fontSize = 17.sp))
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(Modifier.width(48.dp), color = Palette.Gold, thickness = 1.dp)
        Spacer(Modifier.height(20.dp))

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Category.entries.forEach { cat ->
                CategoryCard(
                    cat,
                    Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 40.dp, max = 84.dp),
                ) { onPick(cat) }
            }
        }

        if (permissionDenied) {
            Spacer(Modifier.height(10.dp))
            Text(
                "uChoose needs your location to find restaurants nearby. " +
                    "You can allow it in Settings → Apps → uChoose → Permissions.",
                style = Type.Body.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))
        if (hiddenCount > 0) {
            Text(
                "Hidden places ($hiddenCount)",
                style = Type.Body.copy(fontSize = 13.sp),
                modifier = Modifier.clickable(onClick = onShowHidden).padding(6.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        RadiusPicker(radiusMiles, onRadiusChange)
        Spacer(Modifier.height(20.dp))
    }
}

private val radiusOptions = listOf(1, 3, 5, 10, 15, 25)

/** "WITHIN" label and a row of mile options; the selected one is gold with a thin underline. */
@Composable
fun RadiusPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("WITHIN", style = Type.Label.copy(fontSize = 11.sp, color = Palette.Muted))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            radiusOptions.forEach { miles ->
                val isSel = miles == selected
                Column(
                    Modifier
                        .clickable { onSelect(miles) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "$miles mi",
                        style = Type.Body.copy(
                            fontSize = 15.sp,
                            color = if (isSel) Palette.Gold else Palette.Muted,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(
                        Modifier.width(20.dp),
                        color = if (isSel) Palette.Gold else Color.Transparent,
                        thickness = 1.dp,
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryCard(category: Category, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .border(1.dp, Palette.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // On short screens the cards shrink; drop the subtitle before anything gets cramped.
        val roomy = maxHeight >= 64.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(category.title.uppercase(), style = Type.Label.copy(fontSize = 15.sp))
            if (roomy) {
                Spacer(Modifier.height(4.dp))
                Text(category.subtitle, style = Type.Body)
            }
        }
    }
}

// ---------- Wheel ----------

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val items: List<Restaurant>) : LoadState
    data class Error(val message: String) : LoadState
}

@Composable
fun WheelScreen(
    category: Category,
    radiusMiles: Int,
    onBack: () -> Unit,
    onChosen: (Restaurant) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val rotation = remember { Animatable(0f) }
    var spinning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Finding your location…") }
    var toHide by remember { mutableStateOf<Restaurant?>(null) }
    val hiddenCount = HiddenPlaces.places.size

    LaunchedEffect(category, attempt, radiusMiles, hiddenCount) {
        state = LoadState.Loading
        status = "Finding your location…"
        state = try {
            val loc = LocationHelper.current(context)
            status = "Searching for restaurants…"
            if (loc == null) {
                LoadState.Error("Couldn't get your location. Make sure Location is turned on.")
            } else {
                val list = withTimeoutOrNull(30_000) {
                    RestaurantRepository.find(
                        category, loc.latitude, loc.longitude, (radiusMiles * 1609.344).toInt(),
                        HiddenPlaces.ids(),
                    )
                } ?: throw IllegalStateException("Timed out")
                if (list.size < 2) {
                    LoadState.Error(
                        "No ${category.noun} found within $radiusMiles " +
                            (if (radiusMiles == 1) "mile" else "miles") +
                            ". Try a larger distance on the home screen.",
                    )
                } else {
                    LoadState.Ready(list)
                }
            }
        } catch (e: Exception) {
            LoadState.Error("Couldn't reach the restaurant service. Check your connection and try again.")
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                "‹  Back",
                style = Type.Body,
                modifier = Modifier.clickable(enabled = !spinning, onClick = onBack).padding(8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(category.title.uppercase(), style = Type.Label)
        Spacer(Modifier.height(8.dp))
        Text("Let fate decide", style = Type.Title)
        Spacer(Modifier.height(40.dp))

        when (val s = state) {
            LoadState.Loading -> {
                Spacer(Modifier.height(80.dp))
                CircularProgressIndicator(color = Palette.Gold, strokeWidth = 1.5.dp)
                Spacer(Modifier.height(20.dp))
                Text(status, style = Type.Body)
            }
            is LoadState.Error -> {
                Spacer(Modifier.height(80.dp))
                Text(s.message, style = Type.Body, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                OutlineButton("TRY AGAIN") { attempt++ }
            }
            is LoadState.Ready -> {
                val items = s.items
                RestaurantWheel(
                    names = items.map { it.name },
                    rotation = rotation.value,
                    spinning = spinning,
                    onSpin = {
                        spinning = true
                        scope.launch {
                            val n = items.size
                            val sweep = 360f / n
                            val pick = Random.nextInt(n)
                            val jitter = (Random.nextFloat() - 0.5f) * sweep * 0.6f
                            // Rotation that puts the middle of segment `pick` under the top pointer
                            val landing = ((-(pick + 0.5f) * sweep + jitter) % 360f + 360f) % 360f
                            val current = rotation.value
                            val base = current - (current % 360f)
                            val target = base + 360f * 6 + landing
                            rotation.animateTo(
                                target,
                                animationSpec = tween(
                                    durationMillis = 4200,
                                    easing = CubicBezierEasing(0.15f, 0.85f, 0.25f, 1f),
                                ),
                            )
                            delay(600)
                            spinning = false
                            onChosen(items[pick])
                        }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    onLongPressSegment = { i -> toHide = items.getOrNull(i) },
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    "${items.size} places within $radiusMiles mi",
                    style = Type.Body.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Press and hold a name to hide it",
                    style = Type.Body.copy(fontSize = 12.sp, color = Palette.Muted.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Restaurant data © OpenStreetMap contributors",
                    style = Type.Body.copy(fontSize = 11.sp, color = Palette.Muted.copy(alpha = 0.6f)),
                )
            }
        }
    }

    toHide?.let { r ->
        HideDialog(
            name = r.name,
            onConfirm = {
                HiddenPlaces.hide(r)
                toHide = null
            },
            onDismiss = { toHide = null },
        )
    }
}

@Composable
fun HideDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Hide this place?", style = Type.Title.copy(fontSize = 20.sp)) },
        text = {
            Text(
                "$name won't appear on your wheel anymore. " +
                    "You can bring it back from Hidden places on the home screen.",
                style = Type.Body,
            )
        },
        confirmButton = {
            Text(
                "HIDE",
                style = Type.Label,
                modifier = Modifier.clickable(onClick = onConfirm).padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                "CANCEL",
                style = Type.Label.copy(color = Palette.Muted),
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
            )
        },
    )
}

// ---------- Hidden places ----------

@Composable
fun HiddenScreen(onBack: () -> Unit) {
    val places = HiddenPlaces.places
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "‹  Back",
            style = Type.Body,
            modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "HIDDEN PLACES",
            style = Type.Label,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "These never appear on your wheel.",
            style = Type.Body,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(28.dp))

        if (places.isEmpty()) {
            Text(
                "Nothing hidden.",
                style = Type.Body,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(places.toList(), key = { it.id }) { place ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            place.name,
                            style = Type.Body.copy(fontSize = 17.sp, color = Palette.Text),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "RESTORE",
                            style = Type.Label.copy(fontSize = 12.sp),
                            modifier = Modifier.clickable { HiddenPlaces.restore(place) }.padding(8.dp),
                        )
                    }
                    HorizontalDivider(color = Palette.Line, thickness = 0.5.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlineButton("RESTORE ALL") { HiddenPlaces.restoreAll() }
        }
    }
}

// ---------- Result ----------

@Composable
fun ResultScreen(
    category: Category,
    restaurant: Restaurant,
    onSpinAgain: () -> Unit,
    onHome: () -> Unit,
    onHide: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(category.resultLabel, style = Type.Label)
        Spacer(Modifier.height(20.dp))
        Text(
            restaurant.name,
            style = Type.Display.copy(fontSize = 34.sp, letterSpacing = 0.5.sp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(Modifier.width(48.dp), color = Palette.Gold, thickness = 1.dp)
        Spacer(Modifier.height(20.dp))

        restaurant.cuisine?.let {
            Text(
                it.split(';').joinToString(" · ") { c ->
                    c.trim().replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() }
                },
                style = Type.Body,
            )
            Spacer(Modifier.height(6.dp))
        }
        restaurant.address?.let {
            Text(it, style = Type.Body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
        }
        Text(String.format("%.1f miles away", restaurant.distanceMiles), style = Type.Body)

        Spacer(Modifier.height(48.dp))
        OutlineButton("GET DIRECTIONS", filled = true) {
            val label = Uri.encode(restaurant.name)
            val geo = Uri.parse("geo:${restaurant.lat},${restaurant.lon}?q=${restaurant.lat},${restaurant.lon}($label)")
            val intent = Intent(Intent.ACTION_VIEW, geo)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${restaurant.lat},${restaurant.lon}"),
                    ),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlineButton("SPIN AGAIN", onClick = onSpinAgain)
        Spacer(Modifier.height(14.dp))
        Text(
            "Home",
            style = Type.Body,
            modifier = Modifier.clickable(onClick = onHome).padding(8.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Don't show this place again",
            style = Type.Body.copy(fontSize = 13.sp, color = Palette.Muted.copy(alpha = 0.8f)),
            modifier = Modifier.clickable(onClick = onHide).padding(8.dp),
        )
    }
}

@Composable
fun OutlineButton(text: String, filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (filled) {
                    Modifier.border(1.dp, Palette.Gold, shape)
                        .then(Modifier.padding(0.dp))
                } else {
                    Modifier.border(1.dp, Palette.Line, shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Label.copy(color = if (filled) Palette.Gold else Palette.Text))
    }
}
