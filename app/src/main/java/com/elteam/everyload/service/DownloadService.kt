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
        
        // Intent extras
        const val EXTRA_JOB_ENTRY = "job_entry"
        const val EXTRA_JOB_ID = "job_id"
    }

    private val binder = LocalBinder()
    private val activeDownloads = ConcurrentHashMap<String, JobEntry>()
    private var downloadCallbacks: DownloadServiceCallbacks? = null
    private lateinit var settings: SharedPreferences

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
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val jobEntry = intent.getSerializableExtra(EXTRA_JOB_ENTRY) as? JobEntry
                jobEntry?.let { startDownload(it) }
            }
            ACTION_STOP_DOWNLOAD -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                jobId?.let { stopDownload(it) }
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

        // Start foreground service
        if (activeDownloads.size == 1) {
            val notification = createNotification("Everyload", "Starting downloads...", 0)
            startForeground(NOTIFICATION_ID, notification)
        }

        // Perform download in background thread
        Thread {
            performDownload(job)
        }.start()

        Log.d("DownloadService", "Started download: ${job.jobId}")
    }

    private fun performDownload(job: JobEntry) {
        try {
            // Update status
            job.status = "downloading"
            downloadCallbacks?.onJobUpdated(job)
            updateNotification("Downloading", job.title ?: job.jobId, 0)

            // Create download directory with null check
            val externalDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) 
                ?: getExternalFilesDir(null) 
                ?: filesDir
            
            if (externalDir == null) {
                throw Exception("Unable to access any storage directory")
            }
            
            val youtubeDLDir = File(externalDir, "Everyload")
            Log.d("DownloadService", "Download directory: ${youtubeDLDir.absolutePath}")
            
            if (!youtubeDLDir.exists() && !youtubeDLDir.mkdirs()) {
                Log.e("DownloadService", "Failed to create download directory")
                throw Exception("Cannot create download directory")
            }
            
            // Validate download directory
            if (!youtubeDLDir.isDirectory() || !youtubeDLDir.canWrite()) {
                throw Exception("Download directory is not accessible or writable: ${youtubeDLDir.absolutePath}")
            }

            // Create download request with validated paths
            val outputTemplate = "${youtubeDLDir.absolutePath}/%(title)s.%(ext)s"
            Log.d("DownloadService", "Output template: $outputTemplate")

            // Create download request
            val request = YoutubeDLRequest(job.url).apply {
                addOption("-o", outputTemplate)
                addOption("--no-playlist")

                // Format settings
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
                    else -> {
                        val quality = getDownloadQuality()
                        if (quality == "best") {
                            addOption("--format", "best")
                        } else {
                            addOption("--format", "best[height<=${quality.replace("p", "")}]")
                        }
                    }
                }

                addOption("--extractor-retries", "3")
                addOption("--fragment-retries", "3")
                addOption("--skip-unavailable-fragments")
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
                        try {
                            val authority = "${applicationContext.packageName}.provider"
                            val contentUri = FileProvider.getUriForFile(applicationContext, authority, latestFile)
                            job.localUri = contentUri.toString()
                            job.status = "downloaded"
                        } catch (e: Exception) {
                            Log.e("DownloadService", "Failed to create file URI", e)
                        }
                    }

                    job.info = "Download completed"
                    downloadCallbacks?.onJobUpdated(job)
                    downloadCallbacks?.onDownloadCompleted(job)
                    break

                } catch (e: YoutubeDLException) {
                    if (currentAttempt < maxAttempts && activeDownloads.containsKey(job.jobId)) {
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
            
            job.status = "error"
            job.info = "Download failed: ${e.message}"
            downloadCallbacks?.onJobUpdated(job)
            downloadCallbacks?.onDownloadFailed(job, e.message ?: "Unknown error")
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

    // Settings helpers
    private fun getDownloadFormat(): String = settings.getString(KEY_FORMAT, "best") ?: "best"
    private fun getDownloadQuality(): String = settings.getString(KEY_QUALITY, "720p") ?: "720p"
    private fun getMaxAttempts(): Int = settings.getInt(KEY_MAX_ATTEMPTS, 3)

    override fun onDestroy() {
        super.onDestroy()
        stopAllDownloads()
        Log.d("DownloadService", "Service destroyed")
    }
}