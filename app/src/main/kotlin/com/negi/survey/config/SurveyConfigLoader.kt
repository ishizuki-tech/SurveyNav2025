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
 *  Supports JSON and YAML formats, SLM metadata, QA-Eval (EVAL -> optional Follow-up),
 *  model defaults, Whisper metadata, and structural validation for graph-based
 *  survey flows.
 *
 *  Update (2026-01):
 *  ---------------------------------------------------------------------
 *   • Fix: Accept both snake_case and camelCase keys for core graph/prompt fields:
 *       - graph.start_id / graph.startId
 *       - prompts[].node_id / prompts[].nodeId
 *       - nodes[].next_id / nodes[].nextId
 *     via custom serializers (no unsafe text rewriting).
 *
 *   • Improve: Validation now separates Errors vs Warnings
 *       - requireValid() throws ONLY on errors (warnings are logged)
 *       - Unreachable nodes / self-loop / legacy config notices are warnings
 *
 *   • Improve: Stronger validation
 *       - Detect blank prompt nodeId / blank prompt template
 *       - Detect AI nodes with no prompt defined (any stage)
 *       - Warn on self-loop nextId
 *       - Warn on unreachable nodes from startId (soft rule)
 *
 *   • Improve: Prompt stage utilities (BASE/EVAL/FOLLOWUP) with tolerant parsing.
 *
 *   • Fix (compile): Allow `Prompt.containsStageDelimiter()` so call sites can do:
 *       prompts.filter { it.containsStageDelimiter() }
 *     without receiver mismatch (Prompt vs String).
 *
 *  Update (2026-01 QA-Eval):
 *  ---------------------------------------------------------------------
 *   • New: Add `qa_eval:` section (preferred) to control 1-call / 2-call workflows:
 *       - mode: one_call | two_call
 *       - followup_trigger: needs_followup | score_below_threshold | either
 *       - max_tokens_eval / max_tokens_followup
 *       - fallback_to_inline_followup
 *     while keeping legacy `two_step:` support for backward compatibility.
 * =====================================================================
 */

package com.negi.survey.config

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import java.nio.charset.Charset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json

/**
 * Top-level configuration model for a survey.
 *
 * Aggregates the prompt table, graph structure, SLM metadata, QA-Eval metadata,
 * Whisper metadata, and model defaults that describe how a survey should be
 * executed at runtime.
 */
