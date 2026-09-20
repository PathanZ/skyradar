package com.zaid.skyradar.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.math.*

data class Aircraft(
    val icao24: String,
    val callsign: String?,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val velocityKt: Int?,
    val heading: Double?
)

class MainActivity : ComponentActivity() {

    private var hasLocationPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hasLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            RadarApp(
                hasPermission = hasLocationPermission,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            )
        }
    }
}

@Composable
fun RadarApp(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var userLat by remember { mutableStateOf<Double?>(null) }
    var userLon by remember { mutableStateOf<Double?>(null) }
    var aircraft by remember { mutableStateOf<List<Aircraft>>(emptyList()) }
    var lastUpdateText by remember { mutableStateOf("--") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Aircraft?>(null) }
    var sweepAngle by remember { mutableStateOf(0f) }

    val radiusDeg = 0.5
    val refreshMs = 45_000L
    val sweepPeriodMs = 3000f

    // sweep animation loop
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val dtMs = (now - last) / 1_000_000f
            last = now
            sweepAngle = (sweepAngle + (360f * dtMs / sweepPeriodMs)) % 360f
        }
    }

    // location fetch once started
    LaunchedEffect(started, hasPermission) {
        if (!started) return@LaunchedEffect
        if (!hasPermission) {
            errorText = "Location permission needed"
            return@LaunchedEffect
        }
        try {
            val lm = context.getSystemService(LocationManager::class.java)
            val providers = lm.getProviders(true)
            var loc: android.location.Location? = null
            for (p in providers) {
                loc = lm.getLastKnownLocation(p)
                if (loc != null) break
            }
            if (loc != null) {
                userLat = loc.latitude
                userLon = loc.longitude
            } else {
                // fallback: Surrey, BC
                userLat = 49.19
                userLon = -122.85
                errorText = "No fix — using Surrey, BC"
            }
        } catch (e: SecurityException) {
            userLat = 49.19
            userLon = -122.85
            errorText = "Permission denied — using Surrey, BC"
        }
    }

    // aircraft refresh loop
    LaunchedEffect(started, userLat, userLon) {
        val lat = userLat ?: return@LaunchedEffect
        val lon = userLon ?: return@LaunchedEffect
        if (!started) return@LaunchedEffect

        while (isActive) {
            try {
                val list = withContext(Dispatchers.IO) { fetchAircraft(lat, lon, radiusDeg) }
                aircraft = list
                lastUpdateText = "UPD " + java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
                errorText = null
            } catch (e: Exception) {
                Log.e("SkyRadar", "fetch failed", e)
                if (aircraft.isEmpty()) errorText = "API unavailable"
            }
            delay(refreshMs)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030F07)),
        contentAlignment = Alignment.Center
    ) {
        if (!started) {
            SplashScreen(onStart = {
                started = true
                if (!hasPermission) onRequestPermission()
            })
        } else {
            RadarCanvas(
                userLat = userLat,
                userLon = userLon,
                aircraft = aircraft,
                radiusDeg = radiusDeg,
                sweepAngleDeg = sweepAngle,
                selected = selected,
                onTap = { tapped ->
                    selected = tapped
                    if (tapped != null) {
                        val vib = context.getSystemService(Vibrator::class.java)
                        vib?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            )
            HudOverlay(count = aircraft.size, updateText = lastUpdateText, error = errorText, selected = selected)
        }
    }
}

@Composable
fun SplashScreen(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SKY RADAR", color = Color(0xFF00FF88), fontSize = 20.sp)
            Text("ADS-B · LIVE", color = Color(0xCC00FF88), fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStart) { Text("SCAN ›") }
        }
    }
}

@Composable
fun HudOverlay(count: Int, updateText: String, error: String?, selected: Aircraft?) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("AIRCRAFT", color = Color(0xFF00FF88), fontSize = 9.sp)
            Text("$count", color = Color(0xFF00FF88), fontSize = 18.sp)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("RANGE: 55 KM", color = Color(0x9900FF88), fontSize = 8.sp)
            Text(updateText, color = Color(0xFF00CC66), fontSize = 8.sp)
        }
        if (error != null) {
            Text(
                error,
                color = Color(0xFFFF4466),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (selected != null) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(selected.callsign ?: selected.icao24.uppercase(), color = Color(0xFFFFB700), fontSize = 12.sp)
                val alt = selected.altitudeM?.let { "${(it * 3.281).roundToInt()}ft" } ?: "---"
                val spd = selected.velocityKt?.let { "${it}kt" } ?: "---"
                val hdg = selected.heading?.let { "${it.roundToInt()}°" } ?: "---"
                Text("$alt · $spd · $hdg", color = Color(0xFF00FF88), fontSize = 8.sp)
            }
        }
    }
}

