package com.elteam.everyload.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.elteam.everyload.model.JobEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized manager for all job operations with proper persistence and thread-safety.
 * This class ensures that jobs are tracked by stable IDs and updates are atomic.
 */
class JobsManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "everyload_jobs"
        private const val KEY_JOBS = "jobs_json"
        private const val TAG = "JobsManager"
    }
    
    // Thread-safe storage using jobId as key
    private val jobsMap = ConcurrentHashMap<String, JobEntry>()
    
    // Ordered list of job IDs for display order (newest first)
    private val jobOrder = mutableListOf<String>()
    
    // Listeners for job changes
    private val changeListeners = mutableListOf<JobChangeListener>()
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    interface JobChangeListener {
        fun onJobAdded(job: JobEntry, position: Int)
        fun onJobUpdated(job: JobEntry, position: Int)
        fun onJobRemoved(jobId: String, position: Int)
        fun onAllJobsCleared()
    }
    
    init {
        loadJobs()
    }
    
    /**
     * Generate a unique job ID
     */
    fun generateJobId(prefix: String = "job"): String {
        return "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }
    
    /**
     * Add a new job at the top of the list
     */
    @Synchronized
    fun addJob(job: JobEntry): Boolean {
        if (jobsMap.containsKey(job.jobId)) {
            Log.w(TAG, "Job with ID ${job.jobId} already exists, updating instead")
            return updateJob(job)
        }
        
        jobsMap[job.jobId] = job
        jobOrder.add(0, job.jobId)
        
        saveJobs()
        notifyJobAdded(job, 0)
        
        Log.d(TAG, "Added job ${job.jobId} at position 0")
        return true
    }
    
    /**
     * Update an existing job
     */
    @Synchronized
    fun updateJob(job: JobEntry): Boolean {
        if (!jobsMap.containsKey(job.jobId)) {
            Log.w(TAG, "Job with ID ${job.jobId} does not exist, adding instead")
            return addJob(job)
        }
        
        jobsMap[job.jobId] = job
        val position = jobOrder.indexOf(job.jobId)
        
        saveJobs()
        notifyJobUpdated(job, position)
        
        Log.d(TAG, "Updated job ${job.jobId} at position $position")
        return true
    }
    
    /**
     * Update job status and optional info
     */
    @Synchronized
    fun updateJobStatus(jobId: String, status: String, info: String? = null): Boolean {
        val job = jobsMap[jobId] ?: return false
        
        val updatedJob = job.copy(
            status = status,
            info = info ?: job.info
        )
        
        return updateJob(updatedJob)
    }
    
    /**
     * Remove a job by ID
     */
    @Synchronized
    fun removeJob(jobId: String): Boolean {
        val position = jobOrder.indexOf(jobId)
        if (position < 0) {
            Log.w(TAG, "Job with ID $jobId not found in order list")
            return false
        }
        
        jobsMap.remove(jobId)
        jobOrder.removeAt(position)
        
        saveJobs()
        notifyJobRemoved(jobId, position)
        
        Log.d(TAG, "Removed job $jobId from position $position")
        return true
    }
    
    /**
     * Clear all jobs
     */
    @Synchronized
    fun clearAllJobs() {
        jobsMap.clear()
        jobOrder.clear()
        
        saveJobs()
        notifyAllJobsCleared()
        
        Log.d(TAG, "Cleared all jobs")
    }
    
    /**
     * Get a job by ID
     */
    fun getJob(jobId: String): JobEntry? {
        return jobsMap[jobId]
    }
    
    /**
     * Get all jobs in display order (newest first)
     */
    fun getAllJobs(): List<JobEntry> {
        return jobOrder.mapNotNull { jobsMap[it] }
    }
    
    /**
     * Get jobs by status
     */
    fun getJobsByStatus(status: String): List<JobEntry> {
        return jobOrder.mapNotNull { jobsMap[it] }.filter { it.status == status }
    }
    
    /**
     * Get count of jobs
     */
    fun getJobCount(): Int {
        return jobOrder.size
    }
    
    /**
     * Check if a job exists
     */
    fun hasJob(jobId: String): Boolean {
        return jobsMap.containsKey(jobId)
    }
    
    /**
     * Get position of a job
     */
    fun getJobPosition(jobId: String): Int {
        return jobOrder.indexOf(jobId)
    }
    
    /**
     * Add a change listener
     */
    fun addChangeListener(listener: JobChangeListener) {
        changeListeners.add(listener)
    }
    
    /**
     * Remove a change listener
     */
    fun removeChangeListener(listener: JobChangeListener) {
        changeListeners.remove(listener)
    }
    
    /**
     * Save jobs to SharedPreferences
     */
    @Synchronized
    private fun saveJobs() {
        try {
            val jsonArray = JSONArray()
            
            // Save in display order
            for (jobId in jobOrder) {
                val job = jobsMap[jobId] ?: continue
                
                // Skip transient downloading states
                if (job.status == "downloading" && job.info.isNullOrEmpty()) {
                    continue
                }
                
                val json = JSONObject().apply {
                    put("jobId", job.jobId)
                    put("url", job.url)
                    put("status", job.status)
                    put("downloadTriggered", job.downloadTriggered)
                    
                    job.info?.let { put("info", it) }
                    job.title?.let { put("title", it) }
                    job.localUri?.let { put("localUri", it) }
                    job.downloadId?.let { put("downloadId", it) }
                    
                    job.files?.let { files ->
                        put("files", JSONArray(files))
                    }
                }
                
                jsonArray.put(json)
            }
            
            prefs.edit()
                .putString(KEY_JOBS, jsonArray.toString())
                .apply()
            
            Log.d(TAG, "Saved ${jsonArray.length()} jobs to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save jobs", e)
        }
    }
    
    /**
     * Load jobs from SharedPreferences
     */
    @Synchronized
    private fun loadJobs() {
        try {
            val jobsJson = prefs.getString(KEY_JOBS, "[]") ?: "[]"
            val jsonArray = JSONArray(jobsJson)
            
            jobsMap.clear()
            jobOrder.clear()
            
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
                    info = json.optString("info").takeIf { it.isNotEmpty() },
                    title = json.optString("title").takeIf { it.isNotEmpty() },
                    files = files,
                    localUri = json.optString("localUri").takeIf { it.isNotEmpty() },
                    downloadId = if (json.has("downloadId")) json.getLong("downloadId") else null,
                    downloadTriggered = json.optBoolean("downloadTriggered", false)
                )
                
                jobsMap[job.jobId] = job
                jobOrder.add(job.jobId)
            }
            
            Log.d(TAG, "Loaded ${jobsMap.size} jobs from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load jobs", e)
        }
    }
    
    /**
     * Filter jobs by optional query (title/url/files), extensions and portal domains.
     * All non-null filters are AND'ed together.
     */
    fun filterJobs(query: String? = null, extensions: List<String>? = null, domains: List<String>? = null): List<JobEntry> {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val exts = extensions?.map { it.trim().lowercase().removePrefix(".") }?.filter { it.isNotEmpty() }?.toSet()
        val domainSet = domains?.map { normalizeHost(it) }?.toSet()

        return jobOrder.mapNotNull { jobsMap[it] }.filter { job ->
            // Query filter - check title, url, files
            val queryMatch = if (q == null) true else {
                val titleMatch = job.title?.lowercase()?.contains(q) ?: false
                val urlMatch = job.url.lowercase().contains(q)
                val filesMatch = job.files?.any { it.lowercase().contains(q) } ?: false
                titleMatch || urlMatch || filesMatch
            }

            if (!queryMatch) return@filter false

            // Extensions filter
            if (exts != null && exts.isNotEmpty()) {
                val jobExts = mutableSetOf<String>()
                job.files?.forEach { fn ->
                    extractExtension(fn)?.let { jobExts.add(it) }
                }
                extractExtension(job.url)?.let { jobExts.add(it) }
                job.localUri?.let { extractExtension(it)?.let { e -> jobExts.add(e) } }

                if (jobExts.none { exts.contains(it) }) return@filter false
            }

            // Domain filter
            if (domainSet != null && domainSet.isNotEmpty()) {
                val host = extractHost(job.url)?.let { normalizeHost(it) }
                if (host == null) return@filter false
                if (domainSet.none { host.endsWith(it) || host == it }) return@filter false
            }

            true
        }
    }

    private fun extractHost(url: String): String? {
        return try {
            val u = java.net.URI(url)
            u.host
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeHost(host: String): String {
        var h = host.trim().lowercase()
        if (h.startsWith("www.")) h = h.removePrefix("www.")
        h = h.split("/")[0]
        return h
    }

    private fun extractExtension(path: String?): String? {
        if (path == null) return null
        val p = path.substringAfterLast('/', path)
        val lastDot = p.lastIndexOf('.')
        if (lastDot < 0 || lastDot == p.length - 1) return null
        return p.substring(lastDot + 1).lowercase()
    }

    // Notification methods
    private fun notifyJobAdded(job: JobEntry, position: Int) {
        changeListeners.forEach { it.onJobAdded(job, position) }
    }

    private fun notifyJobUpdated(job: JobEntry, position: Int) {
        changeListeners.forEach { it.onJobUpdated(job, position) }
    }

    private fun notifyJobRemoved(jobId: String, position: Int) {
        changeListeners.forEach { it.onJobRemoved(jobId, position) }
    }

    private fun notifyAllJobsCleared() {
        changeListeners.forEach { it.onAllJobsCleared() }
    }
}
