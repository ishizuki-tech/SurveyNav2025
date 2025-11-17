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

/**
 * Convert a configuration-layer [NodeDTO] into a ViewModel-layer [Node].
 *
 * - Keeps the `config` package free from any dependency on the ViewModel layer.
 * - Provides a single place to evolve mapping rules (default types, field transforms).
 *
 * Fallback behavior:
 * - If [NodeDTO.type] cannot be mapped to [NodeType] via [NodeType.valueOf],
 *   the node defaults to [NodeType.TEXT].
 *
 * @receiver [NodeDTO] loaded from JSON/YAML configuration.
 * @return A [Node] instance suitable for use in the ViewModel layer.
 */
fun NodeDTO.toVmNode(): Node {
    val vmType = runCatching {
        NodeType.valueOf(type.uppercase())
    }.getOrElse {
        NodeType.TEXT
    }

    return Node(
        id = id,
        type = vmType,
        title = title,
        question = question,
        options = options,
        nextId = nextId
    )
}
