package com.devstrike.henosis.insights.internal

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Validates payloads against the published schema v1 (`schema/v1.json` at the repo root). */
internal object SchemaCheck {
    private val schemaFile: File by lazy {
        val path = System.getProperty("insights.schema") ?: "../../schema/v1.json"
        File(path).also { require(it.isFile) { "schema not found at ${it.absolutePath}" } }
    }

    val schemaJson: JSONObject by lazy { JSONObject(schemaFile.readText()) }

    private val schema: Schema by lazy {
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schemaFile.readText(), InputFormat.JSON)
    }

    fun errors(batchJson: String): List<String> = schema.validate(batchJson, InputFormat.JSON).map { it.toString() }

    /** Every property name the schema allows anywhere. */
    val allowedNames: Set<String> by lazy {
        val out = sortedSetOf<String>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("properties")?.let { props -> props.keys().forEach { out += it } }
                    node.keys().forEach { walk(node.get(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.get(i))
            }
        }
        walk(schemaJson)
        out
    }

    /** Every key in [payload], except the method names inside `counts` (they are values, not fields). */
    fun fieldNames(payload: JSONObject): Set<String> {
        val out = sortedSetOf<String>()
        fun walk(node: Any?, parentKey: String?) {
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    if (parentKey != "counts") out += key
                    walk(node.get(key), key)
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.get(i), parentKey)
            }
        }
        walk(payload, null)
        return out
    }
}
