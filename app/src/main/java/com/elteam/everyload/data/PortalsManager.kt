package com.elteam.everyload.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.elteam.everyload.model.Domain
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.time.Instant

/**
 * Simple manager for storing known domains in SharedPreferences as JSON.
 * Keeps a list of domains and provides helpers to find or add domains by host.
 */
class DomainsManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "everyload_domains"
        private const val KEY_DOMAINS = "domains_json"
        private const val TAG = "DomainsManager"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val domains = mutableListOf<Domain>()

    init {
        loadDomains()
    }

    fun getAllDomains(): List<Domain> = domains.toList()

    fun removeDomainById(id: String) {
        val idx = domains.indexOfFirst { it.id == id }
        if (idx >= 0) {
            domains.removeAt(idx)
            saveDomains()
            Log.d(TAG, "Removed domain with id: $id")
        }
    }

    fun findByDomain(domain: String): Domain? {
        val d = normalizeDomain(domain)
        return domains.firstOrNull { p -> p.domains.any { normalizeDomain(it) == d } }
    }

    fun addDomain(name: String, domainsList: List<String>, example: String? = null): Domain {
        val normalized = domainsList.map { normalizeDomain(it) }.distinct()
        // If exists, return existing
        normalized.firstOrNull()?.let { host ->
            val existing = findByDomain(host)
            if (existing != null) return existing
        }

        val domain = Domain(
            id = UUID.randomUUID().toString(),
            name = name.takeIf { it.isNotBlank() } ?: domainsList.firstOrNull() ?: "unknown",
            domains = normalized,
            example = example,
            addedAt = Instant.now().toString()
        )
        domains.add(domain)
        saveDomains()
        Log.d(TAG, "Added domain: ${domain.name} (${domain.domains.joinToString()})")
        return domain
    }

    private fun saveDomains() {
        try {
            val arr = JSONArray()
            domains.forEach { p ->
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("domains", JSONArray(p.domains))
                    p.example?.let { put("example", it) }
                    put("addedAt", p.addedAt)
                }
                arr.put(obj)
            }
            prefs.edit().putString(KEY_DOMAINS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save domains", e)
        }
    }

    private fun loadDomains() {
        try {
            val json = prefs.getString(KEY_DOMAINS, "[]") ?: "[]"
            val arr = JSONArray(json)
            domains.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val d = if (obj.has("domains")) {
                    val a = obj.getJSONArray("domains")
                    List(a.length()) { idx -> a.getString(idx) }
                } else listOf()
                val domain = Domain(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = obj.optString("name", d.firstOrNull() ?: "unknown"),
                    domains = d,
                    example = if (obj.has("example")) obj.optString("example") else null,
                    addedAt = obj.optString("addedAt", "")
                )
                domains.add(domain)
            }
            Log.d(TAG, "Loaded ${domains.size} domains from prefs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load domains", e)
        }
    }

    private fun normalizeDomain(domain: String): String {
        var d = domain.trim().lowercase()
        // If full URL, extract host
        if (d.startsWith("http://") || d.startsWith("https://")) {
            try {
                val u = java.net.URI(d)
                u.host?.let { d = it }
            } catch (_: Exception) {
            }
        }
        if (d.startsWith("www.")) d = d.removePrefix("www.")
        // Remove any path
        d = d.split("/")[0]
        return d
    }
}
