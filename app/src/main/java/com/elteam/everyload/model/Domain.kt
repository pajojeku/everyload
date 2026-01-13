package com.elteam.everyload.model

import java.io.Serializable

/**
 * Represents a known domain/source (e.g. youtube.com)
 */
data class Domain(
    val id: String,
    val name: String,
    val domains: List<String>,
    val example: String? = null,
    val addedAt: String
) : Serializable

