package art.phoenix.platebase

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * PlateBase v1.0 — one job only: read plates, make a database (GPS + plate).
 * Three tabs: Scan / Plates / Test. No map, no radio, no recording,
 * no video, no network, no accounts.
 *
 * Launch safety (Wardrive v1.9 lesson): every launch step catches
 * Throwable, OCR init runs off the UI thread, the app opens
 * immediately even if OCR/DB are still starting, and a crash handler
 * writes last-crash.txt, shown on the Test tab.
 */
class MainActivity : Activity() {

    private val db by lazy { DbHelper(this) }
    private val ocr by lazy { MlKitOcr(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val saver = PlateSaver()

    private lateinit var tabScan: Button
    private lateinit var tabPlates: Button
    private lateinit var tabTest: Button
    private lateinit var scanPanel: View
    private lateinit var platesPanel: View
    private lateinit var testPanel: View

    // Scan
    private lateinit var textureView: TextureView
    private lateinit var scanStatus: TextView
    private lateinit var recBadge: TextView
    private lateinit var lastPlateText: TextView
    private lateinit var startStopButton: Button
    private lateinit var torchButton: Button
    private var camera: CameraController? = null
    private var scanning = false
    private var scanStartMs = 0L
    private var sessionId = -1L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (scanning) {
                val secs = (System.currentTimeMillis() - scanStartMs) / 1000
                recBadge.text = "● REC %02d:%02d".format(secs / 60, secs % 60)
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    // Plates
    private lateinit var searchBox: EditText
    private lateinit var platesList: ListView
    private lateinit var plateDetail: TextView
    private lateinit var deleteButton: Button
    private lateinit var clearButton: Button
    private var plateRows: List<Pair<String, Int>> = emptyList()
    private var selectedPlate: String? = null

    // Test
    private lateinit var mlkitLine: TextView
    private lateinit var gpsLine: TextView
    private lateinit var dbLine: TextView
    private lateinit var selfTestLine: TextView
    private lateinit var crashLine: TextView

    // GPS
    private var locationManager: LocationManager? = null
    @Volatile private var lastFix: GpsFix? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFix = GpsFix(loc.latitude, loc.longitude, loc.accuracy, loc.time)
            updateGpsLine()
        }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    private var pendingExport: Pair<String, String>? = null // (filename, content)

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            try {
                File(filesDir, "last-crash.txt").writeText(
                    "${Date()}\n${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString()}"
                )
            } catch (_: Exception) { }
        }
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (t: Throwable) {
            // Fallback error screen (Wardrive v1.9 lesson): never close with no message.
            val tv = TextView(this)
            tv.text = "PlateBase failed to build its UI:\n${t.javaClass.simpleName}: ${t.message}"
            tv.setPadding(32, 32, 32, 32)
            setContentView(tv)
            return
        }
        requestPermissionsIfNeeded()
        worker.execute {
            try {
                val ok = ocr.init()
                runOnUiThread {
                    mlkitLine.text = if (ok) "ML Kit: READY — ${ocr.diagnostics}"
                        else "ML Kit: FAILED — ${ocr.diagnostics}"
                }
            } catch (t: Throwable) {
                runOnUiThread { mlkitLine.text = "ML Kit: FAILED — ${t.javaClass.simpleName}: ${t.message}" }
            }
        }
        startLocation()
        refreshDbLine()
        showCrashIfAny()
    }

    // ---------------- UI (built in code, no resources) ----------------

    private fun button(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 16f
        setPadding(24, 16, 24, 16)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScan = button("Scan").apply { setOnClickListener { showTab(0) } }
        tabPlates = button("Plates").apply { setOnClickListener { showTab(1) } }
        tabTest = button("Test").apply { setOnClickListener { showTab(2) } }
        for (b in listOf(tabScan, tabPlates, tabTest)) {
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            tabRow.addView(b)
        }
        root.addView(tabRow)

        scanPanel = buildScanPanel()
        platesPanel = buildPlatesPanel()
        testPanel = buildTestPanel()
        root.addView(scanPanel)
        root.addView(platesPanel)
        root.addView(testPanel)
        setContentView(root)
        showTab(0)
    }

