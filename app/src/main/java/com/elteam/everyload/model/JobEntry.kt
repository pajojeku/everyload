package com.elteam.everyload.model

import java.io.Serializable

/**
 * Represents a download job.
 * This is now immutable (data class with val) to prevent accidental modifications.
 * Use copy() to create updated versions.
 */
data class JobEntry(
    val jobId: String,
    val url: String,
    val status: String = "queued",
    val info: String? = null,
    val title: String? = null,  // Video title for playlists
    val files: List<String>? = null,
    val localUri: String? = null,
    val downloadId: Long? = null,
    // Guard to ensure we trigger a single download per job from the client
    val downloadTriggered: Boolean = false
) : Serializable {
    
    /**
     * Get a display title for this job
     */
    fun getDisplayTitle(): String {
        return title ?: files?.firstOrNull() ?: jobId
    }
    
    /**
     * Get a display URL for this job
     */
    fun getDisplayUrl(): String {
        return localUri ?: url
    }
    
    /**
     * Check if this job is in a final state
     */
    fun isFinalState(): Boolean {
        return status in listOf("downloaded", "error", "download_error", "stopped")
    }
    
    /**
     * Check if this job is active
     */
    fun isActive(): Boolean {
        return status in listOf("downloading", "downloading_local", "running", "extracting", "queued", "pending")
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JobEntry) return false
        return jobId == other.jobId
    }
    
    override fun hashCode(): Int {
        return jobId.hashCode()
    }
}