@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph,
    val slm: SlmMeta = SlmMeta(),

    /**
     * Preferred QA evaluation config (new).
     *
     * - Used to support 1-call (eval only) and 2-call (eval + followup generation) flows.
     * - If both qa_eval and two_step are present, qa_eval takes precedence.
     */
    @SerialName("qa_eval") val qaEval: QaEvalMeta = QaEvalMeta(),

    /**
     * Legacy two-step metadata (old).
     *
     * - Kept for backward compatibility with older configs.
     * - Only contains a subset of QA-eval knobs.
     */
    @SerialName("two_step") val twoStep: TwoStepMeta = TwoStepMeta(),

    val whisper: WhisperMeta = WhisperMeta(),
    @SerialName("model_defaults") val modelDefaults: ModelDefaults = ModelDefaults()
) {

    // ---------------------------------------------------------------------
    // prompts
    // ---------------------------------------------------------------------

    /**
     * Prompt stage token derived from staged prompt identifiers.
     */
    enum class PromptStage {
        BASE,
        EVAL,
        FOLLOWUP,
        UNKNOWN
    }

    /**
     * A single prompt template entry associated with a specific graph node.
     *
     * Notes:
     * - Supports both nodeId and node_id in configs via custom serializer.
     * - Supports staged node ids like "Q1#eval" or "Q1#followup".
     */
    @Serializable(with = PromptSerializer::class)
    data class Prompt(
        val nodeId: String,
        val prompt: String
    ) {

        /**
         * Extract the base node id from staged prompt identifiers.
         */
        fun baseNodeId(): String = nodeId.baseNodeId()

        /**
         * Extract the stage token (e.g., "eval", "followup") from a staged prompt id.
         * Returns null if the nodeId does not contain a stage delimiter.
         */
        fun stageTokenOrNull(): String? = nodeId.stageTokenOrNull()

        /**
         * Resolve the stage into a [PromptStage] using tolerant parsing.
         */
        fun stage(): PromptStage = stageTokenOrNull().toPromptStage()

        /**
         * Return true if this prompt id looks like a staged identifier.
         *
         * This exists so call sites can do `it.containsStageDelimiter()` where `it` is Prompt,
         * avoiding a receiver mismatch with the file-private String extension.
         */
        fun containsStageDelimiter(): Boolean = nodeId.containsStageDelimiter()
    }

    // ---------------------------------------------------------------------
    // graph
    // ---------------------------------------------------------------------

    /**
     * Graph definition for the survey flow.
     *
     * Notes:
     * - Supports startId and start_id in configs via custom serializer.
     */
    @Serializable(with = GraphSerializer::class)
    data class Graph(
        val startId: String,
        val nodes: List<NodeDTO> = emptyList()
    )

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    /**
     * Validation result separating errors and warnings.
     */
    data class ValidationResult(
        val errors: List<String>,
        val warnings: List<String>
    ) {
        val isOk: Boolean get() = errors.isEmpty()
    }

    /**
     * Validate the internal structure and return errors and warnings.
     *
     * - Errors: should prevent execution (requireValid throws)
     * - Warnings: safe to run, but likely indicates misconfiguration
     */
    fun validateDetailed(): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // --- graph basic sanity ---
        if (graph.startId.isBlank()) {
            errors += "graph.startId is blank"
        }
        if (graph.nodes.isEmpty()) {
            errors += "graph.nodes is empty"
            return ValidationResult(errors = errors, warnings = warnings)
        }

        // --- node id sanity ---
        val ids = graph.nodes.map { it.id }
        if (ids.any { it.isBlank() }) {
            errors += "graph.nodes contains blank id entries"
        }
        val idSet = ids.filter { it.isNotBlank() }.toSet()

        // --- startId existence ---
        if (graph.startId.isNotBlank() && graph.startId !in idSet) {
            errors += "graph.startId='${graph.startId}' not found in node ids: ${idSet.joinToString(",")}"
        }

        // --- duplicate node id check ---
        val duplicateIds = ids
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            errors += "duplicate node ids: ${duplicateIds.joinToString(",")}"
        }

        // --- node type sanity ---
        val unknownTypes = graph.nodes
            .filter { it.nodeType() == NodeType.UNKNOWN }
            .map { it.id }
            .filter { it.isNotBlank() }
        if (unknownTypes.isNotEmpty()) {
            errors += "nodes with unknown type: ${unknownTypes.joinToString(",")}"
        }

        // --- START node semantics (soft rules) ---
        val startNode = graph.nodes.firstOrNull { it.id == graph.startId }
        if (startNode != null && startNode.nodeType() != NodeType.START) {
            warnings += "graph.startId points to a non-START node (id='${startNode.id}', type='${startNode.type}')"
        }
        val explicitStarts = graph.nodes.count { it.nodeType() == NodeType.START }
        if (explicitStarts > 1) {
            warnings += "multiple START nodes detected (count=$explicitStarts)"
        }

        // --- prompts sanity ---
        if (prompts.any { it.nodeId.isBlank() }) {
            errors += "prompts contain blank nodeId entries"
        }
        val blankPromptBodies = prompts
            .asSequence()
            .filter { it.prompt.isBlank() }
            .map { it.nodeId }
            .toList()
        if (blankPromptBodies.isNotEmpty()) {
            errors += "prompts contain blank prompt templates for nodeIds: ${blankPromptBodies.joinToString(",")}"
        }

        // --- prompts target existence check (staged ids supported) ---
        val unknownPromptTargets = prompts
            .asSequence()
            .map { it.nodeId.baseNodeId() }
            .filter { it.isNotBlank() }
            .filter { it !in idSet }
            .distinct()
            .toList()
        if (unknownPromptTargets.isNotEmpty()) {
            errors += "prompts contain unknown nodeIds (base ids): ${unknownPromptTargets.joinToString(",")}"
        }

        // --- prompt target duplication check (base + stage) ---
        val duplicatePromptKeys = prompts
            .asSequence()
            .map { p ->
                val base = p.nodeId.baseNodeId()
                val stage = p.nodeId.stageTokenOrNull()?.lowercase()?.trim().orEmpty()
                if (stage.isEmpty()) base else "$base#$stage"
            }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .toList()
        if (duplicatePromptKeys.isNotEmpty()) {
            errors += "multiple prompts defined for nodeIds (base+stage): ${duplicatePromptKeys.joinToString(",")}"
        }

        // --- staged prompt sanity (soft) ---
        val blankStagePrompts = prompts
            .asSequence()
            .map { it.nodeId }
            .filter { it.containsStageDelimiter() }
            .filter { it.stageTokenOrNull().isNullOrBlank() }
            .toList()
        if (blankStagePrompts.isNotEmpty()) {
            warnings += "prompts contain staged nodeIds with blank stage token: ${blankStagePrompts.joinToString(",")}"
        }

        // --- Unknown stage prompts (soft) ---
        val unknownStagePrompts = prompts
            .asSequence()
            .filter { it.containsStageDelimiter() }
            .filter { it.stage() == PromptStage.UNKNOWN }
            .map { it.nodeId }
            .toList()
        if (unknownStagePrompts.isNotEmpty()) {
            warnings += "prompts contain unknown stage tokens: ${unknownStagePrompts.joinToString(",")}"
        }

        // --- AI nodes should have a prompt defined (any stage is acceptable) ---
        val aiIds = graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.AI }
            .map { it.id }
            .filter { it.isNotBlank() }
            .toList()

        val promptBaseSet = prompts
            .asSequence()
            .map { it.nodeId.baseNodeId() }
            .filter { it.isNotBlank() }
            .toSet()

        val aiMissingPrompts = aiIds.filter { it !in promptBaseSet }
        if (aiMissingPrompts.isNotEmpty()) {
            errors += "AI nodes with no prompt defined: ${aiMissingPrompts.joinToString(",")}"
        }

        // --- nextId reference existence check + self-loop warning ---
        graph.nodes.forEach { node ->
            val next = node.nextId?.takeIf { it.isNotBlank() }
            if (next != null) {
                if (next !in idSet) {
                    errors += "node '${node.id}' references unknown nextId='$next'"
                }
                if (next == node.id) {
                    warnings += "node '${node.id}' has self-loop nextId='${node.id}'"
                }
            }
        }

        // --- Reachability check (soft rule) ---
        val reachable = graph.reachableNodeIds()
        val unreachable = idSet.minus(reachable)
        if (unreachable.isNotEmpty()) {
            warnings += "unreachable nodes from startId='${graph.startId}': ${unreachable.joinToString(",")}"
        }

        // --- AI node question non-empty check ---
        graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach {
                errors += "AI node '${it.id}' has empty question"
            }

        // --- Choice nodes should have non-empty options ---
        graph.nodes
            .asSequence()
            .filter {
                val t = it.nodeType()
                t == NodeType.SINGLE_CHOICE || t == NodeType.MULTI_CHOICE
            }
            .filter { it.options.isEmpty() }
            .forEach {
                errors += "Choice node '${it.id}' has empty options"
            }

        // --- QA-eval sanity (preferred) + legacy two_step sanity ---
        val qaHas = qaEval.hasAnySetting()
        val legacyHas = twoStep.hasAnySetting()

        if (qaHas && legacyHas) {
            warnings += "both qa_eval and two_step are specified; qa_eval takes precedence"
        } else if (!qaHas && legacyHas) {
            warnings += "legacy two_step is used; please migrate to qa_eval"
        }

        // Validate qa_eval if present
        if (qaHas) {
            qaEval.evalOkScoreThreshold?.let { th ->
                if (th !in 1..100) {
                    errors += "qa_eval.eval_ok_score_threshold must be in [1,100] (got $th)"
                }
            }

            val parsedMode = QaEvalMeta.Mode.from(qaEval.mode)
            if (qaEval.mode != null && parsedMode == QaEvalMeta.Mode.UNKNOWN) {
                errors += "qa_eval.mode is unknown (got '${qaEval.mode}')"
            }

            val parsedTrig = QaEvalMeta.FollowupTrigger.from(qaEval.followupTrigger)
            if (qaEval.followupTrigger != null && parsedTrig == QaEvalMeta.FollowupTrigger.UNKNOWN) {
                errors += "qa_eval.followup_trigger is unknown (got '${qaEval.followupTrigger}')"
            }

            qaEval.maxTokensEval?.let { if (it <= 0) errors += "qa_eval.max_tokens_eval must be > 0 (got $it)" }
            qaEval.maxTokensFollowup?.let { if (it <= 0) errors += "qa_eval.max_tokens_followup must be > 0 (got $it)" }
        }

        // Validate legacy two_step thresholds if present
        twoStep.evalOkScoreThreshold?.let { th ->
            if (th !in 1..100) {
                errors += "two_step.eval_ok_score_threshold must be in [1,100] (got $th)"
            }
        }

        // --- SLM param sanity (optional, only if given) ---
        slm.accelerator?.let { acc ->
            val a = acc.trim().uppercase()
            if (a != "CPU" && a != "GPU") {
                errors += "slm.accelerator should be 'CPU' or 'GPU' (got '$acc')"
            }
        }
        slm.maxTokens?.let { if (it <= 0) errors += "slm.max_tokens must be > 0 (got $it)" }
        slm.topK?.let { if (it < 0) errors += "slm.top_k must be >= 0 (got $it)" }
        slm.topP?.let { if (it !in 0.0..1.0) errors += "slm.top_p must be in [0.0,1.0] (got $it)" }
        slm.temperature?.let { if (it < 0.0) errors += "slm.temperature must be >= 0.0 (got $it)" }
        slm.repetitionPenalty?.let { if (it <= 0.0) errors += "slm.repetition_penalty must be > 0.0 (got $it)" }

        // --- Whisper param sanity (optional, only if given) ---
        whisper.assetModelPath?.let { if (it.isBlank()) errors += "whisper.asset_model_path is blank" }
        whisper.language?.let { lang ->
            val norm = lang.trim().lowercase()
            if (norm !in setOf("auto", "en", "ja", "sw")) {
                errors += "whisper.language should be one of 'auto','en','ja','sw' (got '$lang')"
            }
        }
        whisper.targetSampleRate?.let { if (it <= 0) errors += "whisper.target_sample_rate must be > 0 (got $it)" }
        whisper.recordSampleRates?.let { rs ->
            if (rs.isEmpty()) {
                errors += "whisper.record_sample_rates is empty"
            } else {
                val bad = rs.filter { it <= 0 }.distinct()
                if (bad.isNotEmpty()) {
                    errors += "whisper.record_sample_rates contains non-positive entries: ${bad.joinToString(",")}"
                }
            }
        }

        // --- Model defaults sanity (optional, only if given) ---
        modelDefaults.defaultModelUrl?.let { if (it.isBlank()) errors += "model_defaults.default_model_url is blank" }
        modelDefaults.defaultFileName?.let { if (it.isBlank()) errors += "model_defaults.default_file_name is blank" }
        modelDefaults.timeoutMs?.let { if (it <= 0L) errors += "model_defaults.timeout_ms must be > 0 (got $it)" }
        modelDefaults.uiThrottleMs?.let { if (it < 0L) errors += "model_defaults.ui_throttle_ms must be >= 0 (got $it)" }
        modelDefaults.uiMinDeltaBytes?.let { if (it < 0L) errors += "model_defaults.ui_min_delta_bytes must be >= 0 (got $it)" }

        return ValidationResult(errors = errors, warnings = warnings)
    }

    /**
     * Validate and return a flat list.
     *
     * - Errors are listed first
     * - Warnings are prefixed with "WARN:"
     */
    fun validate(): List<String> {
        val r = validateDetailed()
        return buildList {
            addAll(r.errors)
            addAll(r.warnings.map { "WARN: $it" })
        }
    }

    /**
     * Validate and throw an [IllegalArgumentException] if errors are found.
     *
     * Warnings are logged but do not throw.
     */
    fun requireValid() {
        val r = validateDetailed()
        if (r.warnings.isNotEmpty()) {
            Log.w("SurveyConfig", "Validation warnings:\n- " + r.warnings.joinToString("\n- "))
        }
        require(r.errors.isEmpty()) {
            "SurveyConfig validation failed:\n- " + r.errors.joinToString("\n- ")
        }
    }

    // ---------------------------------------------------------------------
    // QA-Eval metadata (preferred) + Legacy Two-step metadata (compat)
    // ---------------------------------------------------------------------

    /**
     * QA evaluation flow configuration (preferred).
     *
     * This is a superset of the legacy two_step config.
     */
    @Serializable
    data class QaEvalMeta(
        @SerialName("enabled") val enabled: Boolean? = null,
        @SerialName("mode") val mode: String? = null,
        @SerialName("followup_trigger") val followupTrigger: String? = null,
        @SerialName("eval_ok_score_threshold") val evalOkScoreThreshold: Int? = null,
        @SerialName("skip_followup_when_ok") val skipFollowupWhenOk: Boolean? = null,
        @SerialName("fallback_to_inline_followup") val fallbackToInlineFollowup: Boolean? = null,
        @SerialName("max_tokens_eval") val maxTokensEval: Int? = null,
        @SerialName("max_tokens_followup") val maxTokensFollowup: Int? = null
    ) {

        /** Workflow mode. */
        enum class Mode {
            ONE_CALL,
            TWO_CALL,
            UNKNOWN;

            companion object {
                /** Parse mode from raw text (tolerant). */
                fun from(raw: String?): Mode {
                    val t = raw?.trim()?.lowercase().orEmpty()
                    return when (t) {
                        "one_call", "onecall", "one-call", "1call", "1_call", "single_call" -> ONE_CALL
                        "two_call", "twocall", "two-call", "2call", "2_call", "double_call" -> TWO_CALL
                        "" -> UNKNOWN
                        else -> UNKNOWN
                    }
                }
            }
        }

        /** Follow-up triggering policy. */
        enum class FollowupTrigger {
            NEEDS_FOLLOWUP,
            SCORE_BELOW_THRESHOLD,
            EITHER,
            UNKNOWN;

            companion object {
                /** Parse trigger from raw text (tolerant). */
                fun from(raw: String?): FollowupTrigger {
                    val t = raw?.trim()?.lowercase().orEmpty()
                    return when (t) {
                        "needs_followup", "needsfollowup", "needs", "missing" -> NEEDS_FOLLOWUP
                        "score_below_threshold", "scorebelowthreshold", "score_below", "score" -> SCORE_BELOW_THRESHOLD
                        "either", "any" -> EITHER
                        "" -> UNKNOWN
                        else -> UNKNOWN
                    }
                }
            }
        }

        /**
         * Resolved, strongly typed QA-eval settings for runtime.
         */
        data class Resolved(
            val enabled: Boolean,
            val mode: Mode,
            val followupTrigger: FollowupTrigger,
            val evalOkScoreThreshold: Int,
            val skipFollowupWhenOk: Boolean,
            val fallbackToInlineFollowup: Boolean,
            val maxTokensEval: Int,
            val maxTokensFollowup: Int,
            val source: String
        )

        /** True if any QA-eval field is explicitly provided. */
        fun hasAnySetting(): Boolean =
            enabled != null ||
                    !mode.isNullOrBlank() ||
                    !followupTrigger.isNullOrBlank() ||
                    evalOkScoreThreshold != null ||
                    skipFollowupWhenOk != null ||
                    fallbackToInlineFollowup != null ||
                    maxTokensEval != null ||
                    maxTokensFollowup != null

        /** Resolve enabled with a default. */
        fun enabledOr(defaultValue: Boolean = false): Boolean = enabled ?: defaultValue

        /** Resolve eval_ok_score_threshold with a default. */
        fun okScoreThresholdOr(defaultValue: Int = 85): Int = evalOkScoreThreshold ?: defaultValue

        /** Resolve skip_followup_when_ok with a default. */
        fun skipFollowupWhenOkOr(defaultValue: Boolean = true): Boolean = skipFollowupWhenOk ?: defaultValue

        /** Resolve fallback_to_inline_followup with a default. */
        fun fallbackToInlineFollowupOr(defaultValue: Boolean = true): Boolean = fallbackToInlineFollowup ?: defaultValue

        /** Resolve max_tokens_eval with a default. */
        fun maxTokensEvalOr(defaultValue: Int = 512): Int = maxTokensEval ?: defaultValue

        /** Resolve max_tokens_followup with a default. */
        fun maxTokensFollowupOr(defaultValue: Int = 128): Int = maxTokensFollowup ?: defaultValue

        /** Resolve mode with a default. */
        fun modeOr(defaultValue: Mode = Mode.ONE_CALL): Mode {
            val parsed = Mode.from(mode)
            return if (parsed == Mode.UNKNOWN) defaultValue else parsed
        }

        /** Resolve followup_trigger with a default. */
        fun followupTriggerOr(defaultValue: FollowupTrigger = FollowupTrigger.NEEDS_FOLLOWUP): FollowupTrigger {
            val parsed = FollowupTrigger.from(followupTrigger)
            return if (parsed == FollowupTrigger.UNKNOWN) defaultValue else parsed
        }

        companion object {
            /**
             * Resolve QA-eval settings using:
             * - qa_eval (preferred) when any field is set
             * - otherwise fallback to legacy two_step
             */
            fun resolve(primary: QaEvalMeta, legacy: TwoStepMeta): Resolved {
                val primaryHas = primary.hasAnySetting()
                val legacyHas = legacy.hasAnySetting()

                val src = when {
                    primaryHas -> "qa_eval"
                    legacyHas -> "two_step"
                    else -> "defaults"
                }

                val enabled = when {
                    primary.enabled != null -> primary.enabled
                    legacy.enabled != null -> legacy.enabled
                    else -> false
                } ?: false

                val evalTh = when {
                    primary.evalOkScoreThreshold != null -> primary.evalOkScoreThreshold
                    legacy.evalOkScoreThreshold != null -> legacy.evalOkScoreThreshold
                    else -> 85
                } ?: 85

                val skipFu = when {
                    primary.skipFollowupWhenOk != null -> primary.skipFollowupWhenOk
                    legacy.skipFollowupWhenOk != null -> legacy.skipFollowupWhenOk
                    else -> true
                } ?: true

                val mode = primary.modeOr(defaultValue = Mode.ONE_CALL)
                val trigger = primary.followupTriggerOr(defaultValue = FollowupTrigger.NEEDS_FOLLOWUP)

                val fallbackInline = primary.fallbackToInlineFollowupOr(defaultValue = true)
                val maxEval = primary.maxTokensEvalOr(defaultValue = 512)
                val maxFu = primary.maxTokensFollowupOr(defaultValue = 128)

                return Resolved(
                    enabled = enabled,
                    mode = mode,
                    followupTrigger = trigger,
                    evalOkScoreThreshold = evalTh,
                    skipFollowupWhenOk = skipFu,
                    fallbackToInlineFollowup = fallbackInline,
                    maxTokensEval = maxEval,
                    maxTokensFollowup = maxFu,
                    source = src
                )
            }
        }
    }

    /**
     * Legacy two-step SLM flow configuration.
     *
     * NOTE:
     * - This remains supported for backward compatibility.
     * - Prefer using `qa_eval` going forward.
     */
    @Serializable
    data class TwoStepMeta(
        @SerialName("enabled") val enabled: Boolean? = null,
        @SerialName("eval_ok_score_threshold") val evalOkScoreThreshold: Int? = null,
        @SerialName("skip_followup_when_ok") val skipFollowupWhenOk: Boolean? = null
    ) {
        /** True if any legacy field is explicitly provided. */
        fun hasAnySetting(): Boolean =
            enabled != null ||
                    evalOkScoreThreshold != null ||
                    skipFollowupWhenOk != null

        /** Resolve enabled with a default. */
        fun enabledOr(defaultValue: Boolean = false): Boolean = enabled ?: defaultValue

        /** Resolve eval_ok_score_threshold with a default. */
        fun okScoreThresholdOr(defaultValue: Int = 85): Int = evalOkScoreThreshold ?: defaultValue

        /** Resolve skip_followup_when_ok with a default. */
        fun skipFollowupWhenOkOr(defaultValue: Boolean = true): Boolean = skipFollowupWhenOk ?: defaultValue
    }

    /**
     * Resolve effective QA-eval settings for runtime.
     *
     * Priority:
     * - qa_eval (if any field is explicitly provided)
     * - else two_step (legacy)
     * - else defaults
     */
    fun resolveQaEval(): QaEvalMeta.Resolved = QaEvalMeta.resolve(qaEval, twoStep)

    // ---------------------------------------------------------------------
    // SLM metadata
    // ---------------------------------------------------------------------

    /**
     * SLM runtime parameters and system-prompt metadata.
     *
     * All fields are optional. Snake_case field names have explicit [SerialName]
     * annotations to keep YAML/JSON key resolution stable.
     */
    @Serializable
    data class SlmMeta(
        /** Preferred accelerator type, "CPU" or "GPU". */
        @SerialName("accelerator") val accelerator: String? = null,

        /**
         * Maximum number of tokens for the backend.
         *
         * Note: On some backends, this may represent (input + output) tokens.
         */
        @SerialName("max_tokens") val maxTokens: Int? = null,

        /** Top-k sampling parameter. */
        @SerialName("top_k") val topK: Int? = null,

        /** Top-p (nucleus) sampling parameter. */
        @SerialName("top_p") val topP: Double? = null,

        /** Temperature parameter for sampling. */
        @SerialName("temperature") val temperature: Double? = null,

        /** Repetition penalty, if supported by the backend. */
        @SerialName("repetition_penalty") val repetitionPenalty: Double? = null,

        /** Optional stop sequences, if supported by the backend. */
        @SerialName("stop_sequences") val stopSequences: List<String>? = null,

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

        /** Contract that describes model behavior and scope (legacy single-step). */
        @SerialName("key_contract") val key_contract: String? = null,

        /** Narrative about the allowed length for answers (legacy single-step). */
        @SerialName("length_budget") val length_budget: String? = null,

        /** Description of how scoring should work. */
        @SerialName("scoring_rule") val scoring_rule: String? = null,

        /** Extra constraints to enforce strict output formats (legacy single-step). */
        @SerialName("strict_output") val strict_output: String? = null,

        /** Contract for the EVAL step (strict JSON output, gating keys, etc.). */
        @SerialName("eval_key_contract") val eval_key_contract: String? = null,

        /** Length constraints for the EVAL step. */
        @SerialName("eval_length_budget") val eval_length_budget: String? = null,

        /** Strict-output constraints for the EVAL step. */
        @SerialName("eval_strict_output") val eval_strict_output: String? = null,

        /** Contract for the follow-up step (e.g., plain text, single sentence). */
        @SerialName("followup_contract") val followup_contract: String? = null
    ) {
        /** Convenience alias (camelCase) for call sites. */
        val userTurnPrefix: String? get() = user_turn_prefix

        /** Convenience alias (camelCase) for call sites. */
        val modelTurnPrefix: String? get() = model_turn_prefix

        /** Convenience alias (camelCase) for call sites. */
        val turnEnd: String? get() = turn_end

        /** Convenience alias (camelCase) for call sites. */
        val emptyJsonInstruction: String? get() = empty_json_instruction

        /** Convenience alias (camelCase) for call sites. */
        val keyContract: String? get() = key_contract

        /** Convenience alias (camelCase) for call sites. */
        val lengthBudget: String? get() = length_budget

        /** Convenience alias (camelCase) for call sites. */
        val scoringRule: String? get() = scoring_rule

        /** Convenience alias (camelCase) for call sites. */
        val strictOutput: String? get() = strict_output

        /** Convenience alias (camelCase) for call sites. */
        val evalKeyContract: String? get() = eval_key_contract

        /** Convenience alias (camelCase) for call sites. */
        val evalLengthBudget: String? get() = eval_length_budget

        /** Convenience alias (camelCase) for call sites. */
        val evalStrictOutput: String? get() = eval_strict_output

        /** Convenience alias (camelCase) for call sites. */
        val followupContract: String? get() = followup_contract
    }

    // ---------------------------------------------------------------------
    // Whisper metadata
    // ---------------------------------------------------------------------

    /**
     * Whisper runtime parameters used by on-device speech input.
     */
    @Serializable
    data class WhisperMeta(
        /** Enable or disable Whisper voice features at runtime. */
        @SerialName("enabled") val enabled: Boolean? = null,

        /** Asset path like "models/ggml-small-q5_1.bin". */
        @SerialName("asset_model_path") val assetModelPath: String? = null,

        /** "auto", "en", "ja", "sw". */
        @SerialName("language") val language: String? = null,

        /** If true, run Whisper in translation-to-English mode. */
        @SerialName("translate") val translate: Boolean? = null,

        /** If true, include timestamps in transcription output. */
        @SerialName("print_timestamp") val printTimestamp: Boolean? = null,

        /** Decoder target sample rate (e.g., 16000). */
        @SerialName("target_sample_rate") val targetSampleRate: Int? = null,

        /** Recorder preferred sample rates. */
        @SerialName("record_sample_rates") val recordSampleRates: List<Int>? = null,

        /** Whether to compute SHA-256 for exported WAV. */
        @SerialName("compute_checksum") val computeChecksum: Boolean? = null
    )

    // ---------------------------------------------------------------------
    // Model defaults (download/UI level)
    // ---------------------------------------------------------------------

    /**
     * Model download and UI default settings.
     */
    @Serializable
    data class ModelDefaults(
        /** Default model URL for the download UI. */
        @SerialName("default_model_url") val defaultModelUrl: String? = null,

        /** Default file name to use when saving the model locally. */
        @SerialName("default_file_name") val defaultFileName: String? = null,

        /** Optional timeout override for model loading/inference, in milliseconds. */
        @SerialName("timeout_ms") val timeoutMs: Long? = null,

        /** Optional UI throttling interval for streaming updates, in milliseconds. */
        @SerialName("ui_throttle_ms") val uiThrottleMs: Long? = null,

        /** Optional minimum number of streamed bytes before pushing a UI update. */
        @SerialName("ui_min_delta_bytes") val uiMinDeltaBytes: Long? = null
    )

    // ---------------------------------------------------------------------
    // Prompt lookup helpers
    // ---------------------------------------------------------------------

    /**
     * Find the best matching prompt for [nodeId] and [stage].
     *
     * Resolution:
     *  1) stage-specific prompt (base id matches and stage matches)
     *  2) base prompt (base id matches and stage == BASE)
     *  3) null
     */
    fun findPrompt(nodeId: String, stage: PromptStage = PromptStage.BASE): Prompt? {
        val base = nodeId.baseNodeId()
        val stageHit = prompts.firstOrNull { it.baseNodeId() == base && it.stage() == stage }
        if (stageHit != null) return stageHit
        return prompts.firstOrNull { it.baseNodeId() == base && it.stage() == PromptStage.BASE }
    }

    /** Find the prompt template string for [nodeId] and [stage]. */
    fun findPromptText(nodeId: String, stage: PromptStage = PromptStage.BASE): String? =
        findPrompt(nodeId, stage)?.prompt

    // ---------------------------------------------------------------------
    // Prompt composition helpers
    // ---------------------------------------------------------------------

    /**
     * Compose a single system prompt string from the legacy SLM metadata fields.
     */
    fun composeSystemPrompt(): String {
        val parts = listOf(
            slm.preamble,
            slm.key_contract,
            slm.length_budget,
            slm.scoring_rule,
            slm.strict_output,
            slm.empty_json_instruction
        ).filterNot { it.isNullOrBlank() }
            .map { it!!.trim() }

        return parts.joinToString("\n")
    }

    /**
     * Compose a system prompt string for the EVAL step (QA-eval).
     */
    fun composeEvalSystemPrompt(): String {
        val parts = listOf(
            slm.preamble,
            slm.eval_key_contract ?: slm.key_contract,
            slm.eval_length_budget ?: slm.length_budget,
            slm.scoring_rule,
            slm.eval_strict_output ?: slm.strict_output,
            slm.empty_json_instruction
        ).filterNot { it.isNullOrBlank() }
            .map { it!!.trim() }

        return parts.joinToString("\n")
    }

    /**
     * Compose a system prompt string for the Follow-up step (QA-eval).
     */
    fun composeFollowupSystemPrompt(): String {
        val parts = listOf(
            slm.preamble,
            slm.followup_contract
        ).filterNot { it.isNullOrBlank() }
            .map { it!!.trim() }

        return parts.joinToString("\n")
    }

    /**
     * Compute reachable node ids from [graph.startId] following nextId pointers.
     *
     * This graph model currently supports a single linear successor ([NodeDTO.nextId]).
     * Branching should be represented at a higher layer, or by extending NodeDTO.
     */
    private fun Graph.reachableNodeIds(): Set<String> {
        val byId = nodes.associateBy { it.id }
        val visited = linkedSetOf<String>()

        var current = startId
        while (current.isNotBlank() && current !in visited) {
            visited += current
            val n = byId[current] ?: break
            val next = n.nextId?.trim().orEmpty()
            if (next.isBlank()) break
            current = next
        }
        return visited
    }

    /**
     * Export the prompt table as JSON Lines.
     *
     * Each list element is a single JSON-encoded [Prompt] record.
     */
    fun toJsonl(): List<String> =
        SurveyConfigLoader.jsonCompact.let { json ->
            prompts.map { json.encodeToString(PromptSerializer, it) }
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
     * @param strict When true, the encoder uses strict mode for YAML.
     */
    fun toYaml(strict: Boolean = false): String =
        SurveyConfigLoader.yaml(strict).encodeToString(serializer(), this)
}

