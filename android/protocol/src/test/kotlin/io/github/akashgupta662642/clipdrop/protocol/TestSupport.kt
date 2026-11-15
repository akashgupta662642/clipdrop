package io.github.akashgupta662642.clipdrop.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd-length hex" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Loads the shared golden vectors from docs/test-vectors (path set by Gradle). */
internal object TestVectors {
    private val dir: File by lazy {
        File(System.getProperty("clipdrop.testVectors") ?: error("clipdrop.testVectors system property not set"))
    }

    fun file(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8)).jsonObject

    fun vectors(name: String): List<JsonObject> = file(name).getValue("vectors").jsonArray.map { it.jsonObject }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.stringOrNull(key: String): String? = get(key)?.jsonPrimitive?.content
internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
