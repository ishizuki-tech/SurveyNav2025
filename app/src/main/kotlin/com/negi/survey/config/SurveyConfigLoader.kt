/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyConfigLoader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Strongly-typed survey configuration model and loader.
 *  Supports JSON and YAML formats, SLM metadata, model defaults,
 *  and structural validation for graph-based survey flows.
 *
 *  Features:
 *   • Typed prompts, graph, SLM runtime metadata, and model defaults
 *   • JSON/YAML auto-detection with BOM and newline normalization
 *   • Config-level graph/SLM/model-defaults validation
 *   • Backward-compatible type aliases for legacy call sites
 * =====================================================================
 */

package com.negi.survey.config

import android.content.Context
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import java.nio.charset.Charset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Top-level configuration model for a survey.
 *
 * Aggregates the prompt table, graph structure, SLM metadata, and model
 * defaults that describe how a survey should be executed at runtime.
 */
@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph,
    val slm: SlmMeta = SlmMeta(),
    @SerialName("model_defaults") val modelDefaults: ModelDefaults = ModelDefaults()
) {

    // ---------------------------------------------------------------------
    // prompts
    // ---------------------------------------------------------------------

    /**
     * A single prompt template entry associated with a specific graph node.
     *
     * The template string can contain placeholders such as {{QUESTION}},
     * {{ANSWER}}, and {{NODE_ID}} which will be resolved by the ViewModel.
     */
    @Serializable
    data class Prompt(
        val nodeId: String,
        val prompt: String
    )

    // ---------------------------------------------------------------------
    // graph
    // ---------------------------------------------------------------------

    /**
     * Graph definition for the survey flow.
     *
     * The graph is defined by an entry-point node ID ([startId]) and
     * a flat list of [NodeDTO] instances that describe each node.
     */
    @Serializable
    data class Graph(
        val startId: String,
        val nodes: List<NodeDTO>
    )

    // ---------------------------------------------------------------------
    // SLM metadata
    // ---------------------------------------------------------------------

    /**
     * SLM runtime parameters and system-prompt metadata.
     *
     * All fields are optional. Snake_case field names have explicit
     * [SerialName] annotations to keep YAML/JSON key resolution stable
     * even if the Kotlin property names change in the future.
     */
    @Serializable
    data class SlmMeta(
        // --- runtime params (optional) ---

        /** Preferred accelerator type, "CPU" or "GPU". */
        @SerialName("accelerator") val accelerator: String? = null,

        /** Maximum number of tokens to generate per call. */
        @SerialName("max_tokens") val maxTokens: Int? = null,

        /** Top-k sampling parameter. */
        @SerialName("top_k") val topK: Int? = null,

        /** Top-p (nucleus) sampling parameter. */
        @SerialName("top_p") val topP: Double? = null,

        /** Temperature parameter for sampling. */
        @SerialName("temperature") val temperature: Double? = null,

        // --- meta/system prompt pieces (optional) ---

        /** Prefix prepended before user turns, if any. */
        @SerialName("user_turn_prefix") val user_turn_prefix: String? = null,

        /** Prefix prepended before model turns, if any. */
        @SerialName("model_turn_prefix") val model_turn_prefix: String? = null,

        /** Token that marks the end of a turn. */
        @SerialName("turn_end") val turn_end: String? = null,

        /** Instruction describing what to output for empty JSON. */
        @SerialName("empty_json_instruction") val empty_json_instruction: String? = null,

        /** Global preamble text for the system prompt. */
        @SerialName("preamble") val preamble: String? = null,

        /** Contract that describes model behavior and scope. */
        @SerialName("key_contract") val key_contract: String? = null,

        /** Narrative about the allowed length for answers. */
        @SerialName("length_budget") val length_budget: String? = null,

        /** Description of how scoring should work. */
        @SerialName("scoring_rule") val scoring_rule: String? = null,

        /** Extra constraints to enforce strict output formats. */
        @SerialName("strict_output") val strict_output: String? = null
    )

    // ---------------------------------------------------------------------
    // Model defaults (download/UI level)
    // ---------------------------------------------------------------------

    /**
     * Model download and UI default settings.
     *
     * These values are optional overrides for the client-side defaults
     * used by the SLM integration (for example, download URL and timeouts).
     */
    @Serializable
    data class ModelDefaults(
        /**
         * Default model URL for the download UI.
         *
         * Example:
         * "https://huggingface.co/CraneAILabs/swahili-gemma-1b-litert/resolve/main/..."
         */
        @SerialName("default_model_url") val defaultModelUrl: String? = null,

        /**
         * Default file name to use when saving the model locally.
         *
         * Example: "swahili-gemma-1b-fp16.task"
         */
        @SerialName("default_file_name") val defaultFileName: String? = null,

        /**
         * Optional timeout override for model loading/inference, in milliseconds.
         */
        @SerialName("timeout_ms") val timeoutMs: Long? = null,

        /**
         * Optional UI throttling interval for streaming updates, in milliseconds.
         */
        @SerialName("ui_throttle_ms") val uiThrottleMs: Long? = null,

        /**
         * Optional minimum number of streamed bytes before pushing a UI update.
         */
        @SerialName("ui_min_delta_bytes") val uiMinDeltaBytes: Long? = null
    )

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    /**
     * Validate the internal structure of this configuration and return a list
     * of human-readable issue strings.
     *
     * An empty list means "no issues found".
     * This validation is purely structural and does not execute any business
     * logic; it is safe to call immediately after deserialization.
     */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        val ids = graph.nodes.map { it.id }
        val idSet = ids.toSet()

        // --- startId sanity checks ---
        if (graph.startId.isBlank()) {
            issues += "graph.startId is blank"
        } else if (graph.startId !in idSet) {
            issues += "graph.startId='${graph.startId}' not found in node ids: ${
                idSet.joinToString(",")
            }"
        }

        // --- duplicate node id check ---
        val duplicateIds = ids
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            issues += "duplicate node ids: ${duplicateIds.joinToString(",")}"
        }

        // --- prompt target existence check ---
        val unknownPromptTargets = prompts
            .asSequence()
            .map { it.nodeId }
            .filter { it !in idSet }
            .distinct()
            .toList()
        if (unknownPromptTargets.isNotEmpty()) {
            issues += "prompts contain unknown nodeIds: ${
                unknownPromptTargets.joinToString(",")
            }"
        }

        // --- prompt target duplication check ---
        val duplicatePromptTargets = prompts
            .groupingBy { it.nodeId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicatePromptTargets.isNotEmpty()) {
            issues += "multiple prompts defined for nodeIds: ${
                duplicatePromptTargets.joinToString(",")
            }"
        }

        // --- nextId reference existence check ---
        graph.nodes.forEach { node ->
            node.nextId
                ?.takeIf { it.isNotBlank() }
                ?.let { next ->
                    if (next !in idSet) {
                        issues += "node '${node.id}' references unknown nextId='$next'"
                    }
                }
        }

        // --- AI node question non-empty check ---
        graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach {
                issues += "AI node '${it.id}' has empty question"
            }

        // --- SLM param sanity (optional, only if given) ---
        slm.accelerator?.let { acc ->
            val a = acc.trim().uppercase()
            if (a != "CPU" && a != "GPU") {
                issues += "slm.accelerator should be 'CPU' or 'GPU' (got '$acc')"
            }
        }
        slm.maxTokens?.let {
            if (it <= 0) {
                issues += "slm.max_tokens must be > 0 (got $it)"
            }
        }
        slm.topK?.let {
            if (it < 0) {
                issues += "slm.top_k must be >= 0 (got $it)"
            }
        }
        slm.topP?.let {
            if (it !in 0.0..1.0) {
                issues += "slm.top_p must be in [0.0,1.0] (got $it)"
            }
        }
        slm.temperature?.let {
            if (it < 0.0) {
                issues += "slm.temperature must be >= 0.0 (got $it)"
            }
        }

        // --- Model defaults sanity (optional, only if given) ---
        modelDefaults.defaultModelUrl?.let { url ->
            if (url.isBlank()) {
                issues += "model_defaults.default_model_url is blank"
            }
        }
        modelDefaults.defaultFileName?.let { name ->
            if (name.isBlank()) {
                issues += "model_defaults.default_file_name is blank"
            }
        }
        modelDefaults.timeoutMs?.let { ms ->
            if (ms <= 0L) {
                issues += "model_defaults.timeout_ms must be > 0 (got $ms)"
            }
        }
        modelDefaults.uiThrottleMs?.let { ms ->
            if (ms < 0L) {
                issues += "model_defaults.ui_throttle_ms must be >= 0 (got $ms)"
            }
        }
        modelDefaults.uiMinDeltaBytes?.let { bytes ->
            if (bytes < 0L) {
                issues += "model_defaults.ui_min_delta_bytes must be >= 0 (got $bytes)"
            }
        }

        return issues
    }

    /**
     * Export the prompt table as JSON Lines.
     *
     * Each list element is a single JSON-encoded [Prompt] record.
     * This is useful for feeding prompts into offline tools or logging
     * pipelines.
     */
    fun toJsonl(): List<String> =
        SurveyConfigLoader.jsonCompact.let { json ->
            prompts.map { json.encodeToString(Prompt.serializer(), it) }
        }

    /**
     * Serialize the full configuration as JSON.
     *
     * @param pretty If true, pretty-print the JSON; otherwise use a compact form.
     */
    fun toJson(pretty: Boolean = true): String =
        (if (pretty) SurveyConfigLoader.jsonPretty else SurveyConfigLoader.jsonCompact)
            .encodeToString(serializer(), this)

    /**
     * Serialize the full configuration as YAML.
     *
     * @param strict When true, the encoder uses strict mode for YAML, which
     * may reject configuration that contains unknown fields.
     */
    fun toYaml(strict: Boolean = false): String =
        SurveyConfigLoader.yaml(strict).encodeToString(serializer(), this)

    /**
     * Compose a single system prompt string from the SLM metadata fields.
     *
     * Only non-blank fields are appended, separated by line breaks, in the
     * following order:
     *  - preamble
     *  - key_contract
     *  - length_budget
     *  - scoring_rule
     *  - strict_output
     *  - empty_json_instruction
     *
     * This function is side-effect free and can be called repeatedly.
     */
    fun composeSystemPrompt(): String {
        fun String?.addTo(sb: StringBuilder) {
            if (!this.isNullOrBlank()) {
                if (sb.isNotEmpty()) {
                    sb.appendLine()
                }
                sb.append(this)
            }
        }
        return buildString {
            slm.preamble.addTo(this)
            slm.key_contract.addTo(this)
            slm.length_budget.addTo(this)
            slm.scoring_rule.addTo(this)
            slm.strict_output.addTo(this)
            slm.empty_json_instruction.addTo(this)
        }
    }
}

