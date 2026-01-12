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

package com.negi.survey.vm

import com.negi.survey.config.NodeDTO
import java.util.Locale

/**
 * Convert a configuration-layer [NodeDTO] into a ViewModel-layer [Node].
 *
 * This mapper:
 * - Keeps the `config` package free from any dependency on the ViewModel layer.
 * - Centralizes mapping rules (default types, field transforms, null safety).
 *
 * Fallback behavior:
 * - If [NodeDTO.type] is null/blank or cannot be mapped to [NodeType],
 *   the node defaults to [NodeType.TEXT].
 *
 * Debug behavior:
 * - This overload is silent. Use [toVmNode] with [debug] to observe fallbacks.
 */
fun NodeDTO.toVmNode(): Node = toVmNode(debug = null)

/**
 * Convert a configuration-layer [NodeDTO] into a ViewModel-layer [Node], with an optional debug hook.
 *
 * @param debug Optional callback for emitting non-fatal mapping diagnostics.
 *              This avoids hard dependency on android.util.Log in VM code.
 */
fun NodeDTO.toVmNode(
    debug: ((String) -> Unit)?
): Node {
    val vmType = resolveVmNodeType(rawType = type, nodeId = id, debug = debug)

    val safeId = id.trim()
    if (safeId.isBlank()) {
        // Keeping behavior non-fatal to avoid crashing on bad configs,
        // but we still want this to be highly visible in debug.
        debug?.invoke("NodeMappers: blank node id detected (type=${type.orEmpty()})")
    }

    val safeTitle = title.normalizeText()
    val safeQuestion = question.normalizeText()

    // Normalize options defensively.
    // - If Node.options is nullable, this is still safe (it becomes a non-null list).
    // - If Node.options is non-null, this prevents accidental NPEs downstream.
    val safeOptions = options.normalizeOptions()

    val safeNextId = nextId?.trim()?.takeIf { it.isNotBlank() }

    return Node(
        id = safeId,
        type = vmType,
        title = safeTitle,
        question = safeQuestion,
        options = safeOptions,
        nextId = safeNextId
    )
}

/**
 * Resolve the ViewModel-layer [NodeType] from a config-layer raw type string.
 *
 * @param rawType Raw node type from configuration.
 * @param nodeId Node id (for better diagnostics).
 * @param debug Optional diagnostic sink.
 * @return A normalized [NodeType] with safe fallback.
 */
private fun resolveVmNodeType(
    rawType: String?,
    nodeId: String,
    debug: ((String) -> Unit)?
): NodeType {
    val normalized = rawType
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.uppercase(Locale.ROOT)

    if (normalized == null) {
        debug?.invoke("NodeMappers: node=$nodeId has null/blank type, defaulting to TEXT")
        return NodeType.TEXT
    }

    return runCatching {
        NodeType.valueOf(normalized)
    }.getOrElse {
        debug?.invoke("NodeMappers: node=$nodeId has unknown type='$rawType' (normalized='$normalized'), defaulting to TEXT")
        NodeType.TEXT
    }
}

/* ============================================================
 * Normalization helpers
 * ============================================================ */

/**
 * Normalize human-facing text fields:
 * - Trim leading/trailing whitespace
 * - Collapse CRLF to LF
 *
 * Note:
 * - Intentionally does NOT collapse internal whitespace, since some prompts/questions
 *   may be formatting-sensitive.
 */
private fun String?.normalizeText(): String {
    return this
        ?.replace("\r\n", "\n")
        ?.trim()
        .orEmpty()
}

/**
 * Normalize options list defensively.
 *
 * Rules:
 * - Null => empty list
 * - Trim each option
 * - Drop blank options
 * - Preserve original ordering
 */
private fun List<String>?.normalizeOptions(): List<String> {
    if (this.isNullOrEmpty()) return emptyList()

    val out = ArrayList<String>(this.size)
    for (opt in this) {
        val t = opt.trim()
        if (t.isNotBlank()) out.add(t)
    }
    return out
}
