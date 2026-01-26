/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Main ViewModel responsible for managing survey navigation and state.
 *
 *  Hardening (accident-rate reduction) upgrades in this revision:
 *   • Fix: Kotlin init-order NPE in reflection getter cache (getterCache now initialized before graph build).
 *   • Fix: Prompt normalization loop compile error (no `return@for`; uses labeled continue).
 *   • Option: HardeningOptions to reduce crash probability by failing open (skip bad DTOs) in release.
 *   • Option: Reflection caching can be disabled for ultra-safe mode.
 *   • Feature: onVoiceExported() implemented with policy-based safe routing to avoid mis-attachment.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.BuildConfig
import com.negi.survey.config.SurveyConfig
import com.negi.survey.screens.TwoStepPolicy
import com.negi.survey.screens.TwoStepPromptProvider
import java.lang.reflect.Method
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

private const val TAG = "SurveyVM"

/* ───────────────────────────── Graph Model ───────────────────────────── */

/**
 * Survey node types used by the runtime flow.
 */
enum class NodeType {
    START,
    TEXT,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    AI,
    REVIEW,
    DONE
}

/**
 * Runtime node model built from survey configuration.
 *
 * @property id Unique identifier of the node.
 * @property type Node type that determines which screen to show.
 * @property title Optional title used in the UI.
 * @property question Primary question text for this node.
 * @property options List of answer options for choice-based nodes.
 * @property nextId ID of the next node in the graph, or null if none.
 */
data class Node(
    val id: String,
    val type: NodeType,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
)

/* ───────────────────────────── Nav Keys ───────────────────────────── */

@Serializable object FlowHome : NavKey
@Serializable object FlowText : NavKey
@Serializable object FlowSingle : NavKey
@Serializable object FlowMulti : NavKey
@Serializable object FlowAI : NavKey
@Serializable object FlowReview : NavKey
@Serializable object FlowDone : NavKey

/* ───────────────────────────── UI Events ───────────────────────────── */

/**
 * Events emitted by the ViewModel for one-off UI feedback.
 */
sealed interface UiEvent {
    data class Snack(val message: String) : UiEvent
    data class Dialog(val title: String, val message: String) : UiEvent
}

/* ───────────────────────────── Prompts ───────────────────────────── */

private enum class PromptStage {
    BASE,
    EVAL,
    FOLLOWUP
}

/**
 * Normalized prompt template representation.
 *
 * @property baseNodeId Node id without any embedded stage suffix/prefix.
 * @property stage Prompt stage.
 * @property prompt Prompt body.
 * @property rawKey Original raw node key as it appeared in config (debug only).
 */
private data class PromptTemplate(
    val baseNodeId: String,
    val stage: PromptStage,
    val prompt: String,
    val rawKey: String
)

/* ───────────────────────────── Voice Export Policy ───────────────────────────── */

enum class VoiceExportPolicy {
    /** Strict: drop exports without a valid questionId (best for DEBUG). */
    STRICT_DROP,

    /** Safe: store under a dedicated bucket to avoid mis-attachment. */
    STORE_UNASSIGNED,

    /** Risky: attach to current nodeId (can mis-attach if export is delayed). */
    ATTACH_TO_CURRENT_NODE
}

/* ───────────────────────────── Hardening Options ───────────────────────────── */

/**
 * Hardening options that reduce crash probability by failing open in non-strict mode.
 *
 * NOTE:
 * - Default behavior is stricter in DEBUG builds and more tolerant in RELEASE builds.
 * - You can set ultraSafeMode=true to avoid reflection caching entirely.
 * - Voice export policy defaults:
 *   - DEBUG: STRICT_DROP (surface issues early)
 *   - RELEASE: STORE_UNASSIGNED (avoid wrong attachment)
 */
data class HardeningOptions(
    val debugLogs: Boolean = BuildConfig.DEBUG,
    val strictConfig: Boolean = BuildConfig.DEBUG,
    val skipInvalidGraphNodes: Boolean = !BuildConfig.DEBUG,
    val skipInvalidPromptDtos: Boolean = true,
    val ultraSafeMode: Boolean = false,

    val voiceExportPolicy: VoiceExportPolicy =
        if (BuildConfig.DEBUG) VoiceExportPolicy.STRICT_DROP else VoiceExportPolicy.STORE_UNASSIGNED,

    val unassignedVoiceBucketId: String = "__unassigned_audio__"
)

/* ───────────────────────────── Main ViewModel ───────────────────────────── */