/**
 * Backward-compatible alias for [SurveyConfig.Prompt].
 *
 * This alias allows older call sites to keep referring to [PromptEntry]
 * without changing imports immediately.
 */
typealias PromptEntry = SurveyConfig.Prompt

/**
 * Backward-compatible alias for [SurveyConfig.Graph].
 *
 * This alias allows older call sites to keep referring to [GraphConfig]
 * while the new type name is [SurveyConfig.Graph].
 */
typealias GraphConfig = SurveyConfig.Graph

/**
 * Raw graph node as it is stored in the configuration file.
 *
 * This DTO is intentionally independent from the ViewModel-layer Node type
 * and should only contain data that can be safely serialized/deserialized.
 */
@Serializable
data class NodeDTO(
    val id: String,
    val type: String,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
) {
    /**
     * Interpret the [type] string as a [NodeType] enum value.
     *
     * Unknown or malformed type strings are mapped to [NodeType.UNKNOWN].
     */
    fun nodeType(): NodeType = NodeType.from(type)
}

/**
 * Node type enumeration used at the configuration layer.
 *
 * Unknown or unrecognized values are mapped to [UNKNOWN]. This enum is
 * separate from any ViewModel-level enum to keep layers decoupled.
 */
enum class NodeType {
    START,
    TEXT,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    AI,
    REVIEW,
    DONE,
    UNKNOWN;

