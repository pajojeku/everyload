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
import com.elteam.everyload.model.JobEntry
import com.elteam.everyload.ui.JobAdapter
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

class MainActivity : AppCompatActivity(), DownloadService.DownloadServiceCallbacks {

    private val jobs = mutableListOf<JobEntry>()
    private lateinit var adapter: JobAdapter
    private lateinit var settings: SharedPreferences
    
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
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = getSharedPreferences("app_settings", MODE_PRIVATE)
        
        // Initialize YoutubeDL with enhanced error handling
        try {
            // Ensure app data directory exists
            val appDataDir = getExternalFilesDir(null) ?: filesDir
            if (!appDataDir.exists() && !appDataDir.mkdirs()) {
                Log.e("MainActivity", "Failed to create app data directory")
            }
            
            YoutubeDL.getInstance().init(this)
            Log.d("MainActivity", "YoutubeDL initialized successfully")
        } catch (e: YoutubeDLException) {
            Log.e("MainActivity", "Failed to initialize YoutubeDL", e)
            e.printStackTrace()
            showErrorDialog("Failed to initialize YoutubeDL: ${e.message}")
        }

        val recyclerView: RecyclerView = findViewById(R.id.jobsRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = JobAdapter(jobs, { job -> handleJobClick(job) }) { saveJobsToPrefs() }
        recyclerView.adapter = adapter

        val urlInput: TextInputEditText = findViewById(R.id.urlInput)
        val downloadButton: com.google.android.material.button.MaterialButton = findViewById(R.id.downloadButton)

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotBlank()) {
                startYtdlpDownload(url)
                urlInput.text?.clear()
            } else {
                Toast.makeText(this, "Wklej link do pobrania", Toast.LENGTH_SHORT).show()
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

        loadJobsFromPrefs()
        
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
                        .setTitle("Link Shared")
                        .setMessage("Do you want to download:\n$url")
                        .setPositiveButton("Download") { _, _ ->
                            processSharedUrl(url)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    Log.d("MainActivity", "No valid URL found in SEND intent")
                    // Show error if we received something but it's not a valid URL
                    if (sharedText != null || sharedSubject != null) {
                        AlertDialog.Builder(this)
                            .setTitle("Unsupported Link")
                            .setMessage("Received: ${sharedText ?: sharedSubject}\n\nOnly YouTube links are supported.")
                            .setPositiveButton("OK", null)
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
        Toast.makeText(this, "URL received from sharing", Toast.LENGTH_SHORT).show()

        // Check if it's a playlist and user allows playlists
        val isPlaylist = url.contains("list=") || 
                       url.contains("playlist") || 
                       url.contains("/playlist?") ||
                       url.contains("youtube.com/c/") ||
                       url.contains("youtube.com/@")

        if (isPlaylist && !getAllowPlaylists()) {
            // Ask user if they want to download playlist
            AlertDialog.Builder(this)
                .setTitle("Wykryto playlistę")
                .setMessage("Czy chcesz pobrać całą playlistę? Możesz to zmienić w ustawieniach.")
                .setPositiveButton("Tak, pobierz playlistę") { _, _ ->
                    startYtdlpDownload(url, forcePlaylist = true)
                    urlInput.text?.clear()
                }
                .setNegativeButton("Tylko to wideo") { _, _ ->
                    startYtdlpDownload(url, forcePlaylist = false)
                    urlInput.text?.clear()
                }
                .setNegativeButton("Anuluj") { _, _ ->
                    // Keep URL in field for manual download later
                }
                .show()
        } else {
            // Auto-start download and show confirmation
            startYtdlpDownload(url)
            urlInput.text?.clear()
            Toast.makeText(this, "Pobieranie rozpoczęte", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorDialog(message: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Błąd")
        builder.setMessage(message)
        builder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
        builder.setCancelable(true)
        builder.show()
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
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
                    Toast.makeText(this, "Some permissions were denied. App may not work properly.", Toast.LENGTH_LONG).show()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Notification permission denied. Background downloads won't show progress.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
            }
        }
    }

    // Start download directly with ytdlp library
    private fun startYtdlpDownload(url: String, forcePlaylist: Boolean? = null) {
        // Validate URL first
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank() || (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://"))) {
            Toast.makeText(this, "Nieprawidłowy URL: $trimmedUrl", Toast.LENGTH_LONG).show()
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
            val jobId = "ytdlp_${System.currentTimeMillis()}"
            val entry = JobEntry(jobId = jobId, url = trimmedUrl, status = "queued")
            jobs.add(0, entry)
            adapter.upsert(entry)
            
            runOnUiThread {
                Toast.makeText(this, "Dodano do kolejki", Toast.LENGTH_SHORT).show()
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
        val extractJobId = "extract_${System.currentTimeMillis()}"
        val extractEntry = JobEntry(jobId = extractJobId, url = playlistUrl, status = "extracting")
        extractEntry.info = "Pobieranie informacji o playliście..."
        jobs.add(0, extractEntry)
        adapter.upsert(extractEntry)
        
        Thread {
            try {
                // Create download directory with comprehensive null checks
                val externalDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) 
                    ?: getExternalFilesDir(null) 
                    ?: filesDir
                
                if (externalDir == null) {
                    runOnUiThread {
                        extractEntry.status = "error"
                        extractEntry.info = "Nie można uzyskać dostępu do katalogu"
                        adapter.upsert(extractEntry)
                        Toast.makeText(this@MainActivity, "Błąd: Nie można uzyskać dostępu do katalogu", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                
                val youtubeDLDir = File(externalDir, "Everyload")
                Log.d("MainActivity", "Playlist extraction directory: ${youtubeDLDir.absolutePath}")
                
                if (!youtubeDLDir.exists() && !youtubeDLDir.mkdirs()) {
                    runOnUiThread {
                        extractEntry.status = "error"
                        extractEntry.info = "Nie można utworzyć katalogu"
                        adapter.upsert(extractEntry)
                        Toast.makeText(this@MainActivity, "Błąd: Nie można utworzyć katalogu", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                
                // Validate directory access
                if (!youtubeDLDir.isDirectory() || !youtubeDLDir.canWrite()) {
                    runOnUiThread {
                        extractEntry.status = "error"
                        extractEntry.info = "Katalog niedostępny do zapisu"
                        adapter.upsert(extractEntry)
                        Toast.makeText(this@MainActivity, "Błąd: Katalog niedostępny do zapisu", Toast.LENGTH_LONG).show()
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
                    extractEntry.info = "Łączenie z playlistą..."
                    adapter.upsert(extractEntry)
                }
                
                val youtubeDLInstance = YoutubeDL.getInstance()
                if (youtubeDLInstance == null) {
                    Log.e("MainActivity", "YoutubeDL instance is null for playlist extraction")
                    runOnUiThread {
                        extractEntry.status = "error"
                        extractEntry.info = "YoutubeDL niedostępny"
                        adapter.upsert(extractEntry)
                        Toast.makeText(this@MainActivity, "Błąd: YoutubeDL niedostępny", Toast.LENGTH_LONG).show()
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
                        extractEntry.info = "Analizowanie playlisty: ${progress.toInt()}%"
                        adapter.upsert(extractEntry)
                    }
                }
                
                // Parse the output to create individual jobs
                val lines = output.toString().split("\n").filter { it.contains("|||") }
                
                runOnUiThread {
                    // Remove the extraction job
                    jobs.remove(extractEntry)
                    adapter.notifyDataSetChanged()
                    
                    if (lines.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Nie znaleziono filmów w playliście", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    
                    val message = "Znaleziono ${lines.size} filmów w playliście"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    
                    // Create separate jobs for each video
                    lines.forEachIndexed { index, line ->
                        val parts = line.split("|||")
                        if (parts.size >= 3) {
                            val title = parts[0].trim()
                            val videoId = parts[1].trim()
                            val videoUrl = parts[2].trim()
                            
                            val jobId = "ytdlp_${System.currentTimeMillis()}_$index"
                            val videoEntry = JobEntry(
                                jobId = jobId, 
                                url = videoUrl.ifEmpty { "https://www.youtube.com/watch?v=$videoId" },
                                status = "queued",
                                title = title.ifEmpty { "Video ${index + 1}" }
                            )
                            videoEntry.info = "W kolejce (${index + 1}/${lines.size})"
                            
                            jobs.add(0, videoEntry)
                            adapter.upsert(videoEntry)
                            
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
                    extractEntry.status = "error"
                    extractEntry.info = "Błąd pobierania informacji o playliście: ${e.message}"
                    adapter.upsert(extractEntry)
                    Toast.makeText(this@MainActivity, "Błąd analizy playlisty: ${e.message}", Toast.LENGTH_LONG).show()
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
                    entry.status = "error"
                    entry.info = "Nie można utworzyć katalogu pobierania"
                    adapter.upsert(entry)
                    Toast.makeText(this@MainActivity, "Błąd: Nie można utworzyć katalogu", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        
        // Validate download directory
        if (!youtubeDLDir.exists() || !youtubeDLDir.isDirectory()) {
            runOnUiThread {
                entry.status = "error"
                entry.info = "Download directory is not accessible"
                adapter.upsert(entry)
                Toast.makeText(this@MainActivity, "Error: Cannot access download directory", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        // Download request with user settings
        val request = YoutubeDLRequest(entry.url).apply {
            addOption("-o", "${youtubeDLDir.absolutePath}/%(title)s.%(ext)s")
            
            // Always download single video (no playlist)
            addOption("--no-playlist")
            
            // Format selection based on user preference
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
            
            // Add options to handle YouTube's anti-bot measures
            addOption("--extractor-retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--skip-unavailable-fragments")
        }
        
        // Update status to downloading
        runOnUiThread {
            entry.status = "downloading"
            adapter.upsert(entry)
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
                        entry.info = "Próba $currentAttempt/$maxAttempts - Łączenie..."
                        adapter.upsert(entry)
                    }
                    
                    val youtubeDLInstance = YoutubeDL.getInstance()
                    
                    youtubeDLInstance.execute(request) { progress, eta, line ->
                        Log.d("YouTubeDL", "$progress% (ETA $eta seconds) - $line")
                        runOnUiThread {
                            entry.info = "Próba $currentAttempt/$maxAttempts - Pobieranie: ${progress.toInt()}%"
                            adapter.upsert(entry)
                        }
                    }
                
                    // Download completed successfully
                    runOnUiThread {
                        entry.status = "finished"
                        entry.info = "Pobieranie zakończone (próba $currentAttempt/$maxAttempts)"
                        
                        // Find the downloaded file
                        val downloadedFiles = youtubeDLDir.listFiles()?.filter { 
                            it.isFile && it.lastModified() > System.currentTimeMillis() - 300000 // within last 5 minutes
                        }
                        
                        if (!downloadedFiles.isNullOrEmpty()) {
                            val latestFile = downloadedFiles.maxByOrNull { it.lastModified() }
                            if (latestFile != null && latestFile.exists()) {
                                try {
                                    // Use FileProvider for the downloaded file
                                    val authority = "${applicationContext.packageName}.provider"
                                    val contentUri = FileProvider.getUriForFile(applicationContext, authority, latestFile)
                                    entry.localUri = contentUri.toString()
                                    entry.status = "downloaded"
                                    Log.d("YouTubeDL", "File downloaded successfully: ${latestFile.absolutePath}")
                                } catch (e: Exception) {
                                    Log.e("YouTubeDL", "Failed to create file URI", e)
                                    entry.status = "error"
                                    entry.info = "Błąd dostępu do pliku: ${e.message}"
                                }
                            } else {
                                Log.w("YouTubeDL", "No downloaded file found in directory")
                                entry.status = "error"
                                entry.info = "Nie znaleziono pobranego pliku"
                            }
                        } else {
                            Log.w("YouTubeDL", "No files found in download directory")
                            entry.status = "error"
                            entry.info = "Brak plików w katalogu pobierania"
                        }
                        
                        adapter.upsert(entry)
                        val downloadedFileName = downloadedFiles?.firstOrNull()?.name ?: "plik"
                        if (entry.status == "downloaded") {
                            Toast.makeText(this@MainActivity, "Pobrano: $downloadedFileName", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Błąd pobierania", Toast.LENGTH_LONG).show()
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
                            entry.info = "Próba $currentAttempt nieudana. Ponowienie za ${retryDelay}s..."
                            adapter.upsert(entry)
                        }
                        
                        // Wait before retry
                        Thread.sleep((currentAttempt * 2000).toLong())
                        currentAttempt++
                        continue
                    } else {
                        // Last attempt failed, show final error
                        runOnUiThread {
                            entry.status = "error"
                            
                            // Parse common error messages
                            val userFriendlyError = when {
                                errorMsg.contains("NoneType") || errorMsg.contains("Signature solving failed") || errorMsg.contains("challenge solving failed") -> 
                                    "YouTube zablokował pobieranie po $maxAttempts próbach. Spróbuj ponownie za kilka minut."
                                errorMsg.contains("Video unavailable") -> "Wideo niedostępne"
                                errorMsg.contains("Private video") -> "Wideo prywatne"
                                errorMsg.contains("No video formats") -> "Brak dostępnych formatów wideo"
                                errorMsg.contains("network") || errorMsg.contains("timeout") -> "Błąd sieci po $maxAttempts próbach"
                                errorMsg.contains("403") -> "Dostęp zabroniony - wideo może być zablokowane"
                                errorMsg.contains("404") -> "Wideo nie znalezione"
                                errorMsg.contains("IncompleteRead") -> "Błąd połączenia z YouTube po $maxAttempts próbach"
                                errorMsg.contains("JS challenge") || errorMsg.contains("JavaScript runtime") -> 
                                    "YouTube wymaga dodatkowej weryfikacji po $maxAttempts próbach."
                                else -> "Błąd pobierania po $maxAttempts próbach: ${errorMsg.take(100)}"
                            }
                            
                            entry.info = userFriendlyError
                            adapter.upsert(entry)
                            Toast.makeText(this@MainActivity, userFriendlyError, Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.e("YouTubeDL", "Unexpected error during download (Attempt $currentAttempt/$maxAttempts)", e)
                    
                    if (currentAttempt < maxAttempts) {
                        runOnUiThread {
                            entry.info = "Nieoczekiwany błąd w próbie $currentAttempt. Ponowienie..."
                            adapter.upsert(entry)
                        }
                        Thread.sleep(2000) // 2 second delay
                        currentAttempt++
                        continue
                    } else {
                        runOnUiThread {
                            entry.status = "error"
                            entry.info = "Nieoczekiwany błąd po $maxAttempts próbach: ${e.message}"
                            adapter.upsert(entry)
                            Toast.makeText(this@MainActivity, "Nieoczekiwany błąd po $maxAttempts próbach", Toast.LENGTH_LONG).show()
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
                it.text = "ℹ️ MP3 downloads use format conversion for best compatibility."
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
        attemptsText.text = "Maksymalne próby: ${getMaxAttempts()}"
        attemptsSlider.addOnChangeListener { _, value, _ ->
            attemptsText.text = "Maksymalne próby: ${value.toInt()}"
        }
        
        // Setup playlist switch
        playlistSwitch.isChecked = getAllowPlaylists()
        
        // Setup concurrent downloads slider (1-10 downloads)
        val concurrentDownloadsSlider = dialogView.findViewById<Slider>(R.id.concurrentDownloadsSeekBar)
        val concurrentDownloadsText = dialogView.findViewById<TextView>(R.id.concurrentDownloadsText)
        
        concurrentDownloadsSlider.value = getMaxConcurrentDownloads().toFloat()
        concurrentDownloadsText.text = "Równoczesne pobierania: ${getMaxConcurrentDownloads()}"
        
        concurrentDownloadsSlider.addOnChangeListener { _, value, _ ->
            concurrentDownloadsText.text = "Równoczesne pobierania: ${value.toInt()}"
        }
        
        // Setup stop all downloads button
        stopAllButton.setOnClickListener {
            stopAllDownloads()
        }
        
        AlertDialog.Builder(this)
            .setTitle("Ustawienia")
            .setView(dialogView)
            .setPositiveButton("Zapisz") { _, _ ->
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
                
                Toast.makeText(this, "Ustawienia zapisane", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }
    
    // DownloadService.DownloadServiceCallbacks implementation
    override fun onJobUpdated(job: JobEntry) {
        runOnUiThread {
            adapter.upsert(job)
        }
    }
    
    override fun onDownloadCompleted(job: JobEntry) {
        runOnUiThread {
            adapter.upsert(job)
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
            adapter.upsert(job)
            Toast.makeText(this, "Download failed: ${job.title ?: job.jobId}", Toast.LENGTH_SHORT).show()
            
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
                    nextJob.info = "Starting download..."
                    adapter.upsert(nextJob)
                }
                
                // Start download
                performSingleDownload(nextJob)
            }
        }
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
                Toast.makeText(this, "Nie można otworzyć pliku: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveJobsToPrefs() {
        val filteredJobs = jobs.filter { it.status != "downloading" || !it.info.isNullOrEmpty() && it.info!!.contains("%") }
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        try {
            val jsonArray = JSONArray()
            for (job in filteredJobs) {
                val json = JSONObject().apply {
                    put("jobId", job.jobId)
                    put("url", job.url)
                    put("status", job.status)
                    if (!job.info.isNullOrEmpty()) put("info", job.info)
                    if (!job.title.isNullOrEmpty()) put("title", job.title)
                    if (job.files != null) put("files", JSONArray(job.files!!))
                    if (job.localUri != null) put("localUri", job.localUri!!)
                    if (job.downloadId != null) put("downloadId", job.downloadId!!)
                }
                jsonArray.put(json)
            }
            
            prefs.edit().putString(KEY_JOBS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save jobs", e)
        }
    }

    private fun loadJobsFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jobsJson = prefs.getString(KEY_JOBS, "[]") ?: "[]"
        
        try {
            val jsonArray = JSONArray(jobsJson)
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val files = if (json.has("files")) {
                    val filesArray = json.getJSONArray("files")
                    List(filesArray.length()) { idx -> filesArray.getString(idx) }
                } else null
                
                val job = JobEntry(
                    jobId = json.getString("jobId"),
                    url = json.getString("url"),
                    status = json.getString("status"),
                    info = json.optString("info", null).takeIf { !it.isNullOrEmpty() },
                    title = json.optString("title", null).takeIf { !it.isNullOrEmpty() },
                    files = files,
                    localUri = json.optString("localUri", null).takeIf { !it.isNullOrEmpty() },
                    downloadId = if (json.has("downloadId")) json.getLong("downloadId") else null
                )
                jobs.add(job)
            }
            adapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load jobs", e)
        }
    }

    private fun clearAllJobs() {
        if (jobs.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Wyczyść listę")
                .setMessage("Czy chcesz usunąć wszystkie zadania (${jobs.size})? To zatrzyma także aktywne pobierania.")
                .setPositiveButton("Tak") { _, _ ->
                    // Stop any active downloads first
                    val activeJobs = jobs.filter { job ->
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
                    
                    // Clear all jobs regardless of status
                    val totalJobs = jobs.size
                    jobs.clear()
                    adapter.notifyDataSetChanged()
                    saveJobsToPrefs()
                    Toast.makeText(this, "Usunięto wszystkie zadania ($totalJobs)", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Anuluj", null)
                .show()
        } else {
            Toast.makeText(this, "Brak zadań do usunięcia", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopAllDownloads() {
        val activeJobs = jobs.filter { job ->
            job.status == "downloading" || job.status == "queued" || job.status == "pending"
        }
        
        if (activeJobs.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Zatrzymaj pobierania")
                .setMessage("Czy chcesz zatrzymać wszystkie aktywne pobierania? (${activeJobs.size} zadań)")
                .setPositiveButton("Tak") { _, _ ->
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
                        job.status = "stopped"
                    }
                    adapter.notifyDataSetChanged()
                    saveJobsToPrefs()
                    
                    Toast.makeText(this, "Zatrzymano ${activeJobs.size} zadań", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Anuluj", null)
                .show()
        } else {
            Toast.makeText(this, "Brak aktywnych pobrań do zatrzymania", Toast.LENGTH_SHORT).show()
        }
    }
}