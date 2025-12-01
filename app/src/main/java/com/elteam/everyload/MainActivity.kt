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

// YTDLP imports
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException

class MainActivity : AppCompatActivity() {

    private val jobs = mutableListOf<JobEntry>()
    private lateinit var adapter: JobAdapter
    
    companion object {
        private const val PREFS_NAME = "everyload_prefs"
        private const val KEY_JOBS = "jobs_json"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize YTDLP
        try {
            YoutubeDL.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e("YouTubeDL", "Failed to initialize youtubedl-android", e)
        }

        // Check and request permissions
        checkPermissions()

        // Load saved jobs from SharedPreferences
        loadJobs()

        // Setup RecyclerView
        val recycler = findViewById<RecyclerView>(R.id.jobsRecycler)
        adapter = JobAdapter(jobs, { job ->
            // onClick: if downloaded, open local file; otherwise show status
            if (job.status == "downloaded") {
                // open localUri
                job.localUri?.let { uri ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(uri), "video/*")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Nie można otworzyć pliku: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                // Show current status
                checkStatus(job.jobId)
            }
        }, {
            // onChanged callback - save jobs whenever adapter updates
            saveJobs()
            updateEmptyState()
        })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        
        // Setup swipe-to-delete
        setupSwipeToDelete(recycler)
        
        // Update empty state initially
        updateEmptyState()
        
        // Handle share intents when activity is first created - NOW adapter is initialized
        handleSendIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the intent and handle the new share
        setIntent(intent)
        handleSendIntent(intent)
    }