    companion object {
        /**
         * Convert a raw string into a [NodeType].
         *
         * @param raw Raw string from config (case-insensitive, trimmed).
         * @return Matching [NodeType] value or [UNKNOWN] if no match is found.
         */
        fun from(raw: String?): NodeType =
            when (raw?.trim()?.uppercase()) {
                "START" -> START
                "TEXT" -> TEXT
                "SINGLE_CHOICE" -> SINGLE_CHOICE
                "MULTI_CHOICE" -> MULTI_CHOICE
                "AI" -> AI
                "REVIEW" -> REVIEW
                "DONE" -> DONE
                else -> UNKNOWN
            }
    }
}

/**
 * Supported configuration formats for serialization and deserialization.
 *
 * - [JSON]: Force JSON decoding/encoding.
 * - [YAML]: Force YAML decoding/encoding.
 * - [AUTO]: Let the loader sniff by extension or content.
 */
enum class ConfigFormat {
    JSON,
    YAML,
    AUTO
}

/**
 * Loader and writer utilities for [SurveyConfig].
 *
 * This object centralizes JSON/YAML serializers, format sniffing, and
 * normalization of line endings and BOM so that all call sites share
 * consistent behavior.
 */
object SurveyConfigLoader {

    /**
     * Compact JSON instance used for reading and minimal writing.
     *
     * - Ignores unknown keys.
     * - Uses lenient parsing to tolerate minor format deviations.
     */
    internal val jsonCompact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    /**
     * Pretty-printing JSON instance used for human-friendly output.
     *
     * This is mainly intended for debugging, logging, and configuration
     * inspection rather than production data interchange.
     */
    internal val jsonPretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    /**
     * Create a YAML serializer with a given strictness.
     *
     * When [strict] is false, unknown fields are ignored and defaults are
     * not encoded (encodeDefaults=false). Decoding in [fromString] always
     * uses strict=false to maximize compatibility with existing configs.
     */
    internal fun yaml(strict: Boolean = false): Yaml =
        Yaml(
            configuration = YamlConfiguration(
                encodeDefaults = false,
                // Decoding side always uses strict=false via fromString.
                strictMode = strict
            )
        )