@Composable
fun RadarCanvas(
    userLat: Double?,
    userLon: Double?,
    aircraft: List<Aircraft>,
    radiusDeg: Double,
    sweepAngleDeg: Float,
    selected: Aircraft?,
    onTap: (Aircraft?) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(aircraft, userLat, userLon) {
                detectTapGestures { tapOffset ->
                    if (userLat == null || userLon == null) return@detectTapGestures
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.width / 2f * 0.82f
                    var closest: Aircraft? = null
                    var minDist = 40f // dp-ish tap radius
                    for (a in aircraft) {
                        val dx = ((a.lon - userLon) / radiusDeg).toFloat()
                        val dy = ((a.lat - userLat) / radiusDeg).toFloat()
                        val px = cx + dx * r
                        val py = cy - dy * r
                        val d = hypot(px - tapOffset.x, py - tapOffset.y)
                        if (d < minDist) { minDist = d; closest = a }
                    }
                    onTap(closest)
                }
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = size.width / 2f
        val radarR = maxR * 0.82f

        // range rings
        listOf(0.33f, 0.66f, 1f).forEachIndexed { i, f ->
            drawCircle(
                color = if (i == 2) Color(0x3000FF88) else Color(0x1800FF88),
                radius = radarR * f,
                center = Offset(cx, cy),
                style = Stroke(width = if (i == 2) 2f else 1f)
            )
        }

        // crosshairs
        drawLine(Color(0x1400FF88), Offset(cx, cy - radarR), Offset(cx, cy + radarR), 1f)
        drawLine(Color(0x1400FF88), Offset(cx - radarR, cy), Offset(cx + radarR, cy), 1f)

        // sweep
        val sweepRad = Math.toRadians(sweepAngleDeg.toDouble())
        drawLine(
            color = Color(0xCC00FF88),
            start = Offset(cx, cy),
            end = Offset(cx + (cos(sweepRad) * radarR).toFloat(), cy + (sin(sweepRad) * radarR).toFloat()),
            strokeWidth = 3f
        )

        // sweep glow trail
        for (i in 0 until 30) {
            val a = Math.toRadians((sweepAngleDeg - i * 3).toDouble())
            val alpha = (1f - i / 30f) * 0.15f
            drawLine(
                color = Color(0f, 1f, 0.53f, alpha),
                start = Offset(cx, cy),
                end = Offset(cx + (cos(a) * radarR).toFloat(), cy + (sin(a) * radarR).toFloat()),
                strokeWidth = 2f
            )
        }

        // center dot
        drawCircle(Color(0xFF00FF88), radius = 4f, center = Offset(cx, cy))

        // aircraft blips
        if (userLat != null && userLon != null) {
            for (a in aircraft) {
                val dx = ((a.lon - userLon) / radiusDeg).toFloat()
                val dy = ((a.lat - userLat) / radiusDeg).toFloat()
                val px = cx + dx * radarR
                val py = cy - dy * radarR
                val isSelected = selected?.icao24 == a.icao24

                drawCircle(
                    color = if (isSelected) Color(0xFFFFB700) else Color(0xFF00CC66),
                    radius = if (isSelected) 8f else 5.5f,
                    center = Offset(px, py)
                )

                a.heading?.let { hdg ->
                    val rad = Math.toRadians(hdg - 90)
                    val tl = 10f
                    drawLine(
                        color = if (isSelected) Color(0x99FFB700) else Color(0x6600FF88),
                        start = Offset(px, py),
                        end = Offset(px + (cos(rad) * tl).toFloat(), py + (sin(rad) * tl).toFloat()),
                        strokeWidth = 1.5f
                    )
                }
            }
        }
    }
}

private fun fetchAircraft(lat: Double, lon: Double, radiusDeg: Double): List<Aircraft> {
    val lamin = lat - radiusDeg
    val lamax = lat + radiusDeg
    val lomin = lon - radiusDeg
    val lomax = lon + radiusDeg
    val url = "https://opensky-network.org/api/states/all?lamin=$lamin&lomin=$lomin&lamax=$lamax&lomax=$lomax"

    val conn = URL(url).openConnection() as HttpURLConnection
    // TODO: fill in your own OpenSky Network credentials before building.
    // Get a free account at https://opensky-network.org/
    val creds = "id:password"
    val encoded = Base64.getEncoder().encodeToString(creds.toByteArray())
    conn.setRequestProperty("Authorization", "Basic $encoded")
    conn.connectTimeout = 10000
    conn.readTimeout = 10000

    if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")

    val body = conn.inputStream.bufferedReader().readText()
    val json = org.json.JSONObject(body)
    val states = json.optJSONArray("states") ?: JSONArray()

    val result = mutableListOf<Aircraft>()
    for (i in 0 until states.length()) {
        val s = states.getJSONArray(i)
        val latV = if (s.isNull(6)) null else s.getDouble(6)
        val lonV = if (s.isNull(5)) null else s.getDouble(5)
        val onGround = if (s.isNull(8)) false else s.getBoolean(8)
        if (latV == null || lonV == null || onGround) continue

        result.add(
            Aircraft(
                icao24 = s.optString(0, "??????"),
                callsign = s.optString(1, "").trim().ifEmpty { null },
                lat = latV,
                lon = lonV,
                altitudeM = if (s.isNull(7)) null else s.getDouble(7),
                velocityKt = if (s.isNull(9)) null else (s.getDouble(9) * 1.944).roundToInt(),
                heading = if (s.isNull(10)) null else s.getDouble(10)
            )
        )
    }
    return result
}