open class SurveyViewModel(
    private val nav: NavBackStack<NavKey>,
    private val config: SurveyConfig,
    private val hardening: HardeningOptions = HardeningOptions()
) : ViewModel(), TwoStepPromptProvider {

    private val DEBUG_LOGS: Boolean = hardening.debugLogs
    private val STRICT_CONFIG: Boolean = hardening.strictConfig

    // ----------------------------------------------------------
    // DTO Access (No Kotlin-Reflect) — MUST be initialized BEFORE graph build
    // ----------------------------------------------------------

    /**
     * Cache: (Class -> methodNameLower -> Method)
     *
     * IMPORTANT:
     * This MUST be declared before graph/prompt parsing to avoid Kotlin init-order NPE.
     */
    private val getterCache: ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Method>> =
        ConcurrentHashMap()

    private fun Any.cachedNoArgMethod(name: String): Method? {
        val cls = this.javaClass

        val map = if (hardening.ultraSafeMode) {
            null
        } else {
            getterCache.getOrPut(cls) { ConcurrentHashMap() }
        }

        val key = name.lowercase(Locale.US)

        if (map != null) {
            map[key]?.let { return it }
        }

        val m = runCatching {
            cls.methods.firstOrNull { it.parameterTypes.isEmpty() && it.name.equals(name, ignoreCase = true) }
        }.getOrNull()

        if (m != null && map != null) {
            map[key] = m
        }
        return m
    }

    private fun Any.readStringGetter(names: List<String>): String {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries
                    .firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }
                    ?.value
                val s = (v as? String)?.trim()
                if (!s.isNullOrBlank()) return s
            }
        }

        for (n in names) {
            val cap = n.cap()
            val m = cachedNoArgMethod("get$cap") ?: cachedNoArgMethod(n) ?: continue
            val v = runCatching { m.invoke(this) }.getOrNull()
            if (v is String) return v.trim()
        }
        return ""
    }

    private fun Any.readStringListGetter(names: List<String>): List<String> {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries
                    .firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }
                    ?.value
                when (v) {
                    is List<*> -> return v.filterIsInstance<String>()
                    is Array<*> -> return v.filterIsInstance<String>()
                }
            }
        }

        for (n in names) {
            val cap = n.cap()
            val m = cachedNoArgMethod("get$cap") ?: cachedNoArgMethod(n) ?: continue
            val v = runCatching { m.invoke(this) }.getOrNull()
            when (v) {
                is List<*> -> return v.filterIsInstance<String>()
                is Array<*> -> return v.filterIsInstance<String>()
            }
        }
        return emptyList()
    }

    private fun Any.readStringGetterOrNull(names: List<String>): String? =
        readStringGetter(names).ifBlank { null }

    private fun Any.readObjectGetterOrNull(names: List<String>): Any? {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries
                    .firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }
                    ?.value
                if (v != null) return v
            }
        }

        for (n in names) {
            val cap = n.cap()
            val m = cachedNoArgMethod("get$cap") ?: cachedNoArgMethod(n) ?: continue
            return runCatching { m.invoke(this) }.getOrNull()
        }
        return null
    }

    private fun Any.readBooleanGetter(names: List<String>, defaultValue: Boolean): Boolean {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries
                    .firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }
                    ?.value
                when (v) {
                    is Boolean -> return v
                    is Number -> return v.toInt() != 0
                    is String -> {
                        val s = v.trim().lowercase(Locale.US)
                        when (s) {
                            "true", "1", "yes", "y" -> return true
                            "false", "0", "no", "n" -> return false
                        }
                    }
                }
            }
        }

        for (n in names) {
            val cap = n.cap()
            val m = cachedNoArgMethod("get$cap") ?: cachedNoArgMethod(n) ?: continue
            val v = runCatching { m.invoke(this) }.getOrNull()
            when (v) {
                is Boolean -> return v
                is String -> {
                    val s = v.trim().lowercase(Locale.US)
                    when (s) {
                        "true", "1", "yes", "y" -> return true
                        "false", "0", "no", "n" -> return false
                    }
                }
                is Number -> return v.toInt() != 0
            }
        }
        return defaultValue
    }

    private fun Any.readIntGetter(names: List<String>, defaultValue: Int): Int {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries
                    .firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }
                    ?.value
                when (v) {
                    is Int -> return v
                    is Number -> return v.toInt()
                    is String -> v.trim().toIntOrNull()?.let { return it }
                }
            }
        }

        for (n in names) {
            val cap = n.cap()
            val m = cachedNoArgMethod("get$cap") ?: cachedNoArgMethod(n) ?: continue
            val v = runCatching { m.invoke(this) }.getOrNull()
            when (v) {
                is Int -> return v
                is Number -> return v.toInt()
                is String -> v.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    private fun String.cap(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    // ----------------------------------------------------------
    // Graph + Prompts (built from config)
    // ----------------------------------------------------------

    private val graph: Map<String, Node> = buildGraphFromConfig(config)

    val nodes: Map<String, Node>
        get() = graph

    private val startId: String = config.graph.startId

    private val perNodeTwoStepPolicy = ConcurrentHashMap<String, TwoStepPolicy>()
    private val globalTwoStepPolicy: TwoStepPolicy = readTwoStepPolicyFromConfig(config)
    private val promptTemplates: List<PromptTemplate> = normalizePromptTemplates(config)
    private val templateRegexCache = ConcurrentHashMap<String, Regex>()

    // ----------------------------------------------------------
    // Runtime state
    // ----------------------------------------------------------

    private val nodeStack = ArrayDeque<String>()

    private val _sessionId = MutableStateFlow(0L)
    val sessionId: StateFlow<Long> = _sessionId.asStateFlow()

    private val _surveyUuid = MutableStateFlow(UUID.randomUUID().toString())
    val surveyUuid: StateFlow<String> = _surveyUuid.asStateFlow()

    private fun regenerateSurveyUuid() {
        _surveyUuid.value = UUID.randomUUID().toString()
    }

    private val _currentNode = MutableStateFlow(Node(id = "Loading", type = NodeType.START))
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    val currentNodeId: String
        get() = _currentNode.value.id

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun emitSnack(message: String) { _events.tryEmit(UiEvent.Snack(message)) }
    fun emitDialog(title: String, message: String) { _events.tryEmit(UiEvent.Dialog(title, message)) }

    /* ───────────────────────────── Questions ───────────────────────────── */

    private val _questions = MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val questions: StateFlow<Map<String, String>> = _questions.asStateFlow()

    fun setQuestion(text: String, key: String) {
        _questions.update { old -> old.mutableLinked().apply { put(key, text) } }
    }

    fun getQuestion(key: String): String = questions.value[key].orEmpty()

    fun resetQuestions() {
        _questions.value = LinkedHashMap()
    }

    /* ───────────────────────────── Answers ───────────────────────────── */

    private val _answers = MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()

    fun setAnswer(text: String, key: String) {
        _answers.update { old -> old.mutableLinked().apply { put(key, text) } }
    }

    fun getAnswer(key: String): String = answers.value[key].orEmpty()

    fun clearAnswer(key: String) {
        _answers.update { old -> old.mutableLinked().apply { remove(key) } }
    }

    fun resetAnswers() {
        _answers.value = LinkedHashMap()
    }

    /* ───────────────────────────── Choice Selections ───────────────────────────── */

    private val _single = MutableStateFlow<String?>(null)
    val single: StateFlow<String?> = _single.asStateFlow()

    fun setSingleChoice(opt: String?) { _single.value = opt }

    private val _multi = MutableStateFlow<Set<String>>(emptySet())
    val multi: StateFlow<Set<String>> = _multi.asStateFlow()

    fun toggleMultiChoice(opt: String) {
        _multi.update { cur ->
            cur.toMutableSet().apply { if (!add(opt)) remove(opt) }
        }
    }

    fun clearSelections() {
        _single.value = null
        _multi.value = emptySet()
    }

    /* ───────────────────────────── Follow-ups ───────────────────────────── */

    data class FollowupEntry(
        val question: String,
        val answer: String? = null,
        val askedAt: Long = System.currentTimeMillis(),
        val answeredAt: Long? = null
    )

    private val _followups = MutableStateFlow<Map<String, List<FollowupEntry>>>(LinkedHashMap())
    val followups: StateFlow<Map<String, List<FollowupEntry>>> = _followups.asStateFlow()

    fun addFollowupQuestion(nodeId: String, question: String, dedupAdjacent: Boolean = true) {
        val q = question.trim()
        if (q.isBlank()) return

        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable.getOrPut(nodeId) { mutableListOf() }
            val last = list.lastOrNull()
            if (!(dedupAdjacent && last?.question == q)) {
                list.add(FollowupEntry(question = q))
            }
            mutable.toImmutableLists()
        }
    }

    fun answerLastFollowup(nodeId: String, answer: String) {
        val a = answer.trim()
        if (a.isBlank()) return

        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[nodeId] ?: return@update old
            val idx = list.indexOfLast { it.answer == null }
            if (idx < 0) return@update old
            list[idx] = list[idx].copy(answer = a, answeredAt = System.currentTimeMillis())
            mutable.toImmutableLists()
        }
    }

    fun clearFollowups(nodeId: String) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            mutable.remove(nodeId)
            mutable.toImmutableLists()
        }
    }

    fun resetFollowups() {
        _followups.value = LinkedHashMap()
    }

    /* ───────────────────────────── Recorded Audio Refs ───────────────────────────── */

    data class AudioRef(
        val surveyId: String,
        val questionId: String,
        val fileName: String,
        val createdAt: Long = System.currentTimeMillis(),
        val byteSize: Long? = null,
        val checksum: String? = null
    )

    private val _recordedAudioRefs = MutableStateFlow<Map<String, List<AudioRef>>>(LinkedHashMap())
    val recordedAudioRefs: StateFlow<Map<String, List<AudioRef>>> = _recordedAudioRefs.asStateFlow()

    /**
     * Records a voice export reference in the survey state.
     *
     * Accident-rate reduction:
     * - If questionId is missing/blank, behavior depends on [hardening.voiceExportPolicy].
     * - Default in RELEASE: store under a dedicated unassigned bucket to avoid mis-attachment.
     */
    @Synchronized
    fun onVoiceExported(
        questionId: String?,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null,
        replace: Boolean = false
    ) {
        val qidFromArg = questionId?.trim().orEmpty()

        val resolvedQid = when {
            qidFromArg.isNotBlank() -> qidFromArg
            else -> when (hardening.voiceExportPolicy) {
                VoiceExportPolicy.STRICT_DROP -> {
                    if (DEBUG_LOGS) Log.w(TAG, "onVoiceExported: missing questionId -> dropped. file=$fileName")
                    emitSnack("Voice export missing questionId; ignored to avoid mis-attachment.")
                    return
                }
                VoiceExportPolicy.STORE_UNASSIGNED -> hardening.unassignedVoiceBucketId
                VoiceExportPolicy.ATTACH_TO_CURRENT_NODE -> currentNodeId
            }
        }

        // Validate that questionId exists in the graph (fail-open in non-strict mode).
        val finalQid = if (graph.containsKey(resolvedQid) || resolvedQid == hardening.unassignedVoiceBucketId) {
            resolvedQid
        } else {
            if (STRICT_CONFIG) {
                error("onVoiceExported: unknown questionId='$resolvedQid' (file=$fileName)")
            }
            if (DEBUG_LOGS) Log.w(TAG, "onVoiceExported: unknown questionId='$resolvedQid' -> storing unassigned. file=$fileName")
            hardening.unassignedVoiceBucketId
        }

        if (replace) {
            removeAudioRefsForQuestionInThisRun(finalQid)
        }

        addAudioRef(
            questionId = finalQid,
            fileName = fileName,
            byteSize = byteSize,
            checksum = checksum,
            dedupByFileName = true
        )
    }

    /**
     * Adds a single audio reference entry.
     *
     * @param questionId Node id the audio belongs to.
     * @param fileName File name of the exported audio.
     * @param byteSize Optional file size.
     * @param checksum Optional checksum.
     * @param dedupByFileName If true, skip adding if same fileName already exists for the current run.
     */
    @Synchronized
    fun addAudioRef(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null,
        dedupByFileName: Boolean = true
    ) {
        val sid = surveyUuid.value
        val fn = fileName.trim()
        if (fn.isBlank()) return

        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            val list = mutable.getOrPut(questionId) { mutableListOf() }
            val existsSameRun = list.any { it.fileName == fn && it.surveyId == sid }
            if (!dedupByFileName || !existsSameRun) {
                list.add(
                    AudioRef(
                        surveyId = sid,
                        questionId = questionId,
                        fileName = fn,
                        byteSize = byteSize,
                        checksum = checksum
                    )
                )
            }
            mutable.toImmutableLists()
        }

        if (DEBUG_LOGS) Log.d(TAG, "addAudioRef -> q=$questionId, file=$fn, sid=$sid")
    }

    /**
     * Removes audio refs for the given questionId in the current survey run (same surveyUuid).
     */
    @Synchronized
    private fun removeAudioRefsForQuestionInThisRun(questionId: String) {
        val sid = surveyUuid.value
        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            val list = mutable[questionId] ?: return@update old
            list.removeAll { it.surveyId == sid }
            if (list.isEmpty()) mutable.remove(questionId)
            mutable.toImmutableLists()
        }
    }

    @Synchronized
    fun resetAudioRefs() {
        _recordedAudioRefs.value = LinkedHashMap()
        if (DEBUG_LOGS) Log.d(TAG, "resetAudioRefs -> cleared")
    }

    fun getAudioRefs(questionId: String): List<AudioRef> =
        recordedAudioRefs.value[questionId].orEmpty()

    /* ───────────────────────────── Two-step Prompt Provider ───────────────────────────── */

    fun setTwoStepPolicyOverride(nodeId: String, policy: TwoStepPolicy) {
        perNodeTwoStepPolicy[nodeId] = policy
    }

    fun clearTwoStepPolicyOverrides() {
        perNodeTwoStepPolicy.clear()
    }

    fun getPrompt(nodeId: String, question: String, answer: String): String {
        val template =
            findTemplate(nodeId, PromptStage.BASE)
                ?: findTemplate(nodeId, PromptStage.EVAL)
                ?: findAnyStageTemplateFallback(nodeId)

        if (template == null) {
            val msg = "No prompt defined for nodeId=$nodeId (BASE/EVAL/ANY)."
            Log.e(TAG, msg)
            if (STRICT_CONFIG) error(msg) else return buildDefaultBasePrompt(nodeId, question, answer)
        }

        return renderTemplate(
            template = template,
            vars = mapOf(
                "QUESTION" to question.trim(),
                "ANSWER" to answer.trim(),
                "NODE_ID" to nodeId.trim()
            )
        )
    }

    override fun buildEvalPrompt(nodeId: String, question: String, answer: String): String {
        val template =
            findTemplate(nodeId, PromptStage.EVAL)
                ?: findTemplate(nodeId, PromptStage.BASE)
                ?: findAnyStageTemplateFallback(nodeId)

        if (template == null) {
            val msg = "No prompt defined for nodeId=$nodeId (EVAL/BASE/ANY)."
            Log.e(TAG, msg)
            if (STRICT_CONFIG) error(msg) else return buildDefaultEvalPrompt(nodeId, question, answer)
        }

        return renderTemplate(
            template = template,
            vars = mapOf(
                "QUESTION" to question.trim(),
                "ANSWER" to answer.trim(),
                "NODE_ID" to nodeId.trim()
            )
        )
    }

    override fun buildFollowupPrompt(
        nodeId: String,
        question: String,
        answer: String,
        evalJsonPretty: String
    ): String {
        val template = findTemplate(nodeId, PromptStage.FOLLOWUP)
        return if (template != null) {
            renderTemplate(
                template = template,
                vars = mapOf(
                    "QUESTION" to question.trim(),
                    "ANSWER" to answer.trim(),
                    "NODE_ID" to nodeId.trim(),
                    "EVAL_JSON" to evalJsonPretty.trim()
                )
            )
        } else {
            buildDefaultFollowupPrompt(nodeId, question, answer, evalJsonPretty)
        }
    }

    override fun twoStepPolicy(nodeId: String): TwoStepPolicy {
        return perNodeTwoStepPolicy[nodeId] ?: globalTwoStepPolicy
    }

    private fun renderTemplate(template: String, vars: Map<String, String>): String {
        var out = template
        for ((key, value) in vars) {
            val rx = templateRegexCache.getOrPut(key) {
                Regex("\\{\\{\\s*${Regex.escape(key)}\\s*\\}\\}")
            }
            out = out.replace(rx) { value }
        }
        return out
    }

    private fun buildDefaultBasePrompt(nodeId: String, question: String, answer: String): String {
        return """
            You are a survey expert.
            Task: Evaluate if the respondent's answer matches the intended question.
            Output: JSON with fields: score (0-100), reasons, followups (0-3).

            NodeId: ${nodeId.trim()}
            Question: ${question.trim()}
            Answer: ${answer.trim()}
        """.trimIndent()
    }

    private fun buildDefaultEvalPrompt(nodeId: String, question: String, answer: String): String {
        return buildDefaultBasePrompt(nodeId, question, answer)
    }

    private fun buildDefaultFollowupPrompt(
        nodeId: String,
        question: String,
        answer: String,
        evalJsonPretty: String
    ): String {
        return """
            You are a survey expert.
            Task: Generate exactly ONE short follow-up question that clarifies the respondent's original answer to the SAME question.
            Rules:
            - Output plain text only (no JSON, no markdown).
            - Keep it single-scope and answerable immediately.
            - Do not introduce new topics. Do not ask multiple questions.

            NodeId: ${nodeId.trim()}
            Question: ${question.trim()}
            Answer: ${answer.trim()}
            Eval JSON:
            ${evalJsonPretty.trim()}
        """.trimIndent()
    }

    private fun findTemplate(nodeId: String, stage: PromptStage): String? {
        val n = nodeId.trim()
        return promptTemplates.firstOrNull { it.baseNodeId == n && it.stage == stage && it.prompt.isNotBlank() }?.prompt
    }

    private fun findAnyStageTemplateFallback(nodeId: String): String? {
        val n = nodeId.trim()
        return promptTemplates.firstOrNull { it.baseNodeId == n && it.prompt.isNotBlank() }?.prompt
    }

    /* ───────────────────────────── Navigation ───────────────────────────── */

    private fun navKeyFor(node: Node): NavKey =
        when (node.type) {
            NodeType.START -> FlowHome
            NodeType.TEXT -> FlowText
            NodeType.SINGLE_CHOICE -> FlowSingle
            NodeType.MULTI_CHOICE -> FlowMulti
            NodeType.AI -> FlowAI
            NodeType.REVIEW -> FlowReview
            NodeType.DONE -> FlowDone
        }

    private fun safePopNavOne() {
        if (nav.size > 0) nav.removeAt(nav.size - 1)
    }

    @Synchronized
    private fun push(node: Node) {
        _currentNode.value = node
        nodeStack.addLast(node.id)

        clearSelections()

        nav.add(navKeyFor(node))
        updateCanGoBack()

        if (DEBUG_LOGS) Log.d(TAG, "push -> ${node.id}, navSize=${nav.size}, stackSize=${nodeStack.size}")
    }

    private fun ensureQuestion(id: String) {
        if (getQuestion(id).isEmpty()) {
            val questionText = nodeOf(id).question
            if (questionText.isNotEmpty()) setQuestion(questionText, id)
        }
    }

    @Synchronized
    fun goto(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)
        push(node)
    }

    @Synchronized
    fun resetToStart() {
        regenerateSurveyUuid()

        resetQuestions()
        resetAnswers()
        resetFollowups()
        resetAudioRefs()
        clearSelections()

        nodeStack.clear()

        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.addLast(start.id)

        resetNavToStart(start)
        updateCanGoBack()

        _sessionId.update { it + 1 }

        if (DEBUG_LOGS) {
            Log.d(TAG, "resetToStart -> ${start.id}, session=${_sessionId.value}, uuid=${_surveyUuid.value}")
        }
    }

    @Synchronized
    fun backToPrevious() {
        if (nodeStack.size <= 1) {
            if (DEBUG_LOGS) Log.d(TAG, "backToPrevious: at root (no-op)")
            return
        }

        safePopNavOne()
        nodeStack.removeLast()

        val prevId = nodeStack.last()
        _currentNode.value = nodeOf(prevId)
        updateCanGoBack()

        clearSelections()

        if (DEBUG_LOGS) Log.d(TAG, "backToPrevious -> $prevId")
    }

    @Synchronized
    fun advanceToNext() {
        val cur = _currentNode.value
        val nextId = cur.nextId ?: run {
            if (DEBUG_LOGS) Log.d(TAG, "advanceToNext: no nextId from ${cur.id}")
            return
        }

        if (!graph.containsKey(nextId)) {
            error("nextId '$nextId' from node '${cur.id}' does not exist in graph (graphSize=${graph.size}).")
        }

        ensureQuestion(nextId)
        push(nodeOf(nextId))
    }

    private fun nodeOf(id: String): Node =
        graph[id] ?: error("Node not found: id=$id (graphSize=${graph.size})")

    private fun updateCanGoBack() {
        _canGoBack.value = nodeStack.size > 1
    }

    private fun resetNavToStart(start: Node) {
        while (nav.size > 0) nav.removeAt(nav.size - 1)
        nav.add(navKeyFor(start))
        if (DEBUG_LOGS) Log.d(TAG, "resetNavToStart -> navSize=${nav.size}")
    }

    private fun sanitizePreseededNavForStart(start: Node) {
        val startKey = navKeyFor(start)

        if (nav.size == 0) {
            nav.add(startKey)
            return
        }

        val last = runCatching { nav[nav.size - 1] }.getOrNull()
        if (last != startKey) {
            Log.w(TAG, "init: nav pre-seeded but last != startKey -> resetting to start. navSize=${nav.size}, last=$last, startKey=$startKey")
            resetNavToStart(start)
            return
        }

        if (nav.size > 1) {
            Log.w(TAG, "init: nav pre-seeded with extra entries (navSize=${nav.size}) -> resetting to start to avoid desync with nodeStack.")
            resetNavToStart(start)
        }
    }

    /* ───────────────────────────── Initialization ───────────────────────────── */

    init {
        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.clear()
        nodeStack.addLast(start.id)

        sanitizePreseededNavForStart(start)
        updateCanGoBack()

        if (DEBUG_LOGS) {
            Log.d(
                TAG,
                "init -> start=${start.id}, session=${_sessionId.value}, uuid=${_surveyUuid.value}, navSize=${nav.size}, " +
                        "nodes=${graph.size}, prompts=${promptTemplates.size}, globalTwoStep=$globalTwoStepPolicy, hardening=$hardening"
            )
        }
    }

    /* ───────────────────────────── Config Parsing Helpers ───────────────────────────── */

    private fun buildGraphFromConfig(cfg: SurveyConfig): Map<String, Node> {
        val rawNodes = cfg.graph.nodes
        val capacity = (rawNodes as? Collection<*>)?.size ?: 16
        val built = LinkedHashMap<String, Node>(capacity)

        for (dto in rawNodes) {
            val any = dto as? Any ?: continue

            val id = any.readStringGetter(listOf("id", "nodeId", "key")).trim()
            if (id.isBlank()) {
                val msg = "Graph node DTO missing id: ${any.javaClass.name}"
                Log.e(TAG, msg)
                if (STRICT_CONFIG) error(msg)
                if (hardening.skipInvalidGraphNodes) continue
                continue
            }

            val typeRaw = any.readStringGetter(listOf("type", "nodeType")).ifBlank { "TEXT" }
            val type = runCatching { NodeType.valueOf(typeRaw.trim().uppercase(Locale.US)) }
                .getOrElse {
                    Log.w(TAG, "Unknown node type='$typeRaw' for id=$id -> defaulting to TEXT")
                    NodeType.TEXT
                }

            val title = any.readStringGetter(listOf("title", "label"))
            val question = any.readStringGetter(listOf("question", "text", "prompt"))
            val options = any.readStringListGetter(listOf("options", "choices"))
            val nextId = any.readStringGetterOrNull(listOf("nextId", "next", "nextNodeId"))

            built[id] = Node(
                id = id,
                type = type,
                title = title,
                question = question,
                options = options,
                nextId = nextId
            )
        }

        if (built.isEmpty()) {
            Log.w(TAG, "buildGraphFromConfig -> 0 nodes parsed from config.graph.nodes")
        }

        return built
    }

    private fun normalizePromptTemplates(cfg: SurveyConfig): List<PromptTemplate> {
        val raw = cfg.prompts
        val capacity = (raw as? Collection<*>)?.size ?: 16
        val out = ArrayList<PromptTemplate>(capacity)

        PROMPTS@ for (dto in raw) {
            val any = dto as? Any ?: continue@PROMPTS

            val rawNodeKey = any.readStringGetter(listOf("nodeId", "id", "key")).trim()
            if (rawNodeKey.isBlank()) {
                Log.w(TAG, "Prompt DTO missing nodeId/id/key: ${any.javaClass.name}")
                if (hardening.skipInvalidPromptDtos) continue@PROMPTS
                if (STRICT_CONFIG) error("Prompt DTO missing nodeId/id/key: ${any.javaClass.name}")
                continue@PROMPTS
            }

            val promptText = any.readStringGetter(listOf("prompt", "template", "text", "body"))
            if (promptText.isBlank()) continue@PROMPTS

            val rawStage = any.readStringGetterOrNull(listOf("stage", "kind", "mode", "type"))
            val (baseNodeId, stage) = decodePromptNodeAndStage(rawNodeKey, rawStage)

            out.add(
                PromptTemplate(
                    baseNodeId = baseNodeId,
                    stage = stage,
                    prompt = promptText,
                    rawKey = rawNodeKey
                )
            )
        }

        if (out.isEmpty()) {
            Log.w(TAG, "normalizePromptTemplates -> 0 templates (config.prompts was empty or unparseable)")
        } else if (DEBUG_LOGS) {
            val stageCount = out.groupingBy { it.stage }.eachCount()
            Log.d(
                TAG,
                "normalizePromptTemplates -> total=${out.size}, stages=$stageCount, distinctNodes=${out.map { it.baseNodeId }.distinct().size}"
            )
        }

        return out
    }

    private fun decodePromptNodeAndStage(nodeKeyRaw: String, stageRaw: String?): Pair<String, PromptStage> {
        val rawKey = nodeKeyRaw.trim()
        val stageField = stageRaw?.trim()?.lowercase(Locale.US)

        val (baseFromKey, stageFromKey) = decodeStageFromNodeKey(rawKey)

        val stageFromField = when (stageField) {
            "eval" -> PromptStage.EVAL
            "followup", "follow_up", "follow-up", "fu" -> PromptStage.FOLLOWUP
            "base", null, "" -> PromptStage.BASE
            else -> PromptStage.BASE
        }

        val stage = if (!stageField.isNullOrBlank()) stageFromField else (stageFromKey ?: PromptStage.BASE)
        return baseFromKey to stage
    }

    private fun decodeStageFromNodeKey(nodeKey: String): Pair<String, PromptStage?> {
        val k = nodeKey.trim()

        val partsHash = k.split("#", limit = 2)
        if (partsHash.size == 2) {
            val st = partsHash[1].toStageOrNull()
            if (st != null) return partsHash[0] to st
        }

        val partsColon = k.split(":", limit = 2)
        if (partsColon.size == 2) {
            val left = partsColon[0]
            val right = partsColon[1]
            val leftStage = left.toStageOrNull()
            val rightStage = right.toStageOrNull()
            return when {
                rightStage != null -> left to rightStage
                leftStage != null -> right to leftStage
                else -> k to null
            }
        }

        val partsDot = k.split(".", limit = 2)
        if (partsDot.size == 2) {
            val rightStage = partsDot[1].toStageOrNull()
            if (rightStage != null) return partsDot[0] to rightStage
        }

        val slash = k.split("/", limit = 2)
        if (slash.size == 2) {
            val leftStage = slash[0].toStageOrNull()
            if (leftStage != null) return slash[1] to leftStage
        }

        listOf("_", "-").forEach { sep ->
            val p = k.split(sep, limit = 2)
            if (p.size == 2) {
                val rightStage = p[1].toStageOrNull()
                if (rightStage != null) return p[0] to rightStage
            }
        }

        return k to null
    }

    private fun String.toStageOrNull(): PromptStage? {
        return when (this.trim().lowercase(Locale.US)) {
            "eval" -> PromptStage.EVAL
            "followup", "follow_up", "follow-up", "fu" -> PromptStage.FOLLOWUP
            "base" -> PromptStage.BASE
            else -> null
        }
    }

    private fun defaultGlobalTwoStepPolicy(): TwoStepPolicy {
        return TwoStepPolicy(
            enabled = false,
            okScoreThreshold = 85,
            skipFollowupWhenOk = true
        )
    }

    private fun readTwoStepPolicyFromConfig(cfg: SurveyConfig): TwoStepPolicy {
        val root = cfg as Any
        val twoStepObj = root.readObjectGetterOrNull(listOf("twoStep", "two_step", "twoStepConfig", "twoStepPolicy"))
            ?: return defaultGlobalTwoStepPolicy()

        val enabled = twoStepObj.readBooleanGetter(listOf("enabled", "isEnabled"), defaultValue = false)

        val threshold = twoStepObj.readIntGetter(
            listOf("evalOkScoreThreshold", "eval_ok_score_threshold", "okScoreThreshold", "threshold"),
            defaultValue = 85
        )

        val skip = twoStepObj.readBooleanGetter(
            listOf("skipFollowupWhenOk", "skip_followup_when_ok", "skipFollowUpWhenOk"),
            defaultValue = true
        )

        return TwoStepPolicy(
            enabled = enabled,
            okScoreThreshold = threshold,
            skipFollowupWhenOk = skip
        )
    }

    /* ───────────────────────────── Map Helpers ───────────────────────────── */

    private fun Map<String, String>.mutableLinked(): LinkedHashMap<String, String> = LinkedHashMap(this)

    private fun <T> Map<String, List<T>>.mutableLinkedLists(): LinkedHashMap<String, MutableList<T>> {
        val result = LinkedHashMap<String, MutableList<T>>()
        for ((key, value) in this) result[key] = value.toMutableList()
        return result
    }

    private fun <T> LinkedHashMap<String, MutableList<T>>.toImmutableLists(): Map<String, List<T>> =
        this.mapValues { (_, list) -> list.toList() }
}
