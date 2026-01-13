package com.elteam.everyload.model

import java.io.Serializable

/**
 * Represents a known portal/source (e.g. YouTube, Vimeo)
 */
data class Portal(
    val id: String,
    val name: String,
    val domains: List<String>,
    val example: String? = null,
    val addedAt: String
) : Serializable {
    // Drobne uzupełnienie: identyfikator wersji serializacji (opcjonalne)
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