    private fun buildScanPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        textureView = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                camera?.pinchZoom(d.scaleFactor)
                return true
            }
        })
        textureView.setOnTouchListener { _, e -> scaleDetector.onTouchEvent(e); true }
        col.addView(textureView)
        scanStatus = TextView(this).apply { text = "Camera not started"; textSize = 15f; setPadding(16, 8, 16, 8) }
        col.addView(scanStatus)
        recBadge = TextView(this).apply {
            text = "● READY"; textSize = 22f; setTextColor(Color.RED)
            gravity = Gravity.CENTER; setPadding(16, 8, 16, 8)
        }
        col.addView(recBadge)
        lastPlateText = TextView(this).apply {
            text = "No plates yet"; textSize = 18f; gravity = Gravity.CENTER; setPadding(16, 8, 16, 8)
        }
        col.addView(lastPlateText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startStopButton = button("Start scanning").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { toggleScanning() }
        }
        torchButton = button("Torch").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val on = camera?.toggleTorch() ?: false
                Toast.makeText(this@MainActivity, if (on) "Torch on" else "Torch off", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(startStopButton); row.addView(torchButton)
        col.addView(row)
        return col
    }

    private fun buildPlatesPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        searchBox = EditText(this).apply {
            hint = "Search plates…"
            setPadding(16, 16, 16, 16)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refreshPlatesList() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        col.addView(searchBox)
        platesList = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ ->
                val plate = plateRows.getOrNull(pos)?.first ?: return@setOnItemClickListener
                selectedPlate = plate
                showPlateDetail(plate)
            }
        }
        col.addView(platesList)
        plateDetail = TextView(this).apply { text = "Tap a plate to see its sightings"; textSize = 14f; setPadding(16, 8, 16, 8) }
        col.addView(plateDetail)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        deleteButton = button("Delete plate").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { deleteSelectedPlate() }
        }
        clearButton = button("Clear all").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { clearAllPlates() }
        }
        row1.addView(deleteButton); row1.addView(clearButton)
        col.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val csvBtn = button("Export CSV").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { exportCsv() }
        }
        val jsonBtn = button("Export JSON").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { exportJson() }
        }
        row2.addView(csvBtn); row2.addView(jsonBtn)
        col.addView(row2)
        return col
    }

    private fun buildTestPanel(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(16, 16, 16, 16)
        }
        mlkitLine = TextView(this).apply { text = "ML Kit: initialising…"; textSize = 15f; setPadding(0, 8, 0, 8) }
        gpsLine = TextView(this).apply { text = "GPS: waiting for fix…"; textSize = 15f; setPadding(0, 8, 0, 8) }
        dbLine = TextView(this).apply { text = "DB: —"; textSize = 15f; setPadding(0, 8, 0, 8) }
        val tessLine = TextView(this).apply {
            text = "Tesseract: not available on this device — ML Kit only"; textSize = 15f; setPadding(0, 8, 0, 8)
        }
        selfTestLine = TextView(this).apply { text = "Self-test: not run yet"; textSize = 15f; setPadding(0, 8, 0, 8) }
        crashLine = TextView(this).apply { text = ""; textSize = 14f; setPadding(0, 8, 0, 8) }
        val runBtn = button("Run self-test (ABC123 / 123ABC)").apply {
            setOnClickListener {
                selfTestLine.text = "Self-test: running…"
                worker.execute {
                    val result = try { ocr.selfTest() } catch (t: Throwable) { "FAILED — ${t.javaClass.simpleName}: ${t.message}" }
                    runOnUiThread { selfTestLine.text = "Self-test: $result" }
                }
            }
        }
        val refreshBtn = button("Refresh status").apply {
            setOnClickListener { updateGpsLine(); refreshDbLine() }
        }
        col.addView(mlkitLine); col.addView(gpsLine); col.addView(dbLine)
        col.addView(tessLine); col.addView(selfTestLine); col.addView(crashLine)
        col.addView(runBtn); col.addView(refreshBtn)
        return col
    }

    private fun showTab(i: Int) {
        scanPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        platesPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
        testPanel.visibility = if (i == 2) View.VISIBLE else View.GONE
        if (i == 1) refreshPlatesList()
        if (i == 2) { updateGpsLine(); refreshDbLine() }
    }

    // ---------------- permissions / GPS ----------------

    private fun requestPermissionsIfNeeded() {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            scanStatus.text = "Camera permission denied — scanning unavailable"
        }
        startLocation()
    }

    @Suppress("MissingPermission")
    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lm = locationManager ?: return
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) lastFix = GpsFix(last.latitude, last.longitude, last.accuracy, last.time)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0f, locationListener, Looper.getMainLooper())
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0f, locationListener, Looper.getMainLooper())
        } catch (t: Throwable) {
            gpsLine.text = "GPS: unavailable — ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun updateGpsLine() {
        val fix = lastFix
        if (fix == null) { gpsLine.text = "GPS: no fix yet"; return }
        val age = System.currentTimeMillis() - fix.atMs
        val stale = GpsFresh.isStale(age, fix.accuracy)
        gpsLine.text = "GPS: %.5f, %.5f  ±%.0fm  age %ds  %s".format(
            fix.lat, fix.lon, fix.accuracy, age / 1000, if (stale) "STALE" else "FRESH"
        )
    }

    private fun showCrashIfAny() {
        try {
            val f = File(filesDir, "last-crash.txt")
            if (f.exists()) crashLine.text = "Last crash:\n${f.readText().take(600)}"
        } catch (_: Exception) { }
    }

    private fun refreshDbLine() {
        worker.execute {
            val (s, p) = try { db.countSightings() to db.countPlates() } catch (t: Throwable) { -1 to -1 }
            runOnUiThread { dbLine.text = "DB: $s sightings, $p distinct plates" }
        }
    }

    // ---------------- scanning ----------------

    private fun toggleScanning() {
        if (scanning) stopScanning() else startScanning()
    }

    private fun startScanning() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ocr.ready) {
            Toast.makeText(this, "ML Kit not ready yet — ${ocr.diagnostics}", Toast.LENGTH_LONG).show()
            return
        }
        scanning = true
        scanStartMs = System.currentTimeMillis()
        sessionId = try { db.startSession(scanStartMs) } catch (_: Exception) { -1L }
        startStopButton.text = "Stop"
        recBadge.text = "● REC 00:00"
        mainHandler.post(timerRunnable)
        camera = CameraController(this, textureView, ocr, object : CameraController.Listener {
            override fun onLivePlates(plates: List<String>) = handlePlates(plates)
            override fun onCameraError(message: String) {
                runOnUiThread { scanStatus.text = "Camera: $message" }
            }
            override fun onInfo(message: String) {
                runOnUiThread { scanStatus.text = message }
            }
            override fun onCameraReady() {
                runOnUiThread { scanStatus.text = "Camera ready — scanning" }
            }
        })
        camera?.open()
    }

    private fun stopScanning() {
        scanning = false
        mainHandler.removeCallbacks(timerRunnable)
        recBadge.text = "● READY"
        startStopButton.text = "Start scanning"
        camera?.close(); camera = null
        if (sessionId >= 0) {
            try { db.endSession(sessionId, System.currentTimeMillis()) } catch (_: Exception) { }
            sessionId = -1L
        }
        refreshDbLine()
    }

    private fun handlePlates(plates: List<String>) {
        val now = System.currentTimeMillis()
        val fix = lastFix
        for (plate in plates) {
            if (!saver.shouldSave(plate, now)) continue
            val age = fix?.let { now - it.atMs }
            val stale = GpsFresh.isStale(age, fix?.accuracy)
            val sighting = Sighting(
                plate = plate, seenAt = now,
                lat = fix?.lat, lon = fix?.lon, accuracy = fix?.accuracy,
                gpsAgeMs = age, gpsStale = stale
            )
            worker.execute {
                try { db.addSighting(sighting) } catch (_: Exception) { }
            }
            runOnUiThread {
                lastPlateText.text = PlateLogic.display(plate) +
                    (if (fix != null) "  %.5f, %.5f%s".format(fix.lat, fix.lon, if (stale) " (GPS STALE)" else "") else "  (no GPS fix)")
                refreshDbLine()
                if (platesPanel.visibility == View.VISIBLE) refreshPlatesList()
            }
        }
    }

    // ---------------- plates list ----------------

    private fun refreshPlatesList() {
        val q = searchBox.text.toString().trim()
        worker.execute {
            val rows = try {
                if (q.isEmpty()) db.listPlates() else db.searchPlates(q)
            } catch (t: Throwable) { emptyList() }
            runOnUiThread {
                plateRows = rows
                platesList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                    rows.map { "${PlateLogic.display(it.first)}  (${it.second})" })
            }
        }
    }

    private fun showPlateDetail(plate: String) {
        worker.execute {
            val rows = try { db.sightingsForPlate(plate) } catch (t: Throwable) { emptyList() }
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val text = StringBuilder("$plate — ${rows.size} sighting(s)\n")
            for (s in rows.take(20)) {
                text.append(fmt.format(Date(s.seenAt))).append("  ")
                if (s.lat != null && s.lon != null) text.append("%.5f, %.5f".format(s.lat, s.lon))
                else text.append("no GPS")
                if (s.gpsStale) text.append(" STALE")
                text.append('\n')
            }
            runOnUiThread { plateDetail.text = text.toString() }
        }
    }

    private fun deleteSelectedPlate() {
        val plate = selectedPlate ?: run {
            Toast.makeText(this, "Tap a plate first", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete $plate?")
            .setMessage("Removes all sightings of this plate.")
            .setPositiveButton("Delete") { _, _ ->
                worker.execute { try { db.deletePlate(plate) } catch (_: Exception) { } }
                selectedPlate = null; plateDetail.text = "Tap a plate to see its sightings"
                refreshPlatesList(); refreshDbLine()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllPlates() {
        AlertDialog.Builder(this)
            .setTitle("Clear all plates?")
            .setMessage("Deletes every sighting in the database. This cannot be undone.")
            .setPositiveButton("Clear all") { _, _ ->
                worker.execute { try { db.clearAll() } catch (_: Exception) { } }
                selectedPlate = null; plateDetail.text = "Tap a plate to see its sightings"
                refreshPlatesList(); refreshDbLine()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- export (SAF, no provider needed) ----------------

    private fun exportCsv() = startExport("csv")
    private fun exportJson() = startExport("json")

    private fun startExport(kind: String) {
        worker.execute {
            val rows = try { db.listSightings(10000) } catch (t: Throwable) { emptyList() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val (name, content) = if (kind == "csv") "platebase-$stamp.csv" to Export.toCsv(rows)
                else "platebase-$stamp.json" to Export.toJson(rows)
            pendingExport = name to content
            runOnUiThread {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (kind == "csv") "text/csv" else "application/json"
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                startActivityForResult(intent, if (kind == "csv") 10 else 11)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (result != RESULT_OK || data?.data == null) return
        val (_, content) = pendingExport ?: return
        try {
            contentResolver.openOutputStream(data.data!!)?.use { it.write(content.toByteArray()) }
            Toast.makeText(this, "Exported ${pendingExport?.first}", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
        pendingExport = null
    }

    override fun onPause() {
        super.onPause()
        if (scanning) stopScanning()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { camera?.close() } catch (_: Exception) { }
        try { ocr.close() } catch (_: Exception) { }
        worker.shutdownNow()
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) { }
    }
}