/** Backward-compatible alias for [SurveyConfig.Prompt]. */
typealias PromptEntry = SurveyConfig.Prompt

/** Backward-compatible alias for [SurveyConfig.Graph]. */
typealias GraphConfig = SurveyConfig.Graph

/**
 * Raw graph node as it is stored in the configuration file.
 *
 * Notes:
 * - Supports nextId and next_id in configs via custom serializer.
 */
@Serializable(with = NodeDTOSerializer::class)
data class NodeDTO(
    val id: String,
    val type: String,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
) {
    /** Interpret the [type] string as a [NodeType] enum value. */
    fun nodeType(): NodeType = NodeType.from(type)
}

/**
 * Node type enumeration used at the configuration layer.
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
         * Convert a raw string into a [NodeType] enum value.
         *
         * Tolerant parsing:
         * - case-insensitive
         * - accepts snake_case, kebab-case, and compact/camel-like forms
         */
        fun from(raw: String?): NodeType {
            val norm = raw
                ?.trim()
                ?.replace(Regex("""[\s_\-]+"""), "_")
                ?.uppercase()
                ?: return UNKNOWN

            return when (norm) {
                "START" -> START
                "TEXT" -> TEXT

                "SINGLE_CHOICE", "SINGLECHOICE", "SINGLE_OPTION", "RADIO" ->
                    SINGLE_CHOICE

                "MULTI_CHOICE", "MULTICHOICE", "MULTI_OPTION", "CHECKBOX" ->
                    MULTI_CHOICE

                "AI", "LLM", "SLM" -> AI
                "REVIEW" -> REVIEW
                "DONE", "FINISH", "FINAL" -> DONE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Supported configuration formats for serialization and deserialization.
 */
enum class ConfigFormat {
    JSON,
    YAML,
    AUTO
}

/**
 * Loader and writer utilities for [SurveyConfig].
 */
object SurveyConfigLoader {

    /** Compact JSON instance used for reading and minimal writing. */
    internal val jsonCompact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Pretty-printing JSON instance used for human-friendly output. */
    internal val jsonPretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Create a YAML serializer with a given strictness. */
    internal fun yaml(strict: Boolean = false): Yaml =
        Yaml(
            configuration = YamlConfiguration(
                encodeDefaults = false,
                strictMode = strict
            )
        )

    /** Load [SurveyConfig] from an asset file. */
    fun fromAssets(
        context: Context,
        fileName: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        try {
            context.assets.open(fileName).bufferedReader(charset).use { reader ->
                val raw = reader.readText()
                fromString(text = raw, format = format, fileNameHint = fileName)
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Failed to load SurveyConfig from assets/$fileName: ${ex.message}",
                ex
            )
        }

    /** Load [SurveyConfig] from an asset file and validate immediately. */
    fun fromAssetsValidated(
        context: Context,
        fileName: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        fromAssets(context, fileName, charset, format).also { it.requireValid() }

    /** Load [SurveyConfig] from a regular file on disk. */
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
                fromString(text = raw, format = format, fileNameHint = file.name)
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Failed to load SurveyConfig from file '$path': ${ex.message}",
                ex
            )
        }

    /** Load [SurveyConfig] from a file and validate immediately. */
    fun fromFileValidated(
        path: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        fromFile(path, charset, format).also { it.requireValid() }

    /**
     * Parse [SurveyConfig] from a raw string.
     */
    fun fromString(
        text: String,
        format: ConfigFormat = ConfigFormat.AUTO,
        fileNameHint: String? = null
    ): SurveyConfig {
        val sanitized = text.normalize()
        val chosen = pickFormat(desired = format, fileName = fileNameHint, text = sanitized)

        return try {
            val cfg = when (chosen) {
                ConfigFormat.JSON ->
                    jsonCompact.decodeFromString(SurveyConfig.serializer(), sanitized)

                ConfigFormat.YAML ->
                    yaml(strict = false).decodeFromString(SurveyConfig.serializer(), sanitized)

                ConfigFormat.AUTO ->
                    error("AUTO should have been resolved before decoding; this is a bug.")
            }

            // Optional debug: warn if legacy two_step is used without qa_eval.
            val qaResolved = cfg.resolveQaEval()
            if (qaResolved.source == "two_step") {
                Log.w("SurveyConfigLoader", "Loaded legacy two_step config; please migrate to qa_eval.")
            } else if (qaResolved.source == "qa_eval") {
                Log.d("SurveyConfigLoader", "Loaded qa_eval config (mode=${qaResolved.mode}, trigger=${qaResolved.followupTrigger}).")
            }

            cfg
        } catch (ex: SerializationException) {
            val preview = sanitized.safePreview()
            throw IllegalArgumentException(
                "Parsing error (format=${chosen.name}). First 200 chars: $preview :: ${ex.message}",
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

    /** Parse [SurveyConfig] from a string and validate immediately. */
    fun fromStringValidated(
        text: String,
        format: ConfigFormat = ConfigFormat.AUTO,
        fileNameHint: String? = null
    ): SurveyConfig =
        fromString(text, format, fileNameHint).also { it.requireValid() }

    private fun pickFormat(
        desired: ConfigFormat,
        fileName: String? = null,
        text: String? = null
    ): ConfigFormat {
        if (desired != ConfigFormat.AUTO) return desired

        val lower = fileName?.lowercase().orEmpty()
        if (lower.endsWith(".json")) return ConfigFormat.JSON
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return ConfigFormat.YAML

        return text?.let(::sniffFormat) ?: ConfigFormat.JSON
    }

    /**
     * Heuristic format sniffing:
     *  - Leading '{' or '[' -> JSON
     *  - Leading '---', '- ' or typical "key: value" -> YAML
     *  - Otherwise fall back to JSON.
     */
    private fun sniffFormat(text: String): ConfigFormat {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ConfigFormat.JSON

        val firstNonEmpty = trimmed
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: ""

        if (firstNonEmpty.startsWith("---")) return ConfigFormat.YAML
        if (firstNonEmpty.startsWith("- ")) return ConfigFormat.YAML
        if (":" in firstNonEmpty && !firstNonEmpty.startsWith("{")) return ConfigFormat.YAML

        return ConfigFormat.JSON
    }

    /**
     * Normalize BOM and line endings for a raw text string.
     *
     * - Removes UTF-8 BOM if present.
     * - Converts CRLF/CR to LF.
     * - Trims trailing line breaks.
     */
    private fun String.normalize(): String {
        val s = if (this.isNotEmpty() && this[0] == '\uFEFF') this.drop(1) else this
        return s.replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')
    }

    /** Return a short preview string for error messages. */
    private fun String.safePreview(max: Int = 200): String =
        this.replace("\n", "\\n")
            .replace("\r", "\\r")
            .let { t -> if (t.length <= max) t else t.take(max) + "..." }
}

/* ───────────────────────────── Serializers ─────────────────────────────── */

private val StringListSer: KSerializer<List<String>> = ListSerializer(String.serializer())
private val NullableStringSer: KSerializer<String?> = String.serializer().nullable

/**
 * Serializer that accepts both node_id and nodeId, and encodes as node_id.
 */
private object PromptSerializer : KSerializer<SurveyConfig.Prompt> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Prompt") {
        element<String>("node_id", isOptional = true)
        element<String>("nodeId", isOptional = true)
        element<String>("prompt", isOptional = true)
        element<String>("template", isOptional = true) // legacy alias
    }

    override fun serialize(encoder: Encoder, value: SurveyConfig.Prompt) {
        encoder.encodeStructure(descriptor) {
            // Encode snake_case to keep YAML style stable.
            encodeStringElement(descriptor, 0, value.nodeId)
            encodeStringElement(descriptor, 2, value.prompt)
        }
    }

    override fun deserialize(decoder: Decoder): SurveyConfig.Prompt {
        var nodeIdSnake: String? = null
        var nodeIdCamel: String? = null
        var prompt: String? = null
        var template: String? = null

        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val idx = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> nodeIdSnake = decodeNullableSerializableElement(descriptor, 0, NullableStringSer)
                    1 -> nodeIdCamel = decodeNullableSerializableElement(descriptor, 1, NullableStringSer)
                    2 -> prompt = decodeNullableSerializableElement(descriptor, 2, NullableStringSer)
                    3 -> template = decodeNullableSerializableElement(descriptor, 3, NullableStringSer)
                    else -> Unit
                }
            }
        }

        val nodeId = (nodeIdSnake ?: nodeIdCamel ?: "").trim()
        val body = (prompt ?: template ?: "").trimEnd()
        return SurveyConfig.Prompt(nodeId = nodeId, prompt = body)
    }
}

/**
 * Serializer that accepts both start_id and startId, and encodes as start_id.
 */
private object GraphSerializer : KSerializer<SurveyConfig.Graph> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Graph") {
        element<String>("start_id", isOptional = true)
        element<String>("startId", isOptional = true)
        element<List<NodeDTO>>("nodes")
    }

    override fun serialize(encoder: Encoder, value: SurveyConfig.Graph) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.startId)
            encodeSerializableElement(descriptor, 2, ListSerializer(NodeDTO.serializer()), value.nodes)
        }
    }

    override fun deserialize(decoder: Decoder): SurveyConfig.Graph {
        var startSnake: String? = null
        var startCamel: String? = null
        var nodes: List<NodeDTO>? = null

        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val idx = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> startSnake = decodeNullableSerializableElement(descriptor, 0, NullableStringSer)
                    1 -> startCamel = decodeNullableSerializableElement(descriptor, 1, NullableStringSer)
                    2 -> nodes = decodeSerializableElement(descriptor, 2, ListSerializer(NodeDTO.serializer()))
                    else -> Unit
                }
            }
        }

        val startId = (startSnake ?: startCamel ?: "").trim()
        return SurveyConfig.Graph(startId = startId, nodes = nodes ?: emptyList())
    }
}

/**
 * Serializer that accepts both next_id and nextId, and encodes as next_id.
 */
private object NodeDTOSerializer : KSerializer<NodeDTO> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("NodeDTO") {
        element<String>("id")
        element<String>("type")
        element<String>("title", isOptional = true)
        element<String>("question", isOptional = true)
        element<List<String>>("options", isOptional = true)
        element<String?>("next_id", isOptional = true)
        element<String?>("nextId", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: NodeDTO) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.id)
            encodeStringElement(descriptor, 1, value.type)

            if (value.title.isNotBlank()) {
                encodeStringElement(descriptor, 2, value.title)
            }
            if (value.question.isNotBlank()) {
                encodeStringElement(descriptor, 3, value.question)
            }
            if (value.options.isNotEmpty()) {
                encodeSerializableElement(descriptor, 4, StringListSer, value.options)
            }

            // Encode snake_case to match YAML style.
            value.nextId?.trim()?.takeIf { it.isNotBlank() }?.let { next ->
                encodeNullableSerializableElement(descriptor, 5, NullableStringSer, next)
            }
        }
    }

    override fun deserialize(decoder: Decoder): NodeDTO {
        var id: String? = null
        var type: String? = null
        var title: String = ""
        var question: String = ""
        var options: List<String> = emptyList()
        var nextSnake: String? = null
        var nextCamel: String? = null

        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val idx = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> id = decodeStringElement(descriptor, 0)
                    1 -> type = decodeStringElement(descriptor, 1)
                    2 -> title = decodeStringElement(descriptor, 2)
                    3 -> question = decodeStringElement(descriptor, 3)
                    4 -> options = decodeSerializableElement(descriptor, 4, StringListSer)
                    5 -> nextSnake = decodeNullableSerializableElement(descriptor, 5, NullableStringSer)
                    6 -> nextCamel = decodeNullableSerializableElement(descriptor, 6, NullableStringSer)
                    else -> Unit
                }
            }
        }

        return NodeDTO(
            id = (id ?: "").trim(),
            type = (type ?: "").trim(),
            title = title,
            question = question,
            options = options,
            nextId = (nextSnake ?: nextCamel)?.trim()?.ifBlank { null }
        )
    }
}

/* ───────────────────────────── Stage helpers ────────────────────────────── */

/**
 * Return true if the string contains any delimiter that suggests a staged prompt id.
 */
private fun String.containsStageDelimiter(): Boolean =
    this.contains('#') || this.contains(':') || this.contains('/')

/**
 * Extract base node id from a staged prompt identifier.
 *
 * Examples:
 *  - "Q1#eval"      -> "Q1"
 *  - "Q1:followup"  -> "Q1"
 *  - "Q1/followup"  -> "Q1"
 *  - "Q1"           -> "Q1"
 */
private fun String.baseNodeId(): String {
    val raw = this.trim()
    if (raw.isEmpty()) return raw
    val cut = raw.indexOfFirst { it == '#' || it == ':' || it == '/' }
    return if (cut < 0) raw else raw.substring(0, cut).trim()
}

/**
 * Extract stage token from a staged prompt identifier.
 *
 * Examples:
 *  - "Q1#eval"      -> "eval"
 *  - "Q1:followup"  -> "followup"
 *  - "Q1/followup"  -> "followup"
 *  - "Q1"           -> null
 */
private fun String.stageTokenOrNull(): String? {
    val raw = this.trim()
    if (raw.isEmpty()) return null
    val cut = raw.indexOfFirst { it == '#' || it == ':' || it == '/' }
    if (cut < 0) return null
    if (cut == raw.lastIndex) return ""
    return raw.substring(cut + 1).trim()
}

/**
 * Convert a stage token to [SurveyConfig.PromptStage] using tolerant parsing.
 */
private fun String?.toPromptStage(): SurveyConfig.PromptStage {
    val t = this?.trim()?.lowercase().orEmpty()
    if (t.isBlank()) return SurveyConfig.PromptStage.BASE

    return when (t) {
        "base" -> SurveyConfig.PromptStage.BASE
        "eval", "evaluation", "judge", "score" -> SurveyConfig.PromptStage.EVAL
        "followup", "follow_up", "follow-up", "fu", "clarify" -> SurveyConfig.PromptStage.FOLLOWUP
        else -> SurveyConfig.PromptStage.UNKNOWN
    }
}