    // If another app shares text (link), start download directly with ytdlp
    private fun handleSendIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                startYtdlpDownload(sharedText)
            }
        }
    }

    private fun showErrorDialog(message: String) {
        // Use AlertDialog from AppCompat for consistent styling
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
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = permissions.filterIndexed { index, _ -> 
                grantResults[index] != PackageManager.PERMISSION_GRANTED 
            }
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(this, "Niektóre uprawnienia zostały odrzucone. Aplikacja może nie działać poprawnie.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Start download directly with ytdlp library
    private fun startYtdlpDownload(url: String) {
        // Validate URL first
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank() || (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://"))) {
            Toast.makeText(this, "Nieprawidłowy URL: $trimmedUrl", Toast.LENGTH_LONG).show()
            return
        }
        
        val jobId = "ytdlp_${System.currentTimeMillis()}"
        val entry = JobEntry(jobId = jobId, url = trimmedUrl, status = "queued")
        jobs.add(0, entry)
        adapter.upsert(entry)
        
        runOnUiThread {
            Toast.makeText(this, "Dodano do kolejki", Toast.LENGTH_SHORT).show()
        }
        
        // Download directory - use app external files directory for better compatibility
        val youtubeDLDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Everyload")
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
        
        // Download request with basic options
        val request = YoutubeDLRequest(trimmedUrl).apply {
            addOption("-o", "${youtubeDLDir.absolutePath}/%(title)s.%(ext)s")
            addOption("--no-playlist")
            // Add options to handle YouTube's anti-bot measures
            addOption("--extractor-retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--skip-unavailable-fragments")
            // Try to avoid signature challenges
            addOption("--format", "best[height<=720]") // Lower quality to avoid signature issues
        }
        
        // Update status to downloading
        runOnUiThread {
            entry.status = "downloading"
            adapter.upsert(entry)
        }
        
        // Execute download in background thread with automatic retries
        Thread {
            val maxAttempts = 3
            var currentAttempt = 1
            
            while (currentAttempt <= maxAttempts) {
                try {
                    Log.d("YouTubeDL", "Starting download for URL: $trimmedUrl (Attempt $currentAttempt/$maxAttempts)")
                    Log.d("YouTubeDL", "Download directory: ${youtubeDLDir.absolutePath}")
                    
                    // Update status with attempt info
                    runOnUiThread {
                        entry.info = "Próba $currentAttempt/$maxAttempts - Łączenie..."
                        adapter.upsert(entry)
                    }
                    
                    val youtubeDLInstance = YoutubeDL.getInstance()
                    if (youtubeDLInstance == null) {
                        runOnUiThread {
                            entry.status = "error"
                            entry.info = "YoutubeDL nie został zainicjalizowany"
                            adapter.upsert(entry)
                            Toast.makeText(this@MainActivity, "Błąd inicjalizacji YoutubeDL", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    
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
                    Log.e("YouTubeDL", "Download failed for URL: $trimmedUrl (Attempt $currentAttempt/$maxAttempts)", e)
                    
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
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }

    private fun checkStatus(jobId: String, showDialog: Boolean = true) {
        Log.d("Everyload", "checkStatus request for $jobId showDialog=$showDialog")
        val job = jobs.find { it.jobId == jobId }
        if (job != null) {
            val msg = "Status: ${job.status}\n${if (!job.info.isNullOrEmpty()) "Info: ${job.info}" else ""}"
            if (showDialog) {
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Status").setMessage(msg).setPositiveButton("OK") { d, _ -> d.dismiss() }.show()
                }
            }
        }
    }

    private fun sanitizeFilename(name: String): String {
        var n = name
        
        // Replace spaces with underscores
        n = n.replace(" ", "_")
        
        // Remove emojis and special Unicode characters (keep only basic Latin, digits, underscore, dash, dot)
        n = n.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        
        // Replace multiple consecutive underscores with single underscore
        n = n.replace(Regex("_{2,}"), "_")
        
        // Trim underscores from start and end
        n = n.trim('_')
        
        // Fallback to timestamp if empty after sanitization
        if (n.isEmpty()) n = "downloaded_file_${System.currentTimeMillis()}"
        
        return n
    }

    // Save jobs to SharedPreferences as JSON
    private fun saveJobs() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            for (job in jobs) {
                val obj = JSONObject()
                obj.put("jobId", job.jobId)
                obj.put("url", job.url)
                obj.put("status", job.status)
                obj.put("info", job.info ?: "")
                obj.put("localUri", job.localUri ?: "")
                obj.put("downloadTriggered", job.downloadTriggered)
                // save files array
                if (job.files != null) {
                    val filesArr = JSONArray()
                    for (f in job.files!!) filesArr.put(f)
                    obj.put("files", filesArr)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_JOBS, jsonArray.toString()).apply()
            Log.d("Everyload", "Saved ${jobs.size} jobs to SharedPreferences")
        } catch (e: Exception) {
            Log.e("Everyload", "Error saving jobs: ${e.message}")
        }
    }

    // Load jobs from SharedPreferences, filter out error/download_error statuses
    private fun loadJobs() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_JOBS, null)
            if (jsonStr != null) {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val status = obj.optString("status", "queued")
                    // Skip error jobs (forget them)
                    if (status == "error" || status == "download_error") {
                        Log.d("Everyload", "Skipping error job ${obj.optString("jobId")}")
                        continue
                    }
                    val job = JobEntry(
                        jobId = obj.getString("jobId"),
                        url = obj.getString("url"),
                        status = status,
                        info = obj.optString("info", null).takeIf { it.isNotEmpty() },
                        localUri = obj.optString("localUri", null).takeIf { it.isNotEmpty() },
                        downloadTriggered = obj.optBoolean("downloadTriggered", false)
                    )
                    // restore files array
                    val filesArr = obj.optJSONArray("files")
                    if (filesArr != null) {
                        val list = mutableListOf<String>()
                        for (j in 0 until filesArr.length()) {
                            list.add(filesArr.getString(j))
                        }
                        job.files = list
                    }
                    jobs.add(job)
                }
                Log.d("Everyload", "Loaded ${jobs.size} jobs from SharedPreferences")
            }
        } catch (e: Exception) {
            Log.e("Everyload", "Error loading jobs: ${e.message}")
        }
    }

    private fun setupSwipeToDelete(recyclerView: RecyclerView) {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val job = jobs[position]
                showDeleteDialog(job, position)
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun showDeleteDialog(job: JobEntry, position: Int) {
        val hasLocalFile = job.localUri != null
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Usuń ${job.files?.firstOrNull() ?: job.jobId}?")
        
        if (hasLocalFile) {
            builder.setMessage("Co chcesz usunąć?")
            builder.setPositiveButton("Tylko z listy") { _, _ ->
                deleteJob(job, position, deleteFile = false)
            }
            builder.setNeutralButton("Z listy i telefonu") { _, _ ->
                deleteJob(job, position, deleteFile = true)
            }
            builder.setNegativeButton("Anuluj") { _, _ ->
                adapter.notifyItemChanged(position) // Restore the item
            }
        } else {
            builder.setMessage("Usunąć z listy?")
            builder.setPositiveButton("Tak") { _, _ ->
                deleteJob(job, position, deleteFile = false)
            }
            builder.setNegativeButton("Nie") { _, _ ->
                adapter.notifyItemChanged(position) // Restore the item
            }
        }
        
        builder.setOnCancelListener {
            adapter.notifyItemChanged(position) // Restore the item if dialog is dismissed
        }
        
        builder.show()
    }

    private fun deleteJob(job: JobEntry, position: Int, deleteFile: Boolean) {
        // Delete file from device if requested
        if (deleteFile && job.localUri != null) {
            try {
                val uri = Uri.parse(job.localUri)
                contentResolver.delete(uri, null, null)
                Log.d("Everyload", "Deleted file: ${job.localUri}")
                Toast.makeText(this, "Usunięto plik", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("Everyload", "Error deleting file: ${e.message}")
                Toast.makeText(this, "Błąd usuwania pliku: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Remove from list
        jobs.removeAt(position)
        adapter.notifyItemRemoved(position)
        saveJobs()
        updateEmptyState()
        
        Log.d("Everyload", "Removed job ${job.jobId} from list")
    }

    private fun updateEmptyState() {
        val emptyState = findViewById<View>(R.id.emptyStateView)
        val recycler = findViewById<RecyclerView>(R.id.jobsRecycler)
        
        if (jobs.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }
}