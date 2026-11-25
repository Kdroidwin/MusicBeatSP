package com.music.bitchord.data.sources.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Parses a raw module-index JSON body into a flat list of [SpineModule]s.
 *
 * Index servers organise modules under arbitrary "category:<key>" top-level
 * keys (e.g. "category:music", "category:debrid_modules",
 * "category:ricky_modules"). A fixed-field data class would silently drop
 * every module under an unrecognised key; scanning for any key that starts
 * with "category:" picks them up, up to [MAX_MODULES_PER_INDEX] distinct
 * modules — the same approach Convx takes, with a bound on the working set.
 */
object ModuleIndex {

    private val excludedCategories = setOf("category:artworks", "category:testing")
    /** Bound descriptors even when a valid 2 MiB index contains thousands of entries. */
    const val MAX_MODULES_PER_INDEX = 64

    fun parseModules(json: Json, body: String): List<SpineModule> {
        val obj = json.decodeFromString(JsonObject.serializer(), body)
        val modules = LinkedHashMap<String, SpineModule>()
        for ((category, value) in obj) {
            if (!category.startsWith("category:") || category in excludedCategories) continue
            val entries = value as? JsonArray ?: continue
            for (entry in entries) {
                if (modules.size >= MAX_MODULES_PER_INDEX) return modules.values.toList()
                runCatching { json.decodeFromJsonElement(SpineModule.serializer(), entry) }
                    .getOrNull()
                    ?.takeIf { it.id.isNotBlank() && it.id.length <= 256 }
                    ?.let { modules.putIfAbsent(it.id, it) }
            }
        }
        return modules.values.toList()
    }
}
