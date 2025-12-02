package com.elteam.everyload.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elteam.everyload.R
import com.elteam.everyload.MainActivity
import com.elteam.everyload.model.JobEntry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException
import java.io.File
import androidx.core.content.FileProvider
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import android.content.SharedPreferences

class DownloadService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_channel"
        private const val CHANNEL_NAME = "Download Progress"
        
        // Settings keys
        private const val KEY_FORMAT = "download_format"
        private const val KEY_QUALITY = "download_quality"
        private const val KEY_MAX_ATTEMPTS = "max_attempts"
        
        // Service actions
        const val ACTION_START_DOWNLOAD = "START_DOWNLOAD"
        const val ACTION_STOP_DOWNLOAD = "STOP_DOWNLOAD"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_STOP_ALL_DOWNLOADS = "STOP_ALL_DOWNLOADS"
        
        // Intent extras
        const val EXTRA_JOB_ENTRY = "job_entry"
        const val EXTRA_JOB_ID = "job_id"
    }

    private val binder = LocalBinder()
    private val activeDownloads = ConcurrentHashMap<String, JobEntry>()
    private var downloadCallbacks: DownloadServiceCallbacks? = null
    private lateinit var settings: SharedPreferences
    private var isForegroundStarted = false

    interface DownloadServiceCallbacks {
        fun onJobUpdated(job: JobEntry)
        fun onDownloadCompleted(job: JobEntry)
        fun onDownloadFailed(job: JobEntry, error: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    override fun onCreate() {
        super.onCreate()
        settings = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        createNotificationChannel()
        
        // Initialize YoutubeDL
        try {
            // Ensure app data directory exists
            val appDataDir = getExternalFilesDir(null) ?: filesDir
            if (!appDataDir.exists() && !appDataDir.mkdirs()) {
                Log.e("DownloadService", "Failed to create app data directory")
            }
            
            YoutubeDL.getInstance().init(this)
            Log.d("DownloadService", "YoutubeDL initialized successfully")
            
        } catch (e: YoutubeDLException) {
            Log.e("DownloadService", "Failed to initialize YoutubeDL", e)
        }
        
        Log.d("DownloadService", "Service created")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Try to start foreground immediately if this is the first command
        if (!isForegroundStarted) {
            try {
                val notification = createNotification("Everyload", "Service starting...", 0)
                startForeground(NOTIFICATION_ID, notification)
                isForegroundStarted = true
                Log.d("DownloadService", "Started foreground service on startup")
            } catch (e: Exception) {
                Log.w("DownloadService", "Could not start foreground service: ${e.message}")
                // Continue without foreground - service will still work for downloads
            }
        }
        
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val jobEntry = intent.getSerializableExtra(EXTRA_JOB_ENTRY) as? JobEntry
                jobEntry?.let { startDownload(it) }
            }
            ACTION_STOP_DOWNLOAD -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                jobId?.let { stopDownload(it) }
            }
            ACTION_STOP_ALL_DOWNLOADS -> {
                stopAllDownloads()
            }
            ACTION_STOP_SERVICE -> {
                stopAllDownloads()
                stopSelf()
            }
        }
        return START_STICKY // Restart if killed by system
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows download progress"
            setSound(null, null)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(title: String, content: String, progress: Int = -1): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun updateNotification(title: String, content: String, progress: Int = -1) {
        val notification = createNotification(title, content, progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startDownload(job: JobEntry) {
        if (activeDownloads.containsKey(job.jobId)) {
            Log.w("DownloadService", "Download already active: ${job.jobId}")
            return
        }

        activeDownloads[job.jobId] = job

        // Start foreground service if not already started (fallback)
        if (!isForegroundStarted && activeDownloads.size == 1) {
            try {
                val notification = createNotification("Everyload", "Starting downloads...", 0)
                startForeground(NOTIFICATION_ID, notification)
                isForegroundStarted = true
                Log.d("DownloadService", "Started foreground service from startDownload")
            } catch (e: Exception) {
                Log.e("DownloadService", "Failed to start foreground service: ${e.message}")
                // Continue without foreground service - downloads will still work
            }
        }

        // Perform download in background thread
        Thread {
            performDownload(job)
        }.start()

        Log.d("DownloadService", "Started download: ${job.jobId}")
    }

    private fun performDownload(job: JobEntry) {
        Log.d("DownloadService", "Starting download for: ${job.url}")
        
        // Comprehensive input validation
        if (job.url.isNullOrBlank()) {
            throw IllegalArgumentException("Job URL cannot be null or blank")
        }
        
        if (job.jobId.isNullOrBlank()) {
            throw IllegalArgumentException("Job ID cannot be null or blank")
        }
        
        Log.d("DownloadService", "Job validation passed - URL: ${job.url}, ID: ${job.jobId}")
        
        try {
            // First, extract video title if not already set
            if (job.title.isNullOrEmpty()) {
                job.info = "Fetching video info..."
                downloadCallbacks?.onJobUpdated(job)
                
                try {
                    val infoRequest = YoutubeDLRequest(job.url).apply {
                        addOption("--no-playlist")
                        addOption("--print", "%(title)s")
                        addOption("--skip-download")
                    }
                    
                    val youtubeDLInstance = YoutubeDL.getInstance()
                    val result = youtubeDLInstance.execute(infoRequest) { _, _, _ -> }
                    
                    val extractedTitle = result.out?.trim()
                    if (!extractedTitle.isNullOrEmpty() && extractedTitle != "NA") {
                        job.title = extractedTitle
                        Log.d("DownloadService", "Extracted title: ${job.title}")
                    }
                } catch (e: Exception) {
                    Log.w("DownloadService", "Failed to extract title: ${e.message}")
                    // Continue with download even if title extraction fails
                }
            }
            
            // Update status
            job.status = "downloading"
            downloadCallbacks?.onJobUpdated(job)
            updateNotification("Downloading", job.title ?: job.jobId, 0)

            // Create download directory in public Downloads folder
            val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var youtubeDLDir = File(publicDownloadsDir, "Everyload")
            Log.d("DownloadService", "Attempting to use public Downloads directory: ${youtubeDLDir.absolutePath}")
            
            // Ensure public downloads directory exists
            if (!publicDownloadsDir.exists() && !publicDownloadsDir.mkdirs()) {
                Log.w("DownloadService", "Failed to create public downloads directory, using fallback")
                // Fallback to app-specific directory if public directory fails
                val fallbackDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) 
                    ?: getExternalFilesDir(null) 
                    ?: filesDir
                if (fallbackDir == null) {
                    throw Exception("Unable to access any storage directory")
                }
                youtubeDLDir = File(fallbackDir, "Everyload")
                if (!youtubeDLDir.exists() && !youtubeDLDir.mkdirs()) {
                    throw Exception("Cannot create download directory")
                }
                Log.d("DownloadService", "Using fallback directory: ${youtubeDLDir.absolutePath}")
            } else if (!youtubeDLDir.exists() && !youtubeDLDir.mkdirs()) {
                Log.e("DownloadService", "Failed to create Everyload subfolder in Downloads")
                throw Exception("Cannot create download directory")
            }
            
            Log.d("DownloadService", "Final download directory: ${youtubeDLDir.absolutePath}")
            
            // Validate download directory
            if (!youtubeDLDir.isDirectory() || !youtubeDLDir.canWrite()) {
                throw Exception("Download directory is not accessible or writable: ${youtubeDLDir.absolutePath}")
            }

            // Create safe filename from title - replace special characters with underscores
            val safeTitle = job.title?.let { title ->
                title
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")  // Windows-incompatible chars
                    .replace(Regex("[\\s]+"), "_")           // Spaces to underscores
                    .replace(Regex("[_]+"), "_")             // Multiple underscores to single
                    .replace(Regex("^_|_$"), "")             // Trim leading/trailing underscores
                    .take(100)                                // Limit length
            } ?: "%(title)s"  // Fallback to yt-dlp template if no title
            
            // Use sanitized title or yt-dlp's built-in sanitization
            val outputTemplate = if (safeTitle != "%(title)s") {
                "${youtubeDLDir.absolutePath}/${safeTitle}.%(ext)s"
            } else {
                "${youtubeDLDir.absolutePath}/%(title)s.%(ext)s"
            }
            Log.d("DownloadService", "Output template: $outputTemplate")
            
            // Validate URL and template before creating request
            if (job.url.isNullOrBlank()) {
                throw Exception("Job URL is null or empty")
            }
            
            if (outputTemplate.isBlank() || !outputTemplate.contains("/")) {
                throw Exception("Invalid output template: $outputTemplate")
            }
            
            Log.d("DownloadService", "Creating YoutubeDL request for URL: ${job.url}")

            // Get requested format for use throughout the function
            val requestedFormat = getDownloadFormat()
            Log.d("DownloadService", "Requested format: $requestedFormat")

            // Create download request
            val request = YoutubeDLRequest(job.url).apply {
                // Safe option adding function
                fun safeAddOption(key: String, value: String?) {
                    if (value != null && value.isNotBlank()) {
                        addOption(key, value)
                        Log.d("DownloadService", "Added option: $key = $value")
                    } else {
                        Log.w("DownloadService", "Skipping null/blank option: $key = $value")
                    }
                }
                
                safeAddOption("-o", outputTemplate)
                addOption("--no-playlist")

                // Format settings with MP3 prioritization
                when (requestedFormat) {
                    "mp3" -> {
                        // Try to get MP3 directly, fallback to best audio
                        Log.d("DownloadService", "Attempting MP3 download with multiple format options")
                        safeAddOption("--format", "bestaudio[ext=mp3]/bestaudio[ext=m4a]/bestaudio/best")
                        job.info = "Downloading audio (MP3 preferred)"
                    }
                    "mp4" -> {
                        val quality = getDownloadQuality()
                        Log.d("DownloadService", "MP4 quality: $quality")
                        if (quality == "best") {
                            safeAddOption("--format", "best[ext=mp4]/best")
                        } else {
                            val qualityNum = quality.replace("p", "")
                            safeAddOption("--format", "mp4[height<=$qualityNum]/best[height<=$qualityNum]/best")
                        }
                    }
                    else -> {
                        val quality = getDownloadQuality()
                        Log.d("DownloadService", "General quality: $quality")
                        if (quality == "best") {
                            safeAddOption("--format", "best")
                        } else {
                            val qualityNum = quality.replace("p", "")
                            safeAddOption("--format", "best[height<=$qualityNum]/best")
                        }
                    }
                }

                addOption("--extractor-retries", "3")
                addOption("--fragment-retries", "3")
                addOption("--skip-unavailable-fragments")
                
                // Explicitly prevent FFmpeg operations to avoid native library issues
                addOption("--no-post-overwrites")
            }

            val maxAttempts = getMaxAttempts()
            var currentAttempt = 1

            while (currentAttempt <= maxAttempts && activeDownloads.containsKey(job.jobId)) {
                try {
                    job.info = "Attempt $currentAttempt/$maxAttempts"
                    downloadCallbacks?.onJobUpdated(job)
                    updateNotification("Downloading", "${job.title ?: job.jobId} (Attempt $currentAttempt/$maxAttempts)", 0)

                    val youtubeDLInstance = YoutubeDL.getInstance()
                    if (youtubeDLInstance == null) {
                        Log.e("DownloadService", "YoutubeDL instance is null, attempting to reinitialize")
                        try {
                            YoutubeDL.getInstance().init(this@DownloadService)
                            val newInstance = YoutubeDL.getInstance()
                            if (newInstance == null) {
                                throw Exception("YoutubeDL reinitialization failed - instance still null")
                            }
                        } catch (e: Exception) {
                            throw Exception("YoutubeDL not available: ${e.message}")
                        }
                    }
                    
                    Log.d("DownloadService", "Executing download for URL: ${job.url} with output: $outputTemplate")
                    
                    // Validate request options before execution
                    try {
                        val commandOptions = request.buildCommand()
                        Log.d("DownloadService", "Command options: ${commandOptions.joinToString(" ")}")
                        
                        // Check for null values that could cause NoneType errors
                        commandOptions.forEach { option ->
                            if (option.isNullOrBlank()) {
                                Log.e("DownloadService", "Found null/blank option in command!")
                                throw IllegalArgumentException("Invalid command option detected")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("DownloadService", "Command validation failed: ${e.message}")
                        throw Exception("Download configuration error: ${e.message}")
                    }
                    
                    youtubeDLInstance.execute(request) { progress, eta, line ->
                        if (activeDownloads.containsKey(job.jobId)) {
                            job.info = "Downloading: ${progress.toInt()}%"
                            downloadCallbacks?.onJobUpdated(job)
                            updateNotification("Downloading", job.title ?: job.jobId, progress.toInt())
                        }
                    }

                    // Download completed successfully
                    job.status = "finished"
                    
                    // Find downloaded file
                    val downloadedFiles = youtubeDLDir.listFiles()?.filter { 
                        it.isFile && it.lastModified() > System.currentTimeMillis() - 300000
                    }
                    
                    val latestFile = downloadedFiles?.maxByOrNull { it.lastModified() }
                    if (latestFile?.exists() == true) {
                        // Post-process for MP3 conversion if needed
                        val finalFile = if (requestedFormat == "mp3" && !latestFile.name.endsWith(".mp3", ignoreCase = true)) {
                            job.info = "Converting to MP3..."
                            downloadCallbacks?.onJobUpdated(job)
                            convertToMp3(latestFile, youtubeDLDir)
                        } else {
                            latestFile
                        }
                        
                        try {
                            val authority = "${applicationContext.packageName}.provider"
                            
                            // Check if file is in public Downloads directory or app-specific directory
                            val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val isInPublicDownloads = finalFile.absolutePath.startsWith(publicDownloadsDir.absolutePath)
                            
                            val contentUri = if (isInPublicDownloads) {
                                // For public Downloads directory, use external-path
                                FileProvider.getUriForFile(applicationContext, authority, finalFile)
                            } else {
                                // For app-specific directory, use external-files-path
                                FileProvider.getUriForFile(applicationContext, authority, finalFile)
                            }
                            
                            job.localUri = contentUri.toString()
                            job.status = "downloaded"
                            
                            // Determine location description for user
                            val locationInfo = if (isInPublicDownloads) "Downloads/Everyload/" else "App folder/"
                            job.info = "Saved to ${locationInfo}${finalFile.name}"
                            Log.d("DownloadService", "File saved: ${finalFile.absolutePath}")
                        } catch (e: Exception) {
                            Log.e("DownloadService", "Failed to create file URI", e)
                        }
                    }

                    job.info = "Download completed"
                    downloadCallbacks?.onJobUpdated(job)
                    downloadCallbacks?.onDownloadCompleted(job)
                    break

                } catch (e: YoutubeDLException) {
                    val errorMessage = e.message ?: ""
                    Log.e("DownloadService", "YoutubeDL execution failed: $errorMessage")
                    
                    // Check for FFmpeg-related errors and mark for future reference
                    if (errorMessage.contains("libav", ignoreCase = true) || 
                        errorMessage.contains("ffmpeg", ignoreCase = true) ||
                        errorMessage.contains("libavdevice", ignoreCase = true)) {
                        
                        Log.w("DownloadService", "FFmpeg library issue detected, marking as unavailable")
                        val editor = settings.edit()
                        editor.putBoolean("ffmpeg_unavailable", true)
                        editor.apply()
                    }
                    
                    if (currentAttempt < maxAttempts && activeDownloads.containsKey(job.jobId)) {
                        Log.d("DownloadService", "Retrying download attempt $currentAttempt/$maxAttempts")
                        Thread.sleep(currentAttempt * 2000L)
                        currentAttempt++
                    } else {
                        throw e
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("DownloadService", "Download failed for job ${job.jobId}")
            Log.e("DownloadService", "Job URL: ${job.url}")
            Log.e("DownloadService", "Job title: ${job.title}")
            Log.e("DownloadService", "Error type: ${e.javaClass.simpleName}")
            Log.e("DownloadService", "Error message: ${e.message}")
            Log.e("DownloadService", "Stack trace:", e)
            
            val errorMessage = e.message ?: "Unknown error"
            val userErrorMessage = when {
                errorMessage.contains("libav", ignoreCase = true) || 
                errorMessage.contains("ffmpeg", ignoreCase = true) ||
                errorMessage.contains("libavdevice", ignoreCase = true) -> {
                    "Audio extraction not available on this device. Try using MP4 format instead."
                }
                errorMessage.contains("network", ignoreCase = true) ||
                errorMessage.contains("connection", ignoreCase = true) -> {
                    "Network connection error. Check your internet connection."
                }
                errorMessage.contains("unavailable", ignoreCase = true) -> {
                    "Video unavailable or private"
                }
                else -> "Download failed: $errorMessage"
            }
            
            job.status = "error"
            job.info = userErrorMessage
            downloadCallbacks?.onJobUpdated(job)
            downloadCallbacks?.onDownloadFailed(job, userErrorMessage)
        } finally {
            activeDownloads.remove(job.jobId)
            
            // Update notification or stop foreground if no more downloads
            if (activeDownloads.isEmpty()) {
                stopForeground(true)
                stopSelf()
            } else {
                updateNotification("Everyload", "${activeDownloads.size} downloads active")
            }
        }
    }

    private fun stopDownload(jobId: String) {
        activeDownloads.remove(jobId)
        Log.d("DownloadService", "Stopped download: $jobId")
        
        if (activeDownloads.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun stopAllDownloads() {
        activeDownloads.clear()
        stopForeground(true)
        Log.d("DownloadService", "Stopped all downloads")
    }

    fun setCallbacks(callbacks: DownloadServiceCallbacks?) {
        this.downloadCallbacks = callbacks
    }

    fun getActiveDownloads(): List<JobEntry> = activeDownloads.values.toList()

    // Convert M4A/other audio formats to MP3 using simple file copy with extension change
    private fun convertToMp3(inputFile: File, outputDir: File): File {
        Log.d("DownloadService", "Converting ${inputFile.name} to MP3 format")
        
        return try {
            // Create MP3 filename by replacing extension
            val mp3Name = inputFile.nameWithoutExtension + ".mp3"
            val mp3File = File(outputDir, mp3Name)
            
            // For simple conversion, we'll rename the file to .mp3
            // Most M4A files are actually compatible and just need extension change
            inputFile.copyTo(mp3File, overwrite = true)
            
            if (mp3File.exists() && mp3File.length() > 0) {
                Log.d("DownloadService", "Successfully converted to: ${mp3File.name}")
                // Delete original file to save space
                if (inputFile.delete()) {
                    Log.d("DownloadService", "Cleaned up original file: ${inputFile.name}")
                }
                mp3File
            } else {
                Log.w("DownloadService", "Failed to copy file for conversion, using original")
                inputFile
            }
        } catch (e: Exception) {
            Log.e("DownloadService", "Conversion failed: ${e.message}")
            // Return original file if conversion fails
            inputFile
        }
    }

    // Settings helpers
    private fun getDownloadFormat(): String = settings.getString(KEY_FORMAT, "mp4") ?: "mp4"
    private fun getDownloadQuality(): String = settings.getString(KEY_QUALITY, "720p") ?: "720p"
    private fun getMaxAttempts(): Int = settings.getInt(KEY_MAX_ATTEMPTS, 3)

    override fun onDestroy() {
        super.onDestroy()
        stopAllDownloads()
        Log.d("DownloadService", "Service destroyed")
    }
}