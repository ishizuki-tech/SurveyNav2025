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
 *  Key upgrade points in this version:
 *   • Adds Two-step prompting support: EVAL -> optional Follow-up.
 *   • Reads `two_step` knobs from SurveyConfig and exposes them via TwoStepPolicy.
 *   • Keeps audio manifest (recordedAudioRefs) as stable export truth.
 *   • Provides helper APIs for run-scoped retrieval and replace semantics.
 *   • Avoids double-pushing FlowHome when NavBackStack is pre-seeded.
 *   • Avoids reliance on NavBackStack.clear() for broader compatibility.
 *
 *  Notes:
 *   • Prompt templates are resolved by stage with robust fallbacks.
 *   • This ViewModel intentionally does not depend on file-system scans.
 *     Physical WAV discovery remains an Export/Repository responsibility.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.SurveyConfig
import com.negi.survey.screens.TwoStepPolicy
import com.negi.survey.screens.TwoStepPromptProvider
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
 *
 * These values represent the logical type of nodes in the survey graph.
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
 * This is the in-memory representation of a survey node that the
 * ViewModel manipulates during the flow.
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

/**
 * NavKey definitions for each flow node destination.
 */
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

/**
 * Prompt stages used by the Two-step flow.
 */
private enum class PromptStage {
    BASE,
    EVAL,
    FOLLOWUP
}

/**
 * Normalized prompt template representation.
 */
private data class PromptTemplate(
    val nodeId: String,
    val stage: PromptStage,
    val prompt: String
)

/* ───────────────────────────── Main ViewModel ───────────────────────────── */

/**
 * Main ViewModel responsible for managing survey navigation and state.
 *
 * Responsibilities:
 * - Tracks the current node and navigation history.
 * - Keeps questions and answers per node.
 * - Manages AI follow-up questions and answers.
 * - Tracks recorded audio references per node (logical manifest).
 * - Exposes navigation helpers (advance, back, reset).
 * - Provides a stable UUID per survey run for export correlation.
 * - Builds prompts for BASE/EVAL/FOLLOWUP stages (Two-step).
 *
 * @property nav Navigation back-stack.
 * @property config Survey configuration loaded from JSON/YAML.
 */
open class SurveyViewModel(
    private val nav: NavBackStack<NavKey>,
    private val config: SurveyConfig
) : ViewModel(), TwoStepPromptProvider {

    /**
     * Survey graph as a map from node ID to [Node].
     *
     * NOTE:
     * Must be initialized eagerly (val cannot be assigned in init).
     */
    private val graph: Map<String, Node> = buildGraphFromConfig(config)

    /**
     * Read-only view of the runtime survey graph, keyed by node ID.
     */
    val nodes: Map<String, Node>
        get() = graph

    /**
     * ID of the starting node defined in [SurveyConfig.graph.startId].
     */
    private val startId: String = config.graph.startId

    /**
     * Internal stack that tracks the sequence of visited node IDs.
     *
     * The last element corresponds to the currently active node.
     */
    private val nodeStack = ArrayDeque<String>()

    /**
     * Monotonically increasing survey session ID.
     */
    private val _sessionId = MutableStateFlow(0L)
    val sessionId: StateFlow<Long> = _sessionId.asStateFlow()

    /**
     * Stable UUID for the active survey run.
     */
    private val _surveyUuid = MutableStateFlow(UUID.randomUUID().toString())
    val surveyUuid: StateFlow<String> = _surveyUuid.asStateFlow()

    /**
     * Regenerate the survey UUID for a brand-new run.
     */
    private fun regenerateSurveyUuid() {
        _surveyUuid.value = UUID.randomUUID().toString()
    }

    /**
     * StateFlow representing the currently active [Node].
     */
    private val _currentNode = MutableStateFlow(
        Node(id = "Loading", type = NodeType.START)
    )
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    /**
     * Convenience accessor for the current node ID.
     */
    val currentNodeId: String
        get() = _currentNode.value.id

    /**
     * Whether backwards navigation is currently possible.
     */
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /**
     * UI-level event stream (snackbars, dialogs, etc.).
     */
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

    /**
     * Clear both single- and multi-choice selections for the current node.
     *
     * NOTE:
     * This is intentionally aggressive. If you want "back preserves selection",
     * remove calls to this in back/replace/push.
     */
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

    fun answerFollowupAt(nodeId: String, index: Int, answer: String) {
        val a = answer.trim()
        if (a.isBlank()) return

        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[nodeId] ?: return@update old
            if (index !in list.indices) return@update old
            list[index] = list[index].copy(answer = a, answeredAt = System.currentTimeMillis())
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
                list.add(AudioRef(surveyId = sid, questionId = questionId, fileName = fn, byteSize = byteSize, checksum = checksum))
            }
            mutable.toImmutableLists()
        }

        Log.d(TAG, "addAudioRef -> q=$questionId, file=$fn, sid=$sid")
    }

    @Synchronized
    fun replaceAudioRef(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null
    ) {
        val sid = surveyUuid.value
        val fn = fileName.trim()
        if (fn.isBlank()) return

        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            mutable[questionId] = mutableListOf(
                AudioRef(surveyId = sid, questionId = questionId, fileName = fn, byteSize = byteSize, checksum = checksum)
            )
            mutable.toImmutableLists()
        }

        Log.d(TAG, "replaceAudioRef -> q=$questionId, file=$fn, sid=$sid")
    }

    @Synchronized
    fun removeAudioRef(questionId: String, fileName: String) {
        val fn = fileName.trim()
        if (fn.isBlank()) return

        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            val list = mutable[questionId] ?: return@update old
            list.removeAll { it.fileName == fn }
            if (list.isEmpty()) mutable.remove(questionId)
            mutable.toImmutableLists()
        }

        Log.d(TAG, "removeAudioRef -> q=$questionId, file=$fn")
    }

    @Synchronized
    fun clearAudioRefs(questionId: String) {
        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            mutable.remove(questionId)
            mutable.toImmutableLists()
        }
        Log.d(TAG, "clearAudioRefs -> q=$questionId")
    }

    @Synchronized
    fun resetAudioRefs() {
        _recordedAudioRefs.value = LinkedHashMap()
        Log.d(TAG, "resetAudioRefs -> cleared")
    }

    fun getAudioRefs(questionId: String): List<AudioRef> =
        recordedAudioRefs.value[questionId].orEmpty()

    fun getAudioRefsForRun(surveyId: String = surveyUuid.value): Map<String, List<AudioRef>> {
        return recordedAudioRefs.value
            .mapValues { (_, list) -> list.filter { it.surveyId == surveyId } }
            .filterValues { it.isNotEmpty() }
    }

    fun getAudioRefsForRunFlat(surveyId: String = surveyUuid.value): List<AudioRef> {
        return getAudioRefsForRun(surveyId).values.flatten().sortedBy { it.createdAt }
    }

    fun hasAudioRef(questionId: String, surveyId: String = surveyUuid.value): Boolean {
        return getAudioRefs(questionId).any { it.surveyId == surveyId }
    }

    fun onVoiceExported(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null,
        replace: Boolean = false
    ) {
        if (replace) {
            replaceAudioRef(questionId, fileName, byteSize, checksum)
        } else {
            addAudioRef(questionId, fileName, byteSize, checksum, dedupByFileName = true)
        }
        Log.d(TAG, "onVoiceExported -> q=$questionId, file=$fileName, replace=$replace")
    }

    /* ───────────────────────────── Two-step Policy & Prompt Templates ───────────────────────────── */

    private val perNodeTwoStepPolicy = ConcurrentHashMap<String, TwoStepPolicy>()
    private val globalTwoStepPolicy: TwoStepPolicy = readTwoStepPolicyFromConfig(config)
    private val promptTemplates: List<PromptTemplate> = normalizePromptTemplates(config)

    fun setTwoStepPolicyOverride(nodeId: String, policy: TwoStepPolicy) {
        perNodeTwoStepPolicy[nodeId] = policy
    }

    fun clearTwoStepPolicyOverrides() {
        perNodeTwoStepPolicy.clear()
    }

    /**
     * Legacy BASE prompt builder (used by non-two-step flow too).
     */
    fun getPrompt(nodeId: String, question: String, answer: String): String {
        val template =
            findTemplate(nodeId, PromptStage.BASE)
                ?: findTemplate(nodeId, PromptStage.EVAL) // if only staged
                ?: findAnyStageTemplateFallback(nodeId)
                ?: run {
                    Log.e(TAG, "No prompt defined for nodeId=$nodeId (BASE/EVAL/ANY).")
                    throw IllegalArgumentException("No prompt defined for nodeId=$nodeId")
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
                ?: run {
                    Log.e(TAG, "No prompt defined for nodeId=$nodeId (EVAL/BASE/ANY).")
                    throw IllegalArgumentException("No prompt defined for nodeId=$nodeId (EVAL/BASE)")
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
            val pattern = Regex("\\{\\{\\s*$key\\s*\\}\\}")
            out = out.replace(pattern, value)
        }
        return out
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
        val hit = promptTemplates.firstOrNull { it.nodeId == n && it.stage == stage && it.prompt.isNotBlank() }
        if (hit != null) return hit.prompt

        // Compatibility lookups for older key formats (stage embedded in key).
        val legacyKeys = buildLegacyPromptKeys(n, stage)
        for (k in legacyKeys) {
            val legacyHit = promptTemplates.firstOrNull { it.nodeId == k && it.stage == stage && it.prompt.isNotBlank() }
            if (legacyHit != null) return legacyHit.prompt
        }
        return null
    }

    private fun findAnyStageTemplateFallback(nodeId: String): String? {
        val n = nodeId.trim()
        return promptTemplates.firstOrNull { it.nodeId == n && it.prompt.isNotBlank() }?.prompt
    }

    private fun buildLegacyPromptKeys(nodeId: String, stage: PromptStage): List<String> {
        val s = stage.name.lowercase(Locale.US)
        return listOf(
            "$nodeId#$s",
            "$nodeId:$s",
            "$nodeId.$s",
            "${nodeId}_$s",
            "${nodeId}-$s",
            "$s/$nodeId",
            "$s:$nodeId",
            "$s#$nodeId",
            "$s.$nodeId"
        ).distinct()
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

    @Synchronized
    private fun push(node: Node) {
        _currentNode.value = node
        nodeStack.addLast(node.id)

        clearSelections()

        nav.add(navKeyFor(node))
        updateCanGoBack()

        Log.d(TAG, "push -> ${node.id}, navSize=${nav.size}, stackSize=${nodeStack.size}")
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
    fun replaceTo(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)

        if (nodeStack.isNotEmpty()) {
            nodeStack.removeLast()
            if (nav.size > 0) nav.removeAt(nav.size - 1)
        }

        push(node)
        Log.d(TAG, "replaceTo -> ${node.id}")
    }

    @Synchronized
    private fun resetNavToStart(start: Node) {
        while (nav.size > 0) {
            nav.removeAt(nav.size - 1)
        }
        nav.add(navKeyFor(start))
        Log.d(TAG, "resetNavToStart -> navSize=${nav.size}")
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

        Log.d(TAG, "resetToStart -> ${start.id}, session=${_sessionId.value}, uuid=${_surveyUuid.value}")
    }

    @Synchronized
    fun backToPrevious() {
        if (nodeStack.size <= 1) {
            Log.d(TAG, "backToPrevious: at root (no-op)")
            return
        }

        if (nav.size > 0) nav.removeAt(nav.size - 1)
        nodeStack.removeLast()

        val prevId = nodeStack.last()
        _currentNode.value = nodeOf(prevId)
        updateCanGoBack()

        clearSelections()

        Log.d(TAG, "backToPrevious -> $prevId")
    }

    @Synchronized
    fun advanceToNext() {
        val cur = _currentNode.value
        val nextId = cur.nextId ?: run {
            Log.d(TAG, "advanceToNext: no nextId from ${cur.id}")
            return
        }

        if (!graph.containsKey(nextId)) {
            throw IllegalStateException("nextId '$nextId' from node '${cur.id}' does not exist in graph.")
        }

        ensureQuestion(nextId)
        push(nodeOf(nextId))
    }

    private fun nodeOf(id: String): Node =
        graph[id] ?: error("Node not found: id=$id (defined nodes=${graph.keys})")

    private fun updateCanGoBack() {
        _canGoBack.value = nodeStack.size > 1
    }

    /* ───────────────────────────── Initialization ───────────────────────────── */

    init {
        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.clear()
        nodeStack.addLast(start.id)

        if (nav.size == 0) {
            nav.add(navKeyFor(start))
        } else {
            Log.d(TAG, "init -> nav pre-seeded (navSize=${nav.size}), skipping initial nav.add()")
        }

        updateCanGoBack()

        Log.d(
            TAG,
            "init -> start=${start.id}, session=${_sessionId.value}, uuid=${_surveyUuid.value}, navSize=${nav.size}, " +
                    "nodes=${graph.size}, prompts=${promptTemplates.size}, globalTwoStep=$globalTwoStepPolicy"
        )
    }

    /* ───────────────────────────── Config Parsing Helpers ───────────────────────────── */

    private fun buildGraphFromConfig(cfg: SurveyConfig): Map<String, Node> {
        val rawNodes = cfg.graph.nodes
        val built = LinkedHashMap<String, Node>(rawNodes.size)

        for (dto in rawNodes) {
            val any = dto as Any

            val id = any.readStringGetter(listOf("id", "nodeId", "key")).ifBlank {
                throw IllegalStateException("Graph node DTO missing id: ${any.javaClass.name}")
            }

            val typeRaw = any.readStringGetter(listOf("type", "nodeType")).ifBlank { "TEXT" }
            val type = runCatching { NodeType.valueOf(typeRaw.trim().uppercase(Locale.US)) }
                .getOrElse { NodeType.TEXT }

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
        val out = ArrayList<PromptTemplate>(raw.size)

        for (dto in raw) {
            val any = dto as Any

            val rawNodeId = any.readStringGetter(listOf("nodeId", "id", "key")).ifBlank {
                Log.w(TAG, "Prompt DTO missing nodeId/id/key: ${any.javaClass.name}")
                continue
            }

            val promptText = any.readStringGetter(listOf("prompt", "template", "text", "body"))
            if (promptText.isBlank()) continue

            val rawStage = any.readStringGetterOrNull(listOf("stage", "kind", "mode", "type"))

            val (nodeId, stage) = decodePromptNodeAndStage(rawNodeId, rawStage)
            out.add(PromptTemplate(nodeId = nodeId, stage = stage, prompt = promptText))
        }

        if (out.isEmpty()) {
            Log.w(TAG, "normalizePromptTemplates -> 0 templates (config.prompts was empty or unparseable)")
        } else {
            val stageCount = out.groupingBy { it.stage }.eachCount()
            Log.d(TAG, "normalizePromptTemplates -> total=${out.size}, stages=$stageCount, distinctNodes=${out.map { it.nodeId }.distinct().size}")
        }

        return out
    }

    private fun decodePromptNodeAndStage(nodeIdRaw: String, stageRaw: String?): Pair<String, PromptStage> {
        val nid = nodeIdRaw.trim()
        val s = stageRaw?.trim()?.lowercase(Locale.US)

        val (baseFromId, stageFromId) = decodeStageFromNodeId(nid)

        val stageFromField = when (s) {
            "eval" -> PromptStage.EVAL
            "followup", "follow_up", "follow-up", "fu" -> PromptStage.FOLLOWUP
            "base", null, "" -> PromptStage.BASE
            else -> PromptStage.BASE
        }

        val stage = if (!s.isNullOrBlank()) stageFromField else (stageFromId ?: PromptStage.BASE)
        return baseFromId to stage
    }

    private fun decodeStageFromNodeId(nodeId: String): Pair<String, PromptStage?> {
        val nid = nodeId.trim()

        val partsHash = nid.split("#", limit = 2)
        if (partsHash.size == 2) {
            val st = partsHash[1].toStageOrNull()
            if (st != null) return partsHash[0] to st
        }

        val partsColon = nid.split(":", limit = 2)
        if (partsColon.size == 2) {
            val left = partsColon[0]
            val right = partsColon[1]
            val leftStage = left.toStageOrNull()
            val rightStage = right.toStageOrNull()
            return when {
                rightStage != null -> left to rightStage
                leftStage != null -> right to leftStage
                else -> nid to null
            }
        }

        val partsDot = nid.split(".", limit = 2)
        if (partsDot.size == 2) {
            val rightStage = partsDot[1].toStageOrNull()
            if (rightStage != null) return partsDot[0] to rightStage
        }

        val slash = nid.split("/", limit = 2)
        if (slash.size == 2) {
            val leftStage = slash[0].toStageOrNull()
            if (leftStage != null) return slash[1] to leftStage
        }

        listOf("_", "-").forEach { sep ->
            val p = nid.split(sep, limit = 2)
            if (p.size == 2) {
                val rightStage = p[1].toStageOrNull()
                if (rightStage != null) return p[0] to rightStage
            }
        }

        return nid to null
    }

    private fun String.toStageOrNull(): PromptStage? {
        return when (this.trim().lowercase(Locale.US)) {
            "eval" -> PromptStage.EVAL
            "followup", "follow_up", "follow-up", "fu" -> PromptStage.FOLLOWUP
            "base" -> PromptStage.BASE
            else -> null
        }
    }

    private fun defaultGlobalTwoStepPolicy(): TwoStepPolicy =
        TwoStepPolicy(enabled = false, okScoreThreshold = 85, skipFollowupWhenOk = true)

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

        return TwoStepPolicy(enabled = enabled, okScoreThreshold = threshold, skipFollowupWhenOk = skip)
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

    /* ───────────────────────────── DTO Access (No Kotlin-Reflect) ───────────────────────────── */

    private fun Any.readStringGetter(names: List<String>): String {
        if (this is Map<*, *>) {
            val hit = names.firstNotNullOfOrNull { name ->
                val v = this.entries.firstOrNull { (k, _) -> (k as? String)?.equals(name, ignoreCase = true) == true }?.value
                (v as? String)?.trim()
            }
            if (!hit.isNullOrBlank()) return hit
        }

        for (n in names) {
            val m = javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && (
                        it.name.equals("get${n.cap()}", ignoreCase = true) ||
                                it.name.equals(n, ignoreCase = true)
                        )
            } ?: continue

            val v = runCatching { m.invoke(this) }.getOrNull()
            if (v is String) return v
        }
        return ""
    }

    private fun Any.readStringListGetter(names: List<String>): List<String> {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries.firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }?.value
                when (v) {
                    is List<*> -> return v.filterIsInstance<String>()
                    is Array<*> -> return v.filterIsInstance<String>()
                }
            }
        }

        for (n in names) {
            val m = javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && (
                        it.name.equals("get${n.cap()}", ignoreCase = true) ||
                                it.name.equals(n, ignoreCase = true)
                        )
            } ?: continue

            val v = runCatching { m.invoke(this) }.getOrNull()
            when (v) {
                is List<*> -> return v.filterIsInstance<String>()
                is Array<*> -> return v.filterIsInstance<String>()
            }
        }
        return emptyList()
    }

    private fun Any.readStringGetterOrNull(names: List<String>): String? {
        val s = readStringGetter(names)
        return s.ifBlank { null }
    }

    private fun Any.readObjectGetterOrNull(names: List<String>): Any? {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries.firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }?.value
                if (v != null) return v
            }
        }

        for (n in names) {
            val m = javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && (
                        it.name.equals("get${n.cap()}", ignoreCase = true) ||
                                it.name.equals(n, ignoreCase = true)
                        )
            } ?: continue
            return runCatching { m.invoke(this) }.getOrNull()
        }
        return null
    }

    private fun Any.readBooleanGetter(names: List<String>, defaultValue: Boolean): Boolean {
        if (this is Map<*, *>) {
            for (n in names) {
                val v = this.entries.firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }?.value
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
            val m = javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && (
                        it.name.equals("get${n.cap()}", ignoreCase = true) ||
                                it.name.equals(n, ignoreCase = true)
                        )
            } ?: continue

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
                val v = this.entries.firstOrNull { (k, _) -> (k as? String)?.equals(n, ignoreCase = true) == true }?.value
                when (v) {
                    is Int -> return v
                    is Number -> return v.toInt()
                    is String -> v.trim().toIntOrNull()?.let { return it }
                }
            }
        }

        for (n in names) {
            val m = javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() && (
                        it.name.equals("get${n.cap()}", ignoreCase = true) ||
                                it.name.equals(n, ignoreCase = true)
                        )
            } ?: continue

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
}