    /**
     * Load [SurveyConfig] from an asset file.
     *
     * @param context Android context used to access the asset manager.
     * @param fileName File name inside the assets folder.
     * @param charset Character set used to read the file.
     * @param format Desired config format or [ConfigFormat.AUTO] to sniff.
     * @throws IllegalArgumentException if loading or parsing fails.
     */
    fun fromAssets(
        context: Context,
        fileName: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        try {
            context.assets.open(fileName).bufferedReader(charset).use { reader ->
                val raw = reader.readText()
                fromString(
                    text = raw,
                    format = format,
                    fileNameHint = fileName
                )
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Failed to load SurveyConfig from assets/$fileName: ${ex.message}",
                ex
            )
        }

    /**
     * Load [SurveyConfig] from a regular file on disk.
     *
     * @param path Path to the config file.
     * @param charset Character set used to read the file.
     * @param format Desired config format or [ConfigFormat.AUTO] to sniff.
     * @throws IllegalArgumentException if the file does not exist or parsing fails.
     */
    fun fromFile(
        path: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        try {
            val file = File(path)
            require(file.exists()) { "Config file not found: $path" }
            file.bufferedReader(charset).use { reader ->
                val raw = reader.readText()
                fromString(
                    text = raw,
                    format = format,
                    fileNameHint = file.name
                )
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Failed to load SurveyConfig from file '$path': ${ex.message}",
                ex
            )
        }

    /**
     * Parse [SurveyConfig] from a raw string.
     *
     * The string is normalized (BOM removed, line endings unified, trailing
     * newlines trimmed) and then decoded as either JSON or YAML depending on:
     *  - [format] if it is not [ConfigFormat.AUTO]
     *  - file name hint (extension)
     *  - or content sniffing.
     *
     * @param text Raw text content of the config file.
     * @param format Desired config format or [ConfigFormat.AUTO] to sniff.
     * @param fileNameHint Optional file name used for extension-based sniffing.
     * @throws IllegalArgumentException if parsing fails for any reason.
     */
    fun fromString(
        text: String,
        format: ConfigFormat = ConfigFormat.AUTO,
        fileNameHint: String? = null
    ): SurveyConfig {
        val sanitized = text.normalize()
        val chosen = pickFormat(
            desired = format,
            fileName = fileNameHint,
            text = sanitized
        )

        return try {
            when (chosen) {
                ConfigFormat.JSON ->
                    jsonCompact.decodeFromString(SurveyConfig.serializer(), sanitized)

                ConfigFormat.YAML ->
                    yaml(strict = false).decodeFromString(SurveyConfig.serializer(), sanitized)

                ConfigFormat.AUTO ->
                    error("AUTO should have been resolved before decoding; this is a bug.")
            }
        } catch (ex: SerializationException) {
            val preview = sanitized.safePreview()
            throw IllegalArgumentException(
                "Parsing error (format=${chosen.name}). First 200 chars: " +
                        "$preview :: ${ex.message}",
                ex
            )
        } catch (ex: Exception) {
            val preview = sanitized.safePreview()
            throw IllegalArgumentException(
                "Unexpected error while parsing SurveyConfig (format=${chosen.name}). " +
                        "First 200 chars: $preview :: ${ex.message}",
                ex
            )
        }
    }

    /**
     * Decide which [ConfigFormat] to use, based on the desired format,
     * file name extension, and optionally the content.
     *
     * @param desired Explicitly requested format.
     * @param fileName File name for extension-based sniffing.
     * @param text Optional raw content for content-based sniffing.
     */
    private fun pickFormat(
        desired: ConfigFormat,
        fileName: String? = null,
        text: String? = null
    ): ConfigFormat {
        if (desired != ConfigFormat.AUTO) {
            return desired
        }

        val lower = fileName?.lowercase().orEmpty()
        if (lower.endsWith(".json")) {
            return ConfigFormat.JSON
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return ConfigFormat.YAML
        }

        val sniff = text?.let(::sniffFormat)
        return sniff ?: ConfigFormat.JSON
    }

    /**
     * Quickly infer the format from the first non-empty line and leading
     * characters.
     *
     * The heuristic is intentionally simple:
     *  - Leading '{' or '[' -> JSON
     *  - Leading '---', '- ' or typical "key: value" -> YAML
     *  - Otherwise fall back to JSON.
     */
    private fun sniffFormat(text: String): ConfigFormat {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return ConfigFormat.JSON
        }

        val firstNonEmpty = trimmed
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: ""

        if (firstNonEmpty.startsWith("---")) {
            return ConfigFormat.YAML
        }
        if (firstNonEmpty.startsWith("- ")) {
            return ConfigFormat.YAML
        }
        if (":" in firstNonEmpty && !firstNonEmpty.startsWith("{")) {
            return ConfigFormat.YAML
        }

        return ConfigFormat.JSON
    }

    /**
     * Normalize BOM and line endings for a raw text string.
     *
     * - Removes UTF-8 BOM if present.
     * - Converts CRLF/CR to LF.
     * - Trims trailing line breaks.
     */
    private fun String.normalize(): String =
        this.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    /**
     * Return a short preview string for error messages.
     *
     * Line breaks are escaped and the string is truncated to [max] characters
     * so that stack traces remain readable and compact.
     */
    private fun String.safePreview(max: Int = 200): String =
        this.replace("\n", "\\n")
            .replace("\r", "\\r")
            .let { text ->
                if (text.length <= max) {
                    text
                } else {
                    text.substring(0, max) + "..."
                }
            }
}
