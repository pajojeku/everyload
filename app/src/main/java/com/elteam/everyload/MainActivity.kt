package com.elteam.everyload

import android.content.Intent
import android.util.Log
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.database.Cursor
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import org.json.JSONObject
import com.elteam.everyload.model.JobEntry
import com.elteam.everyload.ui.JobAdapter
import java.util.Timer
import java.util.TimerTask
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import android.content.ContentValues
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.os.Handler
import android.os.Looper
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    // Configure this to the host where you run the yt-dlp server.
    // If testing on the Android emulator, use 10.0.2.2 to reach localhost of host machine.
    // For Genymotion use 10.0.3.2. For a real device use your host LAN IP or adb reverse.
    private val SERVER_URL = "http://10.0.2.2:5000/download"
    private val httpClient = OkHttpClient()
    private val jobs = mutableListOf<JobEntry>()
    private lateinit var adapter: JobAdapter
    
    companion object {
        private const val PREFS_NAME = "everyload_prefs"
        private const val KEY_JOBS = "jobs_json"
    }
    private lateinit var downloadManager: DownloadManager

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == -1L) return
            val idx = jobs.indexOfFirst { it.downloadId == id }
            if (idx >= 0) {
                val job = jobs[idx]
                val q = DownloadManager.Query().setFilterById(id)
                val cursor: Cursor = downloadManager.query(q)
                try {
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uri = downloadManager.getUriForDownloadedFile(id)
                            job.localUri = uri?.toString()
                            job.status = "downloaded"
                        } else {
                            job.status = "download_error"
                        }
                        job.downloadId = null
                        adapter.upsert(job)
                    }
                } finally {
                    cursor.close()
                }
            }
        }
    }

    private fun startPolling(jobId: String, intervalMs: Long = 5000L) {
        // avoid duplicate pollers
        if (pollers.containsKey(jobId)) return
        Log.d("Everyload", "startPolling $jobId interval=$intervalMs")
        val timer = Timer()
        val task = object : TimerTask() {
            override fun run() {
                checkStatus(jobId, false)
            }
        }
        timer.scheduleAtFixedRate(task, intervalMs, intervalMs)
        pollers[jobId] = timer
    }

    private fun stopPolling(jobId: String) {
        Log.d("Everyload", "stopPolling $jobId")
        val t = pollers.remove(jobId)
        t?.cancel()
    }
    // Pollers per job to auto-check status
    private val pollers: MutableMap<String, Timer> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Load saved jobs from SharedPreferences
        loadJobs()

        // Handle share intents when activity is first created
        handleSendIntent(intent)

        // Setup RecyclerView
        val recycler = findViewById<RecyclerView>(R.id.jobsRecycler)
        adapter = JobAdapter(jobs, { job ->
            // onClick: if finished and server has file, download to device; if already downloaded, open local file
            if (job.status == "finished") {
                val remoteFiles = job.files
                if (!remoteFiles.isNullOrEmpty()) {
                    val filename = remoteFiles[0]
                    val fileUrl = SERVER_URL.replace("/download", "/file/${job.jobId}")
                    // guard to avoid double-trigger
                    if (!job.downloadTriggered && job.localUri == null) {
                        job.downloadTriggered = true
                        adapter.upsert(job)
                        // download via OkHttp into app storage
                        downloadFileWithOkHttp(job, fileUrl, filename)
                    }
                } else {
                    // no remote file known yet; check status to refresh
                    checkStatus(job.jobId)
                }
            } else if (job.status == "downloaded") {
                // open localUri
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(job.localUri), "video/*")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
            } else {
                // Otherwise allow checking status
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
        
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // register with explicit exported flag on API 33+; fall back to older overload on earlier SDKs
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // older overload
            //registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // ignore if not registered
        }
        // cancel any pollers
        for ((_, t) in pollers) t.cancel()
        pollers.clear()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the intent and handle the new share
        setIntent(intent)
        handleSendIntent(intent)
    }

    // If another app shares text (link), send it to the yt-dlp server which will perform the download
    private fun handleSendIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                // Build JSON payload {"url":"..."}
                val escaped = sharedText.replace("\"", "\\\"")
                val json = "{\"url\":\"$escaped\"}"
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(SERVER_URL)
                    .post(body)
                    .build()

                // Capture the original shared URL so we can attach it to the new job entry
                val originalUrl = sharedText

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val msg = "Błąd połączenia: ${e.message}"
                        runOnUiThread {
                            // show both a Toast and a blocking alert so user notices
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            showErrorDialog(msg)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val bodyStr = try { response.body?.string() } catch (e: Exception) { null }
                        val msg = if (response.isSuccessful) {
                            "Pobieranie rozpoczęte"
                        } else {
                            "Serwer zwrócił błąd: ${response.code}"
                        }
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            if (!response.isSuccessful) showErrorDialog(msg)
                            // if the server returned a job id, add to list with original URL
                            handleServerAccepted(bodyStr, originalUrl)
                        }
                        response.close()
                    }
                })
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

    // Parse server response to get job_id and offer quick status check
    private fun handleServerAccepted(responseBody: String?, originalUrl: String? = null) {
        if (responseBody.isNullOrEmpty()) return
        try {
            val json = JSONObject(responseBody)
            val jobId = json.optString("job_id", null)
            if (jobId != null) {
                // add to list; attach the original URL if provided
                val entry = JobEntry(jobId = jobId, url = originalUrl ?: "(sent)", status = "queued")
                jobs.add(0, entry)
                runOnUiThread {
                    adapter.upsert(entry)
                    // Simple toast notification, no blocking dialog
                    Toast.makeText(this, "Dodano do kolejki", Toast.LENGTH_SHORT).show()
                    // start polling status every 5 seconds until finished
                    startPolling(jobId)
                }
            }
        } catch (e: Exception) {
            // ignore parsing errors
        }
    }

    private fun checkStatus(jobId: String, showDialog: Boolean = true) {
        Log.d("Everyload", "checkStatus request for $jobId showDialog=$showDialog")
        val statusUrl = SERVER_URL.replace("/download", "/status/$jobId")
        val request = Request.Builder().url(statusUrl).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val msg = "Błąd połączenia (status): ${e.message}"
                runOnUiThread { showErrorDialog(msg) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try { response.body?.string() } catch (e: Exception) { null }
                val msg = if (body != null) {
                    try {
                        val json = JSONObject(body)
                        val status = json.optString("status", "unknown")
                        val out = json.optString("output", json.optString("error", ""))
                        "Status: $status\n${if (out.isNotEmpty()) "Info: $out" else ""}"
                    } catch (e: Exception) {
                        "Nie można sparsować odpowiedzi serwera"
                    }
                } else {
                    "Pusta odpowiedź serwera"
                }
                runOnUiThread {
                    // Update local job entry if exists
                    try {
                        val json = JSONObject(body ?: "{}")
                        val status = json.optString("status", "unknown")
                        val out = json.optString("output", json.optString("error", ""))
                        // Use the jobId parameter (not parsed from JSON, server doesn't include it)
                        val idx = jobs.indexOfFirst { it.jobId == jobId }
                        if (idx >= 0) {
                            val e = jobs[idx]
                            Log.d("Everyload", "checkStatus got status=$status for job=$jobId")
                            val prevStatus = e.status
                            e.status = status
                            e.info = out
                            // update files array if present
                            val filesArr = json.optJSONArray("files")
                            if (filesArr != null) {
                                val list = mutableListOf<String>()
                                for (i in 0 until filesArr.length()) {
                                    list.add(filesArr.optString(i))
                                }
                                e.files = list
                            }
                            adapter.upsert(e)
                            // notify user when status changes
                            if (prevStatus != status) {
                                val toastMsg = when (status) {
                                    "finished" -> "Zadanie ${jobId} zakończone"
                                    "error" -> "Błąd zadania ${jobId}: $out"
                                    "downloading" -> "Serwer pobiera: ${jobId}"
                                    else -> "Status ${status} dla ${jobId}"
                                }
                                Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_LONG).show()
                            }
                            // If finished, stop polling and start a one-time download if not already present
                            if (status == "finished") {
                                // stop polling first
                                stopPolling(jobId)
                                Log.d("Everyload", "status finished for $jobId; scheduling download")
                                // only start download once
                                if (!e.downloadTriggered && e.localUri == null) {
                                    e.downloadTriggered = true
                                    adapter.upsert(e)
                                    val filename = e.files?.firstOrNull()
                                    val fileUrl = SERVER_URL.replace("/download", "/file/${e.jobId}")
                                    // pass nullable filename; downloader will infer if null
                                    downloadFileWithOkHttp(e, fileUrl, filename)
                                } else {
                                    Log.d("Everyload", "download already triggered or localUri exists for $jobId")
                                }
                            }
                        } else {
                            // job not found locally, add it
                            val e = JobEntry(jobId = jobId, url = "(unknown)", status = status, info = out)
                            jobs.add(0, e)
                            adapter.upsert(e)
                        }
                    } catch (e: Exception) {
                        Log.e("Everyload", "Exception parsing status response: ${e.message}")
                    }
                    if (showDialog) {
                        AlertDialog.Builder(this@MainActivity).setTitle("Status").setMessage(msg).setPositiveButton("OK") { d, _ -> d.dismiss() }.show()
                    }
                }
                response.close()
            }
        })
    }

    // Download a file from the server using OkHttp and save into MediaStore (Downloads) or app-specific files as fallback.
    private fun downloadFileWithOkHttp(job: JobEntry, fileUrl: String, filename: String?) {
        // Destination: app external files Downloads directory (fallback)
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            runOnUiThread {
                Toast.makeText(this, "Brak dostępu do katalogu pobierania", Toast.LENGTH_LONG).show()
            }
            return
        }
        // Create a fallback file object (name will be determined after response if needed)
        val fallbackName = filename ?: "everyload_${job.jobId}"
        val outFile = File(downloadsDir, sanitizeFilename(fallbackName))

    // Update UI state and guard
    job.status = "downloading_local"
    job.downloadTriggered = true
    adapter.upsert(job)
    Log.d("Everyload", "Starting download for job=${job.jobId}, url=$fileUrl")

    val request = Request.Builder().url(fileUrl).get().build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    job.status = "download_error"
                    adapter.upsert(job)
                    Toast.makeText(this@MainActivity, "Błąd pobierania: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        job.status = "download_error"
                        adapter.upsert(job)
                        Toast.makeText(this@MainActivity, "Serwer zwrócił błąd przy pobieraniu: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    response.close()
                    return
                }

                var fos: FileOutputStream? = null
                val body = response.body
                if (body == null) {
                    runOnUiThread {
                        job.status = "download_error"
                        adapter.upsert(job)
                        Toast.makeText(this@MainActivity, "Puste ciało odpowiedzi serwera", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                // Decide a filename: prefer Content-Disposition header, then server-provided filename, then fallback
                val resolver = applicationContext.contentResolver
                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                val cd = response.header("Content-Disposition")
                var chosenName: String? = null
                if (cd != null) {
                    // try to parse filename="..."
                    val regex = Regex("filename=\"?([^\";]+)\"?")
                    val m = regex.find(cd)
                    if (m != null) chosenName = m.groupValues[1]
                }
                if (chosenName == null && !filename.isNullOrEmpty()) chosenName = filename
                // ensure extension from content type if missing
                if (chosenName != null && !chosenName.contains('.')) {
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType)
                    if (!ext.isNullOrEmpty()) chosenName = "$chosenName.$ext"
                }
                val safeName = sanitizeFilename(chosenName ?: fallbackName)
                // Try writing into MediaStore (Downloads) so file appears in system Downloads app
                var dstUri = try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, contentType)
                        // Write to Downloads/Everyload folder
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Everyload")
                    }
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } catch (e: Exception) {
                    null
                }

                try {
                    if (dstUri != null) {
                        // Stream directly into MediaStore
                        val outStream = resolver.openOutputStream(dstUri)
                        if (outStream == null) throw Exception("Nie można otworzyć strumienia do MediaStore")
                        val input = body.byteStream()
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (true) {
                            read = input.read(buffer)
                            if (read == -1) break
                            outStream.write(buffer, 0, read)
                        }
                        outStream.flush()
                        outStream.close()
                        // Update job and UI on main thread
                        Handler(Looper.getMainLooper()).post {
                            job.localUri = dstUri.toString()
                            job.status = "downloaded"
                            adapter.upsert(job)
                            Toast.makeText(this@MainActivity, "Pobrano do Downloads: ${safeName}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // Fallback to app external files directory
                        fos = FileOutputStream(outFile)
                        val input = body.byteStream()
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (true) {
                            read = input.read(buffer)
                            if (read == -1) break
                            fos.write(buffer, 0, read)
                        }
                        fos.flush()
                        val authority = "${applicationContext.packageName}.provider"
                        val contentUri = FileProvider.getUriForFile(applicationContext, authority, outFile)
                        Handler(Looper.getMainLooper()).post {
                            job.localUri = contentUri.toString()
                            job.status = "downloaded"
                            adapter.upsert(job)
                            Toast.makeText(this@MainActivity, "Pobrano (lokalnie): ${outFile.name}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    // If MediaStore write fails, attempt to clean up inserted record
                    try {
                        if (dstUri != null) resolver.delete(dstUri, null, null)
                    } catch (ignore: Exception) {}
                    runOnUiThread {
                        job.status = "download_error"
                        adapter.upsert(job)
                        Toast.makeText(this@MainActivity, "Błąd zapisu pliku: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    try { fos?.close() } catch (ignore: Exception) {}
                    response.close()
                }
            }
        })
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
        // Stop polling if active
        stopPolling(job.jobId)
        
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