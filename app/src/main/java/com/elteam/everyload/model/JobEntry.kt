package com.elteam.everyload.model

data class JobEntry(
    val jobId: String,
    val url: String,
    var status: String = "queued",
    var info: String? = null,
    var files: List<String>? = null,
    var localUri: String? = null,
    var downloadId: Long? = null,
    // Guard to ensure we trigger a single download per job from the client
    var downloadTriggered: Boolean = false
)

