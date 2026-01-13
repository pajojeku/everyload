package com.elteam.everyload

import android.content.Intent
import android.util.Log
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import com.elteam.everyload.data.DomainsManager
import com.elteam.everyload.model.JobEntry
import com.elteam.everyload.ui.JobAdapter
import com.elteam.everyload.data.JobsManager
import java.io.File
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import org.json.JSONArray
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import android.widget.EditText
import android.widget.Button
import android.content.SharedPreferences
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

// Service imports
import com.elteam.everyload.service.DownloadService
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

// YTDLP imports
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.ffmpeg.FFmpeg

// Start.io imports
import com.startapp.sdk.adsbase.StartAppSDK

// Sensor imports for shake detection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), DownloadService.DownloadServiceCallbacks, JobsManager.JobChangeListener, SensorEventListener {

    private lateinit var jobsManager: JobsManager
    private lateinit var adapter: JobAdapter
    private lateinit var settings: SharedPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var domainsManager: DomainsManager

    // Shake detection
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0
    private var shakeCount: Int = 0
    private val SHAKE_THRESHOLD = 20f // Higher threshold - more force needed
    private val SHAKE_COUNT_REQUIRED = 3 // Need 3 shakes
    private val SHAKE_TIME_WINDOW = 1500L // Within 1.5 seconds
    private val SHAKE_RESET_TIME = 3000L // Reset if no shake for 3 seconds
    
    // Concurrent download management
    private var activeDownloadCount = 0
    private val downloadQueue = mutableListOf<JobEntry>()
    
    // Service binding
    private var downloadService: DownloadService? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DownloadService.LocalBinder
            downloadService = binder.getService()
            downloadService?.setCallbacks(this@MainActivity)
            serviceBound = true
        }
        
        override fun onServiceDisconnected(arg0: ComponentName) {
            downloadService = null
            serviceBound = false
        }
    }
    
    companion object {
        private const val PREFS_NAME = "everyload_prefs"
        private const val KEY_JOBS = "jobs_json"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
        
        // Settings keys
        private const val KEY_FORMAT = "download_format" // mp3, mp4, best
        private const val KEY_QUALITY = "download_quality" // 720p, 1080p, best
        private const val KEY_MAX_ATTEMPTS = "max_attempts"
        private const val KEY_ALLOW_PLAYLISTS = "allow_playlists"
        private const val KEY_MAX_CONCURRENT_DOWNLOADS = "max_concurrent_downloads"
    }
    
    private fun getDownloadFormat(): String = settings.getString(KEY_FORMAT, "mp4") ?: "mp4"
    private fun getDownloadQuality(): String = settings.getString(KEY_QUALITY, "720p") ?: "720p"
    private fun getMaxAttempts(): Int = settings.getInt(KEY_MAX_ATTEMPTS, 3)
    private fun getAllowPlaylists(): Boolean = settings.getBoolean(KEY_ALLOW_PLAYLISTS, false)
    private fun getMaxConcurrentDownloads(): Int = settings.getInt(KEY_MAX_CONCURRENT_DOWNLOADS, 3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        // Store the original intent before it can be modified
        val originalIntent = intent
        
        Log.d("MainActivity", "onCreate called")
        Log.d("MainActivity", "Intent: ${originalIntent}")
        Log.d("MainActivity", "Intent action: ${originalIntent?.action}")
        Log.d("MainActivity", "Intent type: ${originalIntent?.type}")
        Log.d("MainActivity", "Intent data: ${originalIntent?.data}")
        
        // Set up toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        // Bind to download service
        val intent = Intent(this, DownloadService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        // Initialize shake detection
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = getSharedPreferences("app_settings", MODE_PRIVATE)
        
        // Initialize Start.io SDK
        StartAppSDK.initParams(applicationContext, "211883801")
            .setReturnAdsEnabled(false)
            .setCallback { Log.d("MainActivity", "Start.io SDK ready") }
            .init()
        
        // Initialize JobsManager
        jobsManager = JobsManager(this)
        jobsManager.addChangeListener(this)
        
        // Initialize YoutubeDL with enhanced error handling
        try {
            // Ensure app data directory exists
            val appDataDir = getExternalFilesDir(null) ?: filesDir
            if (!appDataDir.exists() && !appDataDir.mkdirs()) {
                Log.e("MainActivity", "Failed to create app data directory")
            }
            
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Log.d("MainActivity", "YoutubeDL and FFmpeg initialized successfully")
        } catch (e: YoutubeDLException) {
            Log.e("MainActivity", "Failed to initialize YoutubeDL", e)
            e.printStackTrace()
            showErrorDialog("Failed to initialize YoutubeDL: ${e.message}")
        }

        recyclerView = findViewById(R.id.jobsRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = JobAdapter { job -> handleJobClick(job) }
        recyclerView.adapter = adapter
        adapter.submitList(jobsManager.getAllJobs())
        
        // Setup swipe-to-delete
        setupSwipeToDelete(recyclerView)

        val urlInput: TextInputEditText = findViewById(R.id.urlInput)
        val downloadButton: com.google.android.material.button.MaterialButton = findViewById(R.id.downloadButton)

        // Initialize DomainsManager and single search input
        domainsManager = DomainsManager(this)
        val searchInput: TextInputEditText = findViewById(R.id.searchInput)

        // Simple debounce implementation (dynamic search)
        var searchRunnable: Runnable? = null
        val handler = android.os.Handler(mainLooper)
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    // dynamic filter; user can type domain (domain.com) or extension (.mp4) or title
                    applySearchFilter(s?.toString())
                }
                handler.postDelayed(searchRunnable!!, 250)
            }
        })

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotBlank()) {
                // If URL contains a host, ensure it's saved in domains store
                try {
                    val host = java.net.URI(url).host?.lowercase()?.removePrefix("www.")
                    host?.let { h ->
                        if (domainsManager.findByDomain(h) == null) {
                            val added = domainsManager.addDomain(name = h, domainsList = listOf(h))
                            // Snackbar with undo
                            com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.main), "Zapisano nowy domain: ${h}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .setAction("Cofnij") { domainsManager.removeDomainById(added.id) }
                                .show()
                        }
                    }
                } catch (_: Exception) {}
                startYtdlpDownload(url)
                urlInput.text?.clear()
            } else {
                Toast.makeText(this, getString(R.string.toast_paste_link), Toast.LENGTH_SHORT).show()
            }
        }
        
        // Add long click for test sharing functionality
        downloadButton.setOnLongClickListener {
            // Test sharing functionality with a sample YouTube URL
            val testIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            }
            Log.d("MainActivity", "Testing sharing with simulated intent")
            handleSendIntent(testIntent)
            true
        }
        
        // Process sharing intent if it's actually a sharing intent
        Log.d("MainActivity", "Checking intent for sharing: action=${originalIntent?.action}")
        if (originalIntent != null) {
            when (originalIntent.action) {
                Intent.ACTION_SEND -> {
                    Log.d("MainActivity", "Found SEND intent, processing...")
                    handleSendIntent(originalIntent)
                }
                Intent.ACTION_VIEW -> {
                    Log.d("MainActivity", "Found VIEW intent, processing...")
                    handleSendIntent(originalIntent)
                }
                else -> {
                    Log.d("MainActivity", "No sharing intent found, action: ${originalIntent.action}")
                }
            }
        } else {
            Log.d("MainActivity", "Intent is null")
        }
        
        checkPermissions()
        requestNotificationPermission()
    }
    
    private fun applySearchFilter(query: String?) {
         // Extract extensions (e.g. .mp4) from query and clean query text
         var q = query?.trim()
         val exts = mutableListOf<String>()
         if (!q.isNullOrBlank()) {
             val regex = Regex("\\.([A-Za-z0-9]{1,10})")
             regex.findAll(q).forEach { match -> exts.add(match.groupValues[1].lowercase()) }
             // remove extracted extensions from query to avoid affecting title search
             q = q.replace(regex, " ").trim().takeIf { it.isNotEmpty() }
         }

         // If user typed a domain pattern (contains '.') try to use it as domain filter
         val domainFilter = q?.takeIf { it.contains('.') }?.lowercase()?.removePrefix("www.")
         val portalDomains = domainFilter?.let { listOf(it) }

         val filtered = jobsManager.filterJobs(query = q, extensions = if (exts.isEmpty()) null else exts, domains = portalDomains)
         runOnUiThread {
             adapter.submitList(filtered)
             // show/hide empty state
             val emptyView = findViewById<View>(R.id.emptyStateView)
             emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
         }
     }

    override fun onResume() {
        super.onResume()
        // Register shake detection listener
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Unregister shake detection listener to save battery
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        if (serviceBound) {
            downloadService?.setCallbacks(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_clear_downloads -> {
                clearAllJobs()
                true
            }
            R.id.action_update_ytdlp -> {
                updateYtDlp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the intent and handle the new share
        setIntent(intent)
        handleSendIntent(intent)
    }

    private fun handleSendIntent(intent: Intent) {
        // Null check for intent
        if (intent.action == null) {
            Log.d("MainActivity", "Intent action is null, skipping processing")
            return
        }
        
        Log.d("MainActivity", "handleSendIntent called with action: ${intent.action}, type: ${intent.type}")
        Log.d("MainActivity", "Intent extras: ${intent.extras?.keySet()?.joinToString()}")
        
        // Debug: Show what we received
        val debugInfo = StringBuilder()
        debugInfo.append("Action: ${intent.action}\n")
        debugInfo.append("Type: ${intent.type}\n")
        debugInfo.append("Data: ${intent.data}\n")
        intent.extras?.let { extras ->
            for (key in extras.keySet()) {
                debugInfo.append("$key: ${extras.get(key)}\n")
            }
        }
        Log.d("MainActivity", "Full intent info: $debugInfo")
        
        when (intent.action) {
            Intent.ACTION_SEND -> {
                Log.d("MainActivity", "Processing SEND intent with type: ${intent.type}")
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                Log.d("MainActivity", "Shared text: $sharedText")
                Log.d("MainActivity", "Shared subject: $sharedSubject")
                
                // Try both EXTRA_TEXT and EXTRA_SUBJECT for URL
                val url = sharedText ?: sharedSubject
                
                if (url != null && (url.contains("youtube.com") || url.contains("youtu.be") || url.startsWith("http"))) {
                    Log.d("MainActivity", "Valid URL found: $url")
                    
                    // Show confirmation dialog
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_link_shared))
                        .setMessage(getString(R.string.dialog_download_question, url))
                        .setPositiveButton(getString(R.string.btn_download)) { _, _ ->
                            processSharedUrl(url)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                } else {
                    Log.d("MainActivity", "No valid URL found in SEND intent")
                    // Show error if we received something but it's not a valid URL
                    if (sharedText != null || sharedSubject != null) {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.dialog_unsupported_link))
                            .setMessage(getString(R.string.dialog_link_not_valid, sharedText ?: sharedSubject))
                            .setPositiveButton(getString(R.string.btn_ok), null)
                            .show()
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data
                Log.d("MainActivity", "Processing VIEW intent with data: $data")
                if (data != null) {
                    val url = data.toString()
                    Log.d("MainActivity", "Received URL via VIEW: $url")
                    processSharedUrl(url)
                } else {
                    Log.d("MainActivity", "No data found in VIEW intent")
                }
            }
            else -> {
                Log.d("MainActivity", "Unhandled intent action: ${intent.action}")
            }
        }
    }
    
    private fun processSharedUrl(url: String) {
        // Show the shared URL in the input field for user visibility
        val urlInput: EditText = findViewById(R.id.urlInput)
        urlInput.setText(url)
        
        // Give visual feedback that sharing was received
        Toast.makeText(this, getString(R.string.toast_url_received), Toast.LENGTH_SHORT).show()

        // Check if it's a playlist and user allows playlists
        val isPlaylist = url.contains("list=") || 
                       url.contains("playlist") || 
                       url.contains("/playlist?") ||
                       url.contains("youtube.com/c/") ||
                       url.contains("youtube.com/@")

        if (isPlaylist && !getAllowPlaylists()) {
            // Ask user if they want to download playlist
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_playlist_detected))
                .setMessage(getString(R.string.dialog_playlist_question))
                .setPositiveButton(getString(R.string.btn_download_playlist)) { _, _ ->
                    startYtdlpDownload(url, forcePlaylist = true)
                    urlInput.text?.clear()
                }
                .setNegativeButton(getString(R.string.btn_this_video_only)) { _, _ ->
                    startYtdlpDownload(url, forcePlaylist = false)
                    urlInput.text?.clear()
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                    // Keep URL in field for manual download later
                }
                .show()
        } else {
            // Auto-start download and show confirmation
            startYtdlpDownload(url)
            urlInput.text?.clear()
            Toast.makeText(this, getString(R.string.toast_download_started), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorDialog(message: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.dialog_error))
        builder.setMessage(message)
        builder.setPositiveButton(getString(R.string.btn_ok)) { dialog, _ -> dialog.dismiss() }
        builder.setCancelable(true)
        builder.show()
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - use granular media permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12 - use legacy storage permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // WRITE_EXTERNAL_STORAGE is only needed for Android 10 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
        
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val deniedPermissions = permissions.filterIndexed { index, _ -> 
                    grantResults[index] != PackageManager.PERMISSION_GRANTED 
                }
                if (deniedPermissions.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_permissions_denied), Toast.LENGTH_LONG).show()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.toast_notification_denied), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun requestNotificationPermission() {
        // POST_NOTIFICATIONS permission is only available from Android 13 (API 33)
        // Android 10 (API 29) doesn't need this permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
            }
        }
        // For Android 12 and below (including Android 10), notifications work without runtime permission
    }

    // Start download directly with ytdlp library
    private fun startYtdlpDownload(url: String, forcePlaylist: Boolean? = null) {
        // Validate URL first
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank() || (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://"))) {
            Toast.makeText(this, getString(R.string.toast_invalid_url, trimmedUrl), Toast.LENGTH_LONG).show()
            return
        }
        
        val allowPlaylists = forcePlaylist ?: getAllowPlaylists()
        val isPlaylist = trimmedUrl.contains("list=") || 
                        trimmedUrl.contains("playlist") || 
                        trimmedUrl.contains("/playlist?") ||
                        trimmedUrl.contains("youtube.com/c/") ||
                        trimmedUrl.contains("youtube.com/@")
        
        if (isPlaylist && allowPlaylists) {
            // Extract playlist info first to create separate jobs
            extractPlaylistInfo(trimmedUrl)
        } else {
            // Single video download
            val jobId = jobsManager.generateJobId("ytdlp")
            val entry = JobEntry(jobId = jobId, url = trimmedUrl, status = "queued")
            jobsManager.addJob(entry)
            
            runOnUiThread {
                Toast.makeText(this, getString(R.string.toast_added_to_queue), Toast.LENGTH_SHORT).show()
            }
            
            // Add to download queue for concurrent processing
            synchronized(downloadQueue) {
                downloadQueue.add(entry)
            }
            
            // Try to start download if we're under the concurrent limit
            processDownloadQueue()
        }
    }
    
    // Extract playlist information and create separate jobs for each video
    private fun extractPlaylistInfo(playlistUrl: String) {
        val extractJobId = jobsManager.generateJobId("extract")
        val extractEntry = JobEntry(
            jobId = extractJobId,
            url = playlistUrl,
            status = "extracting",
            info = getString(R.string.info_extracting_playlist)
        )
        jobsManager.addJob(extractEntry)
        
        Thread {
            try {
                // Create download directory with comprehensive null checks
                val externalDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) 
                    ?: getExternalFilesDir(null) 
                    ?: filesDir
                
                if (externalDir == null) {
                    runOnUiThread {
                        jobsManager.updateJob(extractEntry.copy(
                            status = "error",
                            info = getString(R.string.error_cannot_access_directory)
                        ))
                        Toast.makeText(this@MainActivity, getString(R.string.error_cannot_access_directory), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                
                val youtubeDLDir = File(externalDir, "Everyload")
                Log.d("MainActivity", "Playlist extraction directory: ${youtubeDLDir.absolutePath}")
                
                if (!youtubeDLDir.exists() && !youtubeDLDir.mkdirs()) {
                    runOnUiThread {
                        jobsManager.updateJob(extractEntry.copy(
                            status = "error",
                            info = getString(R.string.error_cannot_create_directory)
                        ))
                        Toast.makeText(this@MainActivity, getString(R.string.error_cannot_create_directory), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                
                // Validate directory access
                if (!youtubeDLDir.isDirectory() || !youtubeDLDir.canWrite()) {
                    runOnUiThread {
                        jobsManager.updateJob(extractEntry.copy(
                            status = "error",
                            info = getString(R.string.error_directory_not_writable)
                        ))
                        Toast.makeText(this@MainActivity, getString(R.string.error_directory_not_writable), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                
                // Create request to extract playlist info only
                val request = YoutubeDLRequest(playlistUrl).apply {
                    addOption("--flat-playlist")
                    addOption("--print", "%(title)s|||%(id)s|||%(url)s")
                    addOption("--no-download")
                }
                
                runOnUiThread {
                    jobsManager.updateJob(extractEntry.copy(
                        info = getString(R.string.info_connecting_playlist)
                    ))
                }
                
                val youtubeDLInstance = YoutubeDL.getInstance()
                if (youtubeDLInstance == null) {
                    Log.e("MainActivity", "YoutubeDL instance is null for playlist extraction")
                    runOnUiThread {
                        jobsManager.updateJob(extractEntry.copy(
                            status = "error",
                            info = getString(R.string.error_ytdlp_unavailable)
                        ))
                        Toast.makeText(this@MainActivity, getString(R.string.error_ytdlp_unavailable), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                
                Log.d("MainActivity", "Starting playlist extraction for URL: $playlistUrl")
                val output = StringBuilder()
                
                youtubeDLInstance?.execute(request) { progress, eta, line ->
                    if (line.contains("|||")) {
                        output.appendLine(line)
                    }
                    runOnUiThread {
                        val progressPercent = progress.toInt().coerceAtLeast(0)
                        jobsManager.updateJob(extractEntry.copy(
                            info = getString(R.string.info_analyzing_playlist, progressPercent)
                        ))
                    }
                }
                
                // Parse the output to create individual jobs
                val lines = output.toString().split("\n").filter { it.contains("|||") }
                
                runOnUiThread {
                    // Remove the extraction job
                    jobsManager.removeJob(extractEntry.jobId)
                    
                    if (lines.isEmpty()) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_no_videos_in_playlist), Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    
                    val message = getString(R.string.toast_found_videos, lines.size)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    
                    // Create separate jobs for each video
                    lines.forEachIndexed { index, line ->
                        val parts = line.split("|||")
                        if (parts.size >= 3) {
                            val title = parts[0].trim()
                            val videoId = parts[1].trim()
                            val videoUrl = parts[2].trim()
                            
                            val jobId = jobsManager.generateJobId("ytdlp")
                            val videoEntry = JobEntry(
                                jobId = jobId, 
                                url = videoUrl.ifEmpty { "https://www.youtube.com/watch?v=$videoId" },
                                status = "queued",
                                title = title.ifEmpty { "Video ${index + 1}" },
                                info = getString(R.string.info_queued_position, index + 1, lines.size)
                            )
                            
                            jobsManager.addJob(videoEntry)
                            
                            // Add to download queue for concurrent processing
                            synchronized(downloadQueue) {
                                downloadQueue.add(videoEntry)
                            }
                            
                            // Try to start download if we're under the concurrent limit
                            processDownloadQueue()
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to extract playlist info for URL: $playlistUrl")
                Log.e("MainActivity", "Error type: ${e.javaClass.simpleName}")
                Log.e("MainActivity", "Error message: ${e.message}")
                Log.e("MainActivity", "Stack trace:", e)
                runOnUiThread {
                    jobsManager.updateJob(extractEntry.copy(
                        status = "error",
                        info = getString(R.string.error_playlist_extraction, e.message ?: "")
                    ))
                    Toast.makeText(this@MainActivity, getString(R.string.error_playlist_analysis, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    
    // Perform actual download for a single video entry using background service
    private fun performSingleDownload(entry: JobEntry) {
        if (downloadService != null) {
            // Use background service
            val intent = Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START_DOWNLOAD
                putExtra(DownloadService.EXTRA_JOB_ENTRY, entry)
            }
            
            // For Android 14+, we need to use startForegroundService
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start download service: ${e.message}")
                // Fallback to legacy method
                performSingleDownloadLegacy(entry)
            }
        } else {
            // Fallback to old method if service not available
            performSingleDownloadLegacy(entry)
        }
    }
    
    // Legacy download method (kept as fallback)
    private fun performSingleDownloadLegacy(entry: JobEntry) {
        // Download directory - use app external files directory for better compatibility
        val externalDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) 
            ?: getExternalFilesDir(null) 
            ?: filesDir
        val youtubeDLDir = File(externalDir, "Everyload")
        if (!youtubeDLDir.exists()) {
            if (!youtubeDLDir.mkdirs()) {
                runOnUiThread {
                    jobsManager.updateJob(entry.copy(
                        status = "error",
                        info = getString(R.string.error_cannot_create_download_dir)
                    ))
                    Toast.makeText(this@MainActivity, getString(R.string.error_cannot_create_directory), Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        
        // Validate download directory
        if (!youtubeDLDir.exists() || !youtubeDLDir.isDirectory()) {
            runOnUiThread {
                jobsManager.updateJob(entry.copy(
                    status = "error",
                    info = getString(R.string.error_directory_not_accessible)
                ))
                Toast.makeText(this@MainActivity, getString(R.string.error_cannot_access_download_dir), Toast.LENGTH_LONG).show()
            }
            return
        }
        
        // Create unique filename with job ID to prevent collisions
        val uniqueId = entry.jobId.takeLast(8)
        val safeTitle = entry.title?.let { title ->
            val cleanTitle = title
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("[\\s]+"), "_")
                .replace(Regex("[_]+"), "_")
                .replace(Regex("^_|_$"), "")
                .take(80)
            "${cleanTitle}_${uniqueId}"
        } ?: "video_${uniqueId}"
        
        // Download request with user settings
        val request = YoutubeDLRequest(entry.url).apply {
            addOption("-o", "${youtubeDLDir.absolutePath}/${safeTitle}.%(ext)s")
            
            // Always download single video (no playlist)
            addOption("--no-playlist")
            
            // Check if this is a Facebook URL
            val isFacebook = entry.url.contains("facebook.com") || 
                             entry.url.contains("fb.watch") || 
                             entry.url.contains("fb.com")
            
            // Format selection based on user preference and site
            if (isFacebook) {
                // Facebook-specific handling to fix black video issue
                when (getDownloadFormat()) {
                    "mp3" -> {
                        addOption("--format", "bestaudio/best")
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    }
                    else -> {
                        // Use dash_sd_src format which has both video and audio merged
                        addOption("--format", "dash_sd_src_no_ratelimit/dash_sd_src/best")
                        addOption("--http-chunk-size", "10M")
                    }
                }
            } else {
                // Standard format selection for other sites
                when (getDownloadFormat()) {
                    "mp3" -> {
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    }
                    "mp4" -> {
                        val quality = getDownloadQuality()
                        if (quality == "best") {
                            addOption("--format", "best[ext=mp4]")
                        } else {
                            addOption("--format", "mp4[height<=${quality.replace("p", "")}]/best[ext=mp4]")
                        }
                    }
                    else -> { // "best"
                        val quality = getDownloadQuality()
                        if (quality == "best") {
                            addOption("--format", "best")
                        } else {
                            addOption("--format", "best[height<=${quality.replace("p", "")}]")
                        }
                    }
                }
            }
            
            // Add options to handle YouTube's anti-bot measures
            addOption("--extractor-retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--skip-unavailable-fragments")
        }
        
        // Update status to downloading
        runOnUiThread {
            jobsManager.updateJobStatus(entry.jobId, "downloading")
        }
        
        // Execute download in background thread with automatic retries
        Thread {
            val maxAttempts = getMaxAttempts()
            var currentAttempt = 1
            
            while (currentAttempt <= maxAttempts) {
                try {
                    Log.d("YouTubeDL", "Starting download for URL: ${entry.url} (Attempt $currentAttempt/$maxAttempts)")
                    Log.d("YouTubeDL", "Download directory: ${youtubeDLDir.absolutePath}")
                    
                    // Update status with attempt info
                    runOnUiThread {
                        jobsManager.updateJobStatus(entry.jobId, "downloading", 
                            getString(R.string.info_attempt_connecting, currentAttempt, maxAttempts))
                    }
                    
                    val youtubeDLInstance = YoutubeDL.getInstance()
                    
                    youtubeDLInstance.execute(request) { progress, eta, line ->
                        Log.d("YouTubeDL", "$progress% (ETA $eta seconds) - $line")
                        runOnUiThread {
                            val progressPercent = progress.toInt().coerceAtLeast(0)
                            jobsManager.updateJobStatus(entry.jobId, "downloading",
                                getString(R.string.info_attempt_downloading, currentAttempt, maxAttempts, progressPercent))
                        }
                    }
                
                    // Download completed successfully
                    runOnUiThread {
                        // Find the downloaded file by matching our unique filename
                        Log.d("YouTubeDL", "Looking for file with prefix: $safeTitle")
                        
                        // First try to find by exact name match
                        var downloadedFile = youtubeDLDir.listFiles()?.firstOrNull { file ->
                            file.isFile && file.nameWithoutExtension.equals(safeTitle, ignoreCase = true)
                        }
                        
                        // Fallback: find by unique ID
                        if (downloadedFile == null) {
                            downloadedFile = youtubeDLDir.listFiles()?.firstOrNull { file ->
                                file.isFile && file.name.contains(uniqueId)
                            }
                        }
                        
                        // Last resort: recently modified file (within 30 seconds)
                        if (downloadedFile == null) {
                            downloadedFile = youtubeDLDir.listFiles()?.filter { 
                                it.isFile && it.lastModified() > System.currentTimeMillis() - 30000
                            }?.maxByOrNull { it.lastModified() }
                        }
                        
                        val currentJob = jobsManager.getJob(entry.jobId)
                        if (currentJob == null) {
                            Log.w("YouTubeDL", "Job ${entry.jobId} no longer exists")
                            return@runOnUiThread
                        }
                        
                        if (downloadedFile != null && downloadedFile.exists()) {
                            try {
                                // Use FileProvider for the downloaded file
                                val authority = "${applicationContext.packageName}.provider"
                                val contentUri = FileProvider.getUriForFile(applicationContext, authority, downloadedFile)
                                jobsManager.updateJob(currentJob.copy(
                                    status = "downloaded",
                                    localUri = contentUri.toString(),
                                    info = getString(R.string.info_download_completed, currentAttempt, maxAttempts)
                                ))
                                Log.d("YouTubeDL", "File downloaded successfully: ${downloadedFile.absolutePath}")
                                Toast.makeText(this@MainActivity, getString(R.string.toast_downloaded, downloadedFile.name), Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Log.e("YouTubeDL", "Failed to create file URI", e)
                                jobsManager.updateJob(currentJob.copy(
                                    status = "error",
                                    info = getString(R.string.error_file_access, e.message ?: "")
                                ))
                                Toast.makeText(this@MainActivity, getString(R.string.toast_download_error), Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Log.w("YouTubeDL", "No files found in download directory")
                            jobsManager.updateJob(currentJob.copy(
                                status = "error",
                                info = getString(R.string.error_no_files_in_directory)
                            ))
                            Toast.makeText(this@MainActivity, getString(R.string.toast_download_error), Toast.LENGTH_LONG).show()
                        }
                    }
                    
                    // If we reach here, download was successful, break the retry loop
                    break
                
                } catch (e: YoutubeDLException) {
                    val errorMsg = e.message ?: "Nieznany błąd"
                    Log.e("YouTubeDL", "Download failed for URL: ${entry.url} (Attempt $currentAttempt/$maxAttempts)", e)
                    
                    if (currentAttempt < maxAttempts) {
                        // Not the last attempt, show retry info
                        runOnUiThread {
                            val retryDelay = currentAttempt * 2 // Increasing delay: 2, 4, 6 seconds
                            jobsManager.updateJobStatus(entry.jobId, "downloading",
                                getString(R.string.info_attempt_failed_retry, currentAttempt, retryDelay))
                        }
                        
                        // Wait before retry
                        Thread.sleep((currentAttempt * 2000).toLong())
                        currentAttempt++
                        continue
                    } else {
                        // Last attempt failed, show final error
                        runOnUiThread {
                            // Parse common error messages
                            val userFriendlyError = when {
                                errorMsg.contains("NoneType") || errorMsg.contains("Signature solving failed") || errorMsg.contains("challenge solving failed") -> 
                                    getString(R.string.error_youtube_blocked, maxAttempts)
                                errorMsg.contains("Video unavailable") -> getString(R.string.error_video_unavailable)
                                errorMsg.contains("Private video") -> getString(R.string.error_private_video)
                                errorMsg.contains("No video formats") -> getString(R.string.error_no_formats)
                                errorMsg.contains("network") || errorMsg.contains("timeout") -> getString(R.string.error_network, maxAttempts)
                                errorMsg.contains("403") -> getString(R.string.error_access_denied)
                                errorMsg.contains("404") -> getString(R.string.error_video_not_found)
                                errorMsg.contains("IncompleteRead") -> getString(R.string.error_connection, maxAttempts)
                                errorMsg.contains("JS challenge") || errorMsg.contains("JavaScript runtime") -> 
                                    getString(R.string.error_verification_required, maxAttempts)
                                else -> getString(R.string.error_download_failed, maxAttempts, errorMsg.take(100))
                            }
                            
                            jobsManager.updateJobStatus(entry.jobId, "error", userFriendlyError)
                            Toast.makeText(this@MainActivity, userFriendlyError, Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.e("YouTubeDL", "Unexpected error during download (Attempt $currentAttempt/$maxAttempts)", e)
                    
                    if (currentAttempt < maxAttempts) {
                        runOnUiThread {
                            jobsManager.updateJobStatus(entry.jobId, "downloading",
                                getString(R.string.info_unexpected_error_retry, currentAttempt))
                        }
                        Thread.sleep(2000) // 2 second delay
                        currentAttempt++
                        continue
                    } else {
                        runOnUiThread {
                            jobsManager.updateJobStatus(entry.jobId, "error",
                                getString(R.string.error_unexpected, maxAttempts, e.message ?: ""))
                            Toast.makeText(this@MainActivity, getString(R.string.error_unexpected_short, maxAttempts), Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                }
            }
        }.start()
    }
    
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        // Get UI elements
        val formatSpinner = dialogView.findViewById<Spinner>(R.id.formatSpinner)
        val qualitySpinner = dialogView.findViewById<Spinner>(R.id.qualitySpinner)
        val attemptsSlider = dialogView.findViewById<Slider>(R.id.attemptsSeekBar)
        val attemptsText = dialogView.findViewById<TextView>(R.id.attemptsText)
        val playlistSwitch = dialogView.findViewById<MaterialSwitch>(R.id.playlistSwitch)
        val stopAllButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.stopAllButton)
        
        // Setup format spinner
        val formatAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.format_options,
            android.R.layout.simple_spinner_item
        )
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        formatSpinner.adapter = formatAdapter
        formatSpinner.setSelection(when(getDownloadFormat()) {
            "mp3" -> 0
            "mp4" -> 1
            else -> 2
        })
        
        // Check FFmpeg availability and show info about MP3 handling
        val ffmpegUnavailable = settings.getBoolean("ffmpeg_unavailable", false)
        if (ffmpegUnavailable) {
            val warningText = dialogView.findViewById<TextView>(R.id.ffmpegWarning)
            warningText?.let {
                it.text = getString(R.string.settings_ffmpeg_info)
                it.visibility = View.VISIBLE
            }
        }
        
        // Setup quality spinner
        val qualityAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.quality_options,
            android.R.layout.simple_spinner_item
        )
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        qualitySpinner.adapter = qualityAdapter
        qualitySpinner.setSelection(when(getDownloadQuality()) {
            "480p" -> 0
            "720p" -> 1
            "1080p" -> 2
            else -> 3
        })
        
        // Setup attempts slider (Material 3)
        attemptsSlider.value = getMaxAttempts().toFloat()
        attemptsText.text = getString(R.string.settings_max_attempts, getMaxAttempts())
        attemptsSlider.addOnChangeListener { _, value, _ ->
            attemptsText.text = getString(R.string.settings_max_attempts, value.toInt())
        }
        
        // Setup playlist switch
        playlistSwitch.isChecked = getAllowPlaylists()
        
        // Setup concurrent downloads slider (1-10 downloads)
        val concurrentDownloadsSlider = dialogView.findViewById<Slider>(R.id.concurrentDownloadsSeekBar)
        val concurrentDownloadsText = dialogView.findViewById<TextView>(R.id.concurrentDownloadsText)
        
        concurrentDownloadsSlider.value = getMaxConcurrentDownloads().toFloat()
        concurrentDownloadsText.text = getString(R.string.settings_concurrent, getMaxConcurrentDownloads())
        
        concurrentDownloadsSlider.addOnChangeListener { _, value, _ ->
            concurrentDownloadsText.text = getString(R.string.settings_concurrent, value.toInt())
        }
        
        // Setup stop all downloads button
        stopAllButton.setOnClickListener {
            stopAllDownloads()
        }
        
        // Setup yt-dlp update section
        val ytdlpVersionText = dialogView.findViewById<TextView>(R.id.ytdlpVersionText)
        val updateYtdlpButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.updateYtdlpButton)
        
        // Show current yt-dlp version
        val currentVersion = YoutubeDL.getInstance().versionName(this)
        if (currentVersion != null) {
            ytdlpVersionText.text = getString(R.string.settings_ytdlp_version, currentVersion)
        } else {
            ytdlpVersionText.text = getString(R.string.settings_ytdlp_version_unknown)
        }
        
        // Setup update button
        updateYtdlpButton.setOnClickListener {
            updateYtDlp()
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_settings))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                // Save settings
                val editor = settings.edit()
                
                val selectedFormat = when(formatSpinner.selectedItemPosition) {
                    0 -> "mp3"
                    1 -> "mp4"
                    else -> "best"
                }
                editor.putString(KEY_FORMAT, selectedFormat)
                
                val selectedQuality = when(qualitySpinner.selectedItemPosition) {
                    0 -> "480p"
                    1 -> "720p"
                    2 -> "1080p"
                    else -> "best"
                }
                editor.putString(KEY_QUALITY, selectedQuality)
                
                editor.putInt(KEY_MAX_ATTEMPTS, attemptsSlider.value.toInt())
                editor.putBoolean(KEY_ALLOW_PLAYLISTS, playlistSwitch.isChecked)
                editor.putInt(KEY_MAX_CONCURRENT_DOWNLOADS, concurrentDownloadsSlider.value.toInt())
                editor.apply()
                
                Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
    
    // DownloadService.DownloadServiceCallbacks implementation
    override fun onJobUpdated(job: JobEntry) {
        runOnUiThread {
            jobsManager.updateJob(job)
        }
    }
    
    override fun onDownloadCompleted(job: JobEntry) {
        runOnUiThread {
            jobsManager.updateJob(job)
            val fileName = job.localUri?.substringAfterLast("/") ?: "file"
            Toast.makeText(this, "Downloaded: $fileName", Toast.LENGTH_SHORT).show()
            
            // Decrease active count and process queue
            synchronized(downloadQueue) {
                activeDownloadCount--
            }
            processDownloadQueue()
        }
    }

    override fun onDownloadFailed(job: JobEntry, error: String) {
        runOnUiThread {
            jobsManager.updateJob(job)
            Toast.makeText(this, getString(R.string.toast_download_failed, job.title ?: job.jobId), Toast.LENGTH_SHORT).show()
            
            // Decrease active count and process queue
            synchronized(downloadQueue) {
                activeDownloadCount--
            }
            processDownloadQueue()
        }
    }
    
    private fun processDownloadQueue() {
        synchronized(downloadQueue) {
            val maxConcurrent = getMaxConcurrentDownloads()
            
            while (activeDownloadCount < maxConcurrent && downloadQueue.isNotEmpty()) {
                val nextJob = downloadQueue.removeAt(0)
                activeDownloadCount++
                
                runOnUiThread {
                    val updatedJob = nextJob.copy(info = getString(R.string.info_starting_download))
                    jobsManager.updateJob(updatedJob)
                }
                
                // Start download
                performSingleDownload(nextJob)
            }
        }
    }
    
    private fun setupSwipeToDelete(recyclerView: RecyclerView) {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val allJobs = jobsManager.getAllJobs()
                if (position < 0 || position >= allJobs.size) return
                
                val job = allJobs[position]
                
                // Show confirmation dialog
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.dialog_delete_title))
                    .setMessage(getString(R.string.dialog_delete_message, job.title ?: job.jobId))
                    .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                        deleteJobAndFile(job, position)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                        // Restore the item in the list
                        adapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener {
                        // Restore the item if dialog is dismissed
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }
            
            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val iconMargin = (itemView.height - 48) / 2  // 48dp icon with margin
                val iconSize = 48  // dp converted to pixels below
                val iconSizePx = (iconSize * resources.displayMetrics.density).toInt()
                val iconMarginPx = (24 * resources.displayMetrics.density).toInt()
                
                // Get delete icon
                val deleteIcon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)
                
                // Calculate alpha based on swipe distance (fade in effect)
                val swipeThreshold = itemView.width * 0.25f
                val alpha = (kotlin.math.abs(dX) / swipeThreshold).coerceIn(0f, 1f)
                
                // Background color with alpha
                val backgroundColor = ContextCompat.getColor(this@MainActivity, R.color.status_error)
                val paint = android.graphics.Paint().apply {
                    color = backgroundColor
                    this.alpha = (alpha * 255).toInt()
                }
                
                if (dX > 0) {
                    // Swiping right
                    val backgroundRect = android.graphics.RectF(
                        itemView.left.toFloat(),
                        itemView.top.toFloat(),
                        itemView.left + dX,
                        itemView.bottom.toFloat()
                    )
                    
                    // Draw rounded background
                    val cornerRadius = 16 * resources.displayMetrics.density
                    c.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, paint)
                    
                    // Draw delete icon
                    deleteIcon?.let { icon ->
                        val iconTop = itemView.top + (itemView.height - iconSizePx) / 2
                        val iconLeft = itemView.left + iconMarginPx
                        val iconRight = iconLeft + iconSizePx
                        val iconBottom = iconTop + iconSizePx
                        
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        icon.alpha = (alpha * 255).toInt()
                        icon.draw(c)
                    }
                } else if (dX < 0) {
                    // Swiping left
                    val backgroundRect = android.graphics.RectF(
                        itemView.right + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat()
                    )
                    
                    // Draw rounded background
                    val cornerRadius = 16 * resources.displayMetrics.density
                    c.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, paint)
                    
                    // Draw delete icon
                    deleteIcon?.let { icon ->
                        val iconTop = itemView.top + (itemView.height - iconSizePx) / 2
                        val iconRight = itemView.right - iconMarginPx
                        val iconLeft = iconRight - iconSizePx
                        val iconBottom = iconTop + iconSizePx
                        
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        icon.alpha = (alpha * 255).toInt()
                        icon.draw(c)
                    }
                }
                
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        
        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)
    }
    
    private fun deleteJobAndFile(job: JobEntry, position: Int) {
        // Stop download if active
        if (job.status == "downloading" || job.status == "queued") {
            val stopIntent = Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_STOP_DOWNLOAD
                putExtra(DownloadService.EXTRA_JOB_ID, job.jobId)
            }
            try {
                startService(stopIntent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to stop download: ${e.message}")
            }
        }
        
        // Delete file from disk if exists
        if (!job.localUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(job.localUri)
                
                // Try to get file path from content URI
                if (uri.scheme == "content") {
                    // For FileProvider URIs, we need to find the actual file
                    // The file is in Downloads/Everyload/ directory
                    val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val everyloadDir = File(publicDownloadsDir, "Everyload")
                    
                    // Find file by title or last path segment
                    val fileName = uri.lastPathSegment?.substringAfterLast("/") 
                        ?: job.title?.let { sanitizeFileName(it) }
                    
                    if (fileName != null) {
                        // Search for the file in Everyload directory
                        val possibleFiles = everyloadDir.listFiles()?.filter { 
                            it.name.contains(fileName, ignoreCase = true) ||
                            (job.title != null && it.name.contains(sanitizeFileName(job.title!!), ignoreCase = true))
                        }
                        
                        possibleFiles?.forEach { file ->
                            if (file.exists() && file.delete()) {
                                Log.d("MainActivity", "Deleted file: ${file.absolutePath}")
                            }
                        }
                    }
                } else if (uri.scheme == "file") {
                    val file = File(uri.path!!)
                    if (file.exists() && file.delete()) {
                        Log.d("MainActivity", "Deleted file: ${file.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to delete file: ${e.message}")
            }
        }
        
        // Remove from list
        jobsManager.removeJob(job.jobId)
        
        Toast.makeText(this, getString(R.string.toast_deleted, job.title ?: job.jobId), Toast.LENGTH_SHORT).show()
    }
    
    private fun sanitizeFileName(title: String): String {
        return title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\s]+"), "_")
            .replace(Regex("[_]+"), "_")
            .replace(Regex("^_|_$"), "")
            .take(100)
    }
    
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun handleJobClick(job: JobEntry) {
        if (job.status == "downloaded" && !job.localUri.isNullOrEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(Uri.parse(job.localUri), getMimeType(job.localUri!!))
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_cannot_open_file, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearAllJobs() {
        val jobCount = jobsManager.getJobCount()
        if (jobCount > 0) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_clear_list))
                .setMessage(getString(R.string.dialog_clear_message, jobCount))
                .setPositiveButton(getString(R.string.btn_yes)) { _, _ ->
                    // Stop any active downloads first
                    val activeJobs = jobsManager.getAllJobs().filter { job ->
                        job.status == "downloading" || job.status == "queued" || job.status == "pending"
                    }
                    
                    // Stop the download service for active downloads
                    if (activeJobs.isNotEmpty()) {
                        val stopIntent = Intent(this, DownloadService::class.java).apply {
                            action = DownloadService.ACTION_STOP_ALL_DOWNLOADS
                        }
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                startForegroundService(stopIntent)
                            } else {
                                startService(stopIntent)
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to stop download service: ${e.message}")
                        }
                    }
                    
                    // Clear all jobs
                    jobsManager.clearAllJobs()
                    Toast.makeText(this, getString(R.string.toast_deleted_all, jobCount), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        } else {
            Toast.makeText(this, getString(R.string.toast_no_jobs_to_delete), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopAllDownloads() {
        val activeJobs = jobsManager.getAllJobs().filter { job ->
            job.status == "downloading" || job.status == "queued" || job.status == "pending"
        }
        
        if (activeJobs.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_stop_downloads))
                .setMessage(getString(R.string.dialog_stop_message, activeJobs.size))
                .setPositiveButton(getString(R.string.btn_yes)) { _, _ ->
                    // Clear download queue and reset active count
                    synchronized(downloadQueue) {
                        downloadQueue.clear()
                        activeDownloadCount = 0
                    }
                    
                    // Stop the download service
                    val stopIntent = Intent(this, DownloadService::class.java)
                    stopIntent.action = DownloadService.ACTION_STOP_SERVICE
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(stopIntent)
                        } else {
                            startService(stopIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to stop download service: ${e.message}")
                    }
                    
                    // Update job statuses
                    activeJobs.forEach { job ->
                        val stoppedJob = job.copy(status = "stopped")
                        jobsManager.updateJob(stoppedJob)
                    }
                    
                    Toast.makeText(this, getString(R.string.toast_stopped_jobs, activeJobs.size), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        } else {
            Toast.makeText(this, getString(R.string.toast_no_active_downloads), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateYtDlp() {
        Toast.makeText(this, getString(R.string.toast_ytdlp_updating), Toast.LENGTH_SHORT).show()
        
        Thread {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    this,
                    YoutubeDL.UpdateChannel.STABLE
                )
                
                runOnUiThread {
                    when (status) {
                        YoutubeDL.UpdateStatus.DONE -> {
                            val newVersion = YoutubeDL.getInstance().versionName(this) ?: "unknown"
                            Toast.makeText(
                                this,
                                getString(R.string.toast_ytdlp_updated, newVersion),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                            val currentVersion = YoutubeDL.getInstance().versionName(this) ?: "unknown"
                            Toast.makeText(
                                this,
                                getString(R.string.toast_ytdlp_up_to_date, currentVersion),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> {
                            Toast.makeText(
                                this,
                                getString(R.string.toast_ytdlp_update_failed, "Unknown status"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to update yt-dlp", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_ytdlp_update_failed, e.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
    
    // JobsManager.JobChangeListener implementation
    override fun onJobAdded(job: JobEntry, position: Int) {
        runOnUiThread {
            adapter.submitList(jobsManager.getAllJobs())
            // Scroll to the top when a new job is added (position 0)
            if (position == 0) {
                recyclerView.scrollToPosition(0)
            }
        }
    }
    
    override fun onJobUpdated(job: JobEntry, position: Int) {
        runOnUiThread {
            adapter.submitList(jobsManager.getAllJobs())
        }
    }
    
    override fun onJobRemoved(jobId: String, position: Int) {
        runOnUiThread {
            adapter.submitList(jobsManager.getAllJobs())
        }
    }
    
    override fun onAllJobsCleared() {
        runOnUiThread {
            adapter.submitList(emptyList())
        }
    }
    
    // SensorEventListener implementation for shake detection
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = it.values[0]
                val y = it.values[1]
                val z = it.values[2]
                
                // Calculate acceleration magnitude (subtract gravity)
                val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() - SensorManager.GRAVITY_EARTH
                
                val currentTime = System.currentTimeMillis()
                
                // Reset shake count if too much time has passed
                if (currentTime - lastShakeTime > SHAKE_RESET_TIME) {
                    shakeCount = 0
                }
                
                // Detect individual shake
                if (gForce > SHAKE_THRESHOLD) {
                    val timeSinceLastShake = currentTime - lastShakeTime
                    
                    // Only count if not too soon after last shake (debounce)
                    if (timeSinceLastShake > 200) {
                        shakeCount++
                        lastShakeTime = currentTime
                        
                        Log.d("MainActivity", "Shake detected! Count: $shakeCount/$SHAKE_COUNT_REQUIRED")
                        
                        // Check if we have enough shakes within the time window
                        if (shakeCount >= SHAKE_COUNT_REQUIRED) {
                            shakeCount = 0 // Reset counter
                            onShakeDetected()
                        }
                    }
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for shake detection
    }
    
    private fun onShakeDetected() {
        // Open settings when shake is detected
        Log.d("MainActivity", "Shake detected! Opening settings...")
        showSettingsDialog()
    }
}
