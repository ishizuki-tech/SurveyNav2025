/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: NodeMappers.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.util.Log
import com.negi.survey.BuildConfig
import com.negi.survey.config.NodeDTO
import java.util.Locale

private const val TAG = "NodeMappers"

/**
 * When true (in DEBUG builds), unknown node types will throw to surface config mistakes early.
 * In release builds, we always fall back to TEXT to avoid hard crashes from typos.
 */
private const val STRICT_UNKNOWN_NODE_TYPE_IN_DEBUG: Boolean = true

/**
 * Convert a configuration-layer [NodeDTO] into a ViewModel-layer [Node].
 *
 * Goals:
 * - Keep the config layer independent from VM/UI classes.
 * - Normalize inputs (trim, null-safety) to prevent downstream NPEs.
 * - Provide "debug visibility": log or throw when config appears suspicious.
 */
fun NodeDTO.toVmNode(): Node {
    val safeId = id.trim()

    // Fail fast in debug to catch broken configs early.
    if (BuildConfig.DEBUG) {
        require(safeId.isNotBlank()) { "NodeDTO.id is blank (raw='$id')" }
    }

    val vmType = resolveVmNodeType(type, nodeIdForLog = safeId)

    val safeTitle = title?.trim().orEmpty()
    val safeQuestion = question?.trim().orEmpty()

    val safeNextId = nextId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    val safeOptions = sanitizeOptions(options)

    // Heuristic validation to catch missing options for choice-like nodes.
    if (BuildConfig.DEBUG) {
        val typeName = vmType.name
        val looksChoiceLike =
            typeName.contains("CHOICE", ignoreCase = true) ||
                    typeName.contains("SELECT", ignoreCase = true) ||
                    typeName.contains("OPTION", ignoreCase = true)

        if (looksChoiceLike && safeOptions.isEmpty()) {
            Log.w(TAG, "Node[$safeId] type=$typeName looks choice-like but options is empty")
        }

        if (safeQuestion.isBlank()) {
            Log.w(TAG, "Node[$safeId] question is blank (type=${vmType.name})")
        }
    }

    return Node(
        id = safeId,
        type = vmType,
        title = safeTitle,
        question = safeQuestion,
        // Always pass a non-null list to avoid nullable propagation and UI crashes.
        // This compiles whether Node.options is List<String> or List<String>?
        options = safeOptions,
        nextId = safeNextId
    )
}

/**
 * Resolve VM-layer [NodeType] from a raw config type string with robust normalization.
 *
 * Supported normalization:
 * - trim
 * - camelCase -> SNAKE_CASE
 * - hyphen/space/dot -> underscore
 * - collapse multiple underscores
 *
 * Debug behavior:
 * - If STRICT_UNKNOWN_NODE_TYPE_IN_DEBUG is true, throw on unknown types in DEBUG builds.
 * - Otherwise (or in release), fall back to TEXT.
 */
private fun resolveVmNodeType(rawType: String?, nodeIdForLog: String): NodeType {
    val normalizedKey = rawType
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { toEnumKey(it) }

    if (normalizedKey == null) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "Node[$nodeIdForLog] type is null/blank -> fallback to TEXT")
        }
        return NodeType.TEXT
    }

    // First try direct valueOf.
    runCatching { NodeType.valueOf(normalizedKey) }
        .onSuccess { return it }

    // Second try common aliases/synonyms (best-effort).
    val alias = NODE_TYPE_ALIASES[normalizedKey]
    if (alias != null) {
        runCatching { NodeType.valueOf(alias) }
            .onSuccess {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Node[$nodeIdForLog] type='$rawType' normalized='$normalizedKey' mapped alias='$alias'")
                }
                return it
            }
    }

    // Unknown type: throw in debug (optional), fallback otherwise.
    val msg = "Node[$nodeIdForLog] unknown type='$rawType' normalized='$normalizedKey' -> fallback TEXT"
    if (BuildConfig.DEBUG && STRICT_UNKNOWN_NODE_TYPE_IN_DEBUG) {
        throw IllegalArgumentException(msg)
    }
    if (BuildConfig.DEBUG) Log.w(TAG, msg)
    return NodeType.TEXT
}

/**
 * Normalizes a raw type string into an enum-like key:
 * - "multipleChoice" -> "MULTIPLE_CHOICE"
 * - "multi-choice"   -> "MULTI_CHOICE"
 * - "  text  "       -> "TEXT"
 */
private fun toEnumKey(raw: String): String {
    val trimmed = raw.trim()

    // Insert underscores between lower->upper boundaries: "fooBar" -> "foo_Bar"
    val camelToSnake = buildString(trimmed.length + 8) {
        for (i in trimmed.indices) {
            val c = trimmed[i]
            val prev = if (i > 0) trimmed[i - 1] else null
            val next = if (i + 1 < trimmed.length) trimmed[i + 1] else null

            val boundary =
                prev != null &&
                        prev.isLetterOrDigit() &&
                        prev.isLowerCase() &&
                        c.isUpperCase() &&
                        (next == null || next.isLowerCase() || next.isDigit())

            if (boundary) append('_')
            append(c)
        }
    }

    // Replace separators with underscores and keep only [A-Za-z0-9_].
    val replaced = camelToSnake
        .replace('-', '_')
        .replace(' ', '_')
        .replace('.', '_')
        .replace('/', '_')

    // Collapse multiple underscores and trim underscores.
    val collapsed = replaced
        .replace(Regex("_+"), "_")
        .trim('_')

    return collapsed.uppercase(Locale.ROOT)
}

/**
 * Sanitizes options:
 * - null -> emptyList()
 * - trim each entry
 * - drop blank entries
 * - drop duplicates (stable)
 *
 * NOTE:
 * If NodeDTO.options is already non-null/non-empty, this still normalizes whitespace.
 */
private fun sanitizeOptions(raw: List<String>?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()

    val out = ArrayList<String>(raw.size)
    val seen = HashSet<String>(raw.size * 2)

    for (opt in raw) {
        val v = opt.trim()
        if (v.isBlank()) continue
        if (seen.add(v)) out.add(v)
    }
    return out
}

/**
 * Best-effort alias mapping for config type variants.
 *
 * Add aliases here as your config evolves, without changing NodeType enum names.
 * Keys and values should already be normalized enum keys (SNAKE_CASE, uppercase).
 */
private val NODE_TYPE_ALIASES: Map<String, String> = mapOf(
    // Common choice-like spellings
    "MULTIPLECHOICE" to "MULTIPLE_CHOICE",
    "MULTICHOICE" to "MULTI_CHOICE",
    "SINGLECHOICE" to "SINGLE_CHOICE",
    "SINGLESELECT" to "SINGLE_CHOICE",
    "MULTISELECT" to "MULTI_CHOICE",

    // Yes/No patterns
    "YESNO" to "YES_NO",
    "YES_NO" to "YES_NO",

    // Text input variants
    "TEXTINPUT" to "TEXT",
    "FREE_TEXT" to "TEXT",

    // Numeric variants
    "NUMBER" to "NUMERIC",
    "INTEGER" to "NUMERIC"
)
