package com.duodeck.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.duodeck.player.skin.SkinRepository
import com.duodeck.player.ui.DuoDeckApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    private val fold = mutableStateOf<FoldingFeature?>(null)
    private val hasPermission = mutableStateOf(false)

    // Laptop pose: some Duos keep the screen in portrait when folded into laptop mode,
    // so we watch the tilt sensor and turn the UI ourselves.
    private val manualRotation = mutableStateOf(0)
    private var deviceAngle = 0
    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                deviceAngle = when (angle) {
                    in 60..120 -> 90
                    in 240..300 -> 270
                    in 0..30, in 330..359 -> 0
                    in 150..210 -> 180
                    else -> return // in between: keep the last pose
                }
                updateManualRotation()
            }
        }
    }

    // Hinge angle (Surface Duo reports it directly). Folded = laptop/tent, flat = side by side.
    // Uses a little "dead zone" so it doesn't flicker right at the edge.
    private var hingeAngle = -1f
    private var hingeFolded = false
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager }
    private val hingeSensor by lazy {
        if (Build.VERSION.SDK_INT >= 30) sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE) else null
    }
    private val hingeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            hingeAngle = e.values[0]
            val before = hingeFolded
            if (hingeAngle < 150f) hingeFolded = true
            else if (hingeAngle > 165f) hingeFolded = false
            if (before != hingeFolded) updateManualRotation()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private val prefs by lazy { getSharedPreferences("posture", MODE_PRIVATE) }
    /** Which side was up the last time we were in laptop mode (90 = left screen up). */
    private var lastLaptopSide: Int
        get() = prefs.getInt("laptopSide2", 90)
        set(v) { prefs.edit().putInt("laptopSide2", v).apply() }

    private fun isFolded(): Boolean {
        if (hingeSensor != null && hingeAngle >= 0f) return hingeFolded
        return fold.value?.state == FoldingFeature.State.HALF_OPENED
    }

    // Gravity: tells "book" pose (held upright, long edge vertical) from laptop pose.
    // In book pose gravity runs down the long edge (the Y axis) of both screens.
    // In laptop pose it doesn't: one half is upright sideways and the other lies flat.
    private var gravitySensor: android.hardware.Sensor? = null
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var uprightBook = true
    private val gravityListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val a = if (e.sensor.type == android.hardware.Sensor.TYPE_GRAVITY) 1f else 0.15f
            gx += (e.values[0] - gx) * a; gy += (e.values[1] - gy) * a; gz += (e.values[2] - gz) * a
            val g = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
            if (g < 1f) return
            val share = kotlin.math.abs(gy) / g
            val before = uprightBook
            if (share > 0.70f) uprightBook = true
            else if (share < 0.45f) uprightBook = false
            if (before != uprightBook) updateManualRotation()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun updateManualRotation() {
        val f = fold.value
        val spannedPortrait = f != null && f.isSeparating && f.orientation == FoldingFeature.Orientation.VERTICAL
        @Suppress("DEPRECATION")
        val displayRotation = windowManager.defaultDisplay.rotation
        val laptopPose = isFolded() && !uprightBook
        if (!LaptopView.auto.value || !spannedPortrait || displayRotation != android.view.Surface.ROTATION_0 || !laptopPose) {
            // lying flat, held up like a book, or not spanned: normal side-by-side, no turning
            manualRotation.value = 0
            return
        }
        // Folded into laptop mode: the tilt sensor can't tell which way up reliably,
        // so we use the saved choice; "Flip laptop view" in the menu changes it.
        manualRotation.value = if (lastLaptopSide == 270) 90 else -90
    }

    // ---- keep the screen on while music plays (setting in Performance & screen)
    private var pluggedIn = false
    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            pluggedIn = when (i.action) {
                android.content.Intent.ACTION_POWER_CONNECTED -> true
                android.content.Intent.ACTION_POWER_DISCONNECTED -> false
                else -> pluggedIn
            }
            updateKeepScreenOn()
        }
    }

    private fun readPluggedIn(): Boolean {
        val b = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return (b?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun updateKeepScreenOn() {
        val playing = vm.state.value.isPlaying
        val keep = when (PerfSettings.values.value.keepAwake) {
            2 -> playing
            1 -> playing && pluggedIn
            else -> false
        }
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keep) window.addFlags(flag) else window.clearFlags(flag)
    }

    override fun onStart() {
        super.onStart()
        PerfSettings.setUiVisible(true)
        pluggedIn = readPluggedIn()
        registerReceiver(powerReceiver, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
        })
        updateKeepScreenOn()
    }

    override fun onStop() {
        PerfSettings.setUiVisible(false)
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Came back from Settings with access turned on: load the music now.
        if (!hasPermission.value && checkGranted()) {
            hasPermission.value = true
            vm.loadLibrary()
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        hingeSensor?.let { sensorManager.registerListener(hingeListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        gravitySensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        gravitySensor?.let { sensorManager.registerListener(gravityListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        updateManualRotation()
    }

    override fun onPause() {
        orientationListener.disable()
        sensorManager.unregisterListener(hingeListener)
        sensorManager.unregisterListener(gravityListener)
        super.onPause()
    }

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    // After two "Don't allow" answers Android stops showing the question, so we send people to Settings instead.
    private var askedBefore = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[audioPermission] == true || checkGranted()
        hasPermission.value = granted
        askedBefore = true
        if (granted) vm.loadLibrary()
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }

    private fun checkGranted() =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    fun requestPermissions() {
        if (askedBefore && !checkGranted() && !shouldShowRequestPermissionRationale(audioPermission)) {
            android.widget.Toast.makeText(this, "Turn on Music and audio for Duo-Deck, then come back", android.widget.Toast.LENGTH_LONG).show()
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
            return
        }
        val perms = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    fun pickFiles() = pickAudio.launch(arrayOf("audio/*"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SkinRepository.init(this)
        PerfSettings.init(this)
        com.duodeck.player.ui.VisStyles.init(this)
        com.duodeck.player.ui.Setup.init(this)
        com.duodeck.player.data.MusicStats.init(this)
        com.duodeck.player.data.RadioStore.init(this)

        // keep the home-screen widget in the current skin's colours
        lifecycleScope.launch {
            SkinRepository.current.collect { com.duodeck.player.widget.WidgetUpdater.saveSkin(this@MainActivity, it) }
        }

        // Surface Duo hinge / foldable posture
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        fold.value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                        updateManualRotation()
                    }
            }
        }

        // re-check "keep screen on" whenever playback starts/stops or the setting changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.state, PerfSettings.values) { s, p -> s.isPlaying to p.keepAwake }
                    .collect { updateKeepScreenOn() }
            }
        }

        // "Laptop auto-turn" on/off from the menu
        LaptopView.init(this)
        lifecycleScope.launch { LaptopView.auto.collect { updateManualRotation() } }

        // "Flip laptop view" from the menu
        lifecycleScope.launch {
            LaptopView.flips.collect { n ->
                if (n > 0) {
                    lastLaptopSide = if (lastLaptopSide == 270) 90 else 270
                    LaptopView.flips.value = 0
                    updateManualRotation()
                }
            }
        }

        hasPermission.value = checkGranted()
        if (hasPermission.value) vm.loadLibrary() else if (savedInstanceState == null) requestPermissions()

        setContent {
            DuoDeckApp(
                vm = vm,
                fold = fold.value,
                rotation = manualRotation.value,
                hasPermission = hasPermission.value,
                onRequestPermission = { requestPermissions() },
                onPickFiles = { pickFiles() },
            )
        }
    }
}
