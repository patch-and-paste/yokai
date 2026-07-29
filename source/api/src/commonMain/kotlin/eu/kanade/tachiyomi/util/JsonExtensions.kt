package eu.kanade.tachiyomi.util

import kotlinx.serialization.json.JsonObject

private val EmptyJsonObject = JsonObject(emptyMap())

/**
 * Shared empty [JsonObject], used as the default value of the `memo` fields.
 */
val JsonObject.Companion.EMPTY: JsonObject
    get() = EmptyJsonObject
