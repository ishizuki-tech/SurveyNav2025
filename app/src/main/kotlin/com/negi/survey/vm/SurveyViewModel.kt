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
 *   • Adds a logical audio manifest (recordedAudioRefs) as a stable
 *     source of truth for JSON export across repeated uploads.
 *   • Provides helper APIs for run-scoped retrieval, per-file removal,
 *     and replace semantics to support re-record flows.
 *   • Strengthens thread-safety around audio ref mutation.
 *   • Avoids double-pushing FlowHome when NavBackStack is pre-seeded
 *     by rememberNavBackStack(FlowHome).
 *   • Removes reliance on NavBackStack.clear() for broader compatibility.
 *
 *  This ViewModel intentionally does not depend on file-system scans.
 *  Physical WAV discovery remains an Export/Repository responsibility.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.SurveyConfig
import java.util.UUID
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
 *
 * Each of these objects represents a logical screen that the navigation
 * layer can target for a given node type.
 */
@Serializable
object FlowHome : NavKey

@Serializable
object FlowText : NavKey

@Serializable
object FlowSingle : NavKey

@Serializable
object FlowMulti : NavKey

@Serializable
object FlowAI : NavKey

@Serializable
object FlowReview : NavKey

@Serializable
object FlowDone : NavKey

/* ───────────────────────────── UI Events ───────────────────────────── */

/**
 * Events emitted by the ViewModel for one-off UI feedback.
 *
 * Typical usages include snackbars, dialogs, and other transient messages.
 */
sealed interface UiEvent {

    /**
     * Simple snackbar-like message.
     */
    data class Snack(val message: String) : UiEvent

    /**
     * Dialog event that carries a title and message.
     */
    data class Dialog(
        val title: String,
        val message: String
    ) : UiEvent
}

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
 *
 * @property nav Navigation back-stack.
 * @property config Survey configuration loaded from JSON/YAML.
 */
open class SurveyViewModel(
    private val nav: NavBackStack<NavKey>,
    private val config: SurveyConfig
) : ViewModel() {

    /**
     * Survey graph as a map from node ID to [Node].
     */
    private val graph: Map<String, Node>

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
     *
     * This is incremented every time [resetToStart] is invoked so that
     * UI layers (e.g., text fields) can key their local state by session.
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

    /**
     * Emit a snackbar-like UI event.
     */
    fun emitSnack(message: String) {
        _events.tryEmit(UiEvent.Snack(message))
    }

    /**
     * Emit a dialog UI event.
     */
    fun emitDialog(title: String, message: String) {
        _events.tryEmit(UiEvent.Dialog(title, message))
    }

    /* ───────────────────────────── Questions ───────────────────────────── */

    private val _questions =
        MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val questions: StateFlow<Map<String, String>> = _questions.asStateFlow()

    /**
     * Update or insert a question text for the given key (node ID).
     */
    fun setQuestion(text: String, key: String) {
        _questions.update { old ->
            old.mutableLinked().apply {
                put(key, text)
            }
        }
    }

    /**
     * Retrieve a question text by key (node ID) or return an empty string.
     */
    fun getQuestion(key: String): String = questions.value[key].orEmpty()

    /**
     * Clear all stored questions.
     */
    fun resetQuestions() {
        _questions.value = LinkedHashMap()
    }

    /* ───────────────────────────── Answers ───────────────────────────── */

    private val _answers =
        MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()

    /**
     * Update or insert an answer text for the given key (node ID).
     */
    fun setAnswer(text: String, key: String) {
        _answers.update { old ->
            old.mutableLinked().apply {
                put(key, text)
            }
        }
    }

    /**
     * Retrieve an answer by key (node ID) or return an empty string.
     */
    fun getAnswer(key: String): String = answers.value[key].orEmpty()

    /**
     * Remove an answer associated with the given key (node ID).
     */
    fun clearAnswer(key: String) {
        _answers.update { old ->
            old.mutableLinked().apply {
                remove(key)
            }
        }
    }

    /**
     * Clear all stored answers.
     */
    fun resetAnswers() {
        _answers.value = LinkedHashMap()
    }

    /* ───────────────────────────── Choice Selections ───────────────────────────── */

    private val _single = MutableStateFlow<String?>(null)
    val single: StateFlow<String?> = _single.asStateFlow()

    /**
     * Set the current single-choice selection, or null to clear.
     */
    fun setSingleChoice(opt: String?) {
        _single.value = opt
    }

    private val _multi = MutableStateFlow<Set<String>>(emptySet())
    val multi: StateFlow<Set<String>> = _multi.asStateFlow()

    /**
     * Toggle the presence of a multi-choice option in the selection set.
     */
    fun toggleMultiChoice(opt: String) {
        _multi.update { cur ->
            cur.toMutableSet().apply {
                if (!add(opt)) {
                    remove(opt)
                }
            }
        }
    }

    /**
     * Clear both single- and multi-choice selections for the current node.
     */
    fun clearSelections() {
        _single.value = null
        _multi.value = emptySet()
    }

    /* ───────────────────────────── Follow-ups ───────────────────────────── */

    /**
     * Follow-up entry used to track AI-generated questions and answers.
     */
    data class FollowupEntry(
        val question: String,
        val answer: String? = null,
        val askedAt: Long = System.currentTimeMillis(),
        val answeredAt: Long? = null
    )

    private val _followups =
        MutableStateFlow<Map<String, List<FollowupEntry>>>(LinkedHashMap())
    val followups: StateFlow<Map<String, List<FollowupEntry>>> =
        _followups.asStateFlow()

    /**
     * Add a follow-up question for a given node ID.
     */
    fun addFollowupQuestion(
        nodeId: String,
        question: String,
        dedupAdjacent: Boolean = true
    ) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable.getOrPut(nodeId) { mutableListOf() }
            val last = list.lastOrNull()
            if (!(dedupAdjacent && last?.question == question)) {
                list.add(FollowupEntry(question = question))
            }
            mutable.toImmutableLists()
        }
    }

    /**
     * Answer the last unanswered follow-up for the given node ID.
     */
    fun answerLastFollowup(
        nodeId: String,
        answer: String
    ) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[nodeId] ?: return@update old
            val idx = list.indexOfLast { it.answer == null }
            if (idx < 0) {
                return@update old
            }
            list[idx] = list[idx].copy(
                answer = answer,
                answeredAt = System.currentTimeMillis()
            )
            mutable.toImmutableLists()
        }
    }

    /**
     * Answer a follow-up at a specific index for the given node ID.
     */
    fun answerFollowupAt(
        nodeId: String,
        index: Int,
        answer: String
    ) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[nodeId] ?: return@update old
            if (index !in list.indices) {
                return@update old
            }
            list[index] = list[index].copy(
                answer = answer,
                answeredAt = System.currentTimeMillis()
            )
            mutable.toImmutableLists()
        }
    }

    /**
     * Remove all follow-ups associated with the given node ID.
     */
    fun clearFollowups(nodeId: String) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            mutable.remove(nodeId)
            mutable.toImmutableLists()
        }
    }

    /**
     * Clear all follow-ups for all nodes.
     */
    fun resetFollowups() {
        _followups.value = LinkedHashMap()
    }

    /* ───────────────────────────── Recorded Audio Refs ───────────────────────────── */

    /**
     * Audio reference metadata recorded during a survey run.
     */
    data class AudioRef(
        val surveyId: String,
        val questionId: String,
        val fileName: String,
        val createdAt: Long = System.currentTimeMillis(),
        val byteSize: Long? = null,
        val checksum: String? = null
    )

    private val _recordedAudioRefs =
        MutableStateFlow<Map<String, List<AudioRef>>>(LinkedHashMap())
    val recordedAudioRefs: StateFlow<Map<String, List<AudioRef>>> =
        _recordedAudioRefs.asStateFlow()

    /**
     * Register a new audio reference for the active survey UUID.
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

        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            val list = mutable.getOrPut(questionId) { mutableListOf() }

            val existsSameRun = list.any { it.fileName == fileName && it.surveyId == sid }
            if (!dedupByFileName || !existsSameRun) {
                list.add(
                    AudioRef(
                        surveyId = sid,
                        questionId = questionId,
                        fileName = fileName,
                        byteSize = byteSize,
                        checksum = checksum
                    )
                )
            }

            mutable.toImmutableLists()
        }

        Log.d(TAG, "addAudioRef -> q=$questionId, file=$fileName, sid=$sid")
    }

    /**
     * Replace audio refs for a specific question with a single new file.
     */
    @Synchronized
    fun replaceAudioRef(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null
    ) {
        val sid = surveyUuid.value

        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            mutable[questionId] = mutableListOf(
                AudioRef(
                    surveyId = sid,
                    questionId = questionId,
                    fileName = fileName,
                    byteSize = byteSize,
                    checksum = checksum
                )
            )
            mutable.toImmutableLists()
        }

        Log.d(TAG, "replaceAudioRef -> q=$questionId, file=$fileName, sid=$sid")
    }

    /**
     * Remove a specific audio reference by file name.
     */
    @Synchronized
    fun removeAudioRef(
        questionId: String,
        fileName: String
    ) {
        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            val list = mutable[questionId] ?: return@update old

            list.removeAll { it.fileName == fileName }

            if (list.isEmpty()) {
                mutable.remove(questionId)
            }

            mutable.toImmutableLists()
        }

        Log.d(TAG, "removeAudioRef -> q=$questionId, file=$fileName")
    }

    /**
     * Clear audio refs for a specific question.
     */
    @Synchronized
    fun clearAudioRefs(questionId: String) {
        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            mutable.remove(questionId)
            mutable.toImmutableLists()
        }

        Log.d(TAG, "clearAudioRefs -> q=$questionId")
    }

    /**
     * Clear all recorded audio refs for the current run.
     */
    @Synchronized
    fun resetAudioRefs() {
        _recordedAudioRefs.value = LinkedHashMap()
        Log.d(TAG, "resetAudioRefs -> cleared")
    }

    /**
     * Read-only accessor for UI/JSON builders.
     */
    fun getAudioRefs(questionId: String): List<AudioRef> =
        recordedAudioRefs.value[questionId].orEmpty()

    /**
     * Return audio refs that belong to the given survey run.
     */
    fun getAudioRefsForRun(
        surveyId: String = surveyUuid.value
    ): Map<String, List<AudioRef>> {
        return recordedAudioRefs.value
            .mapValues { (_, list) -> list.filter { it.surveyId == surveyId } }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Return a flattened list of audio refs for the given survey run.
     */
    fun getAudioRefsForRunFlat(
        surveyId: String = surveyUuid.value
    ): List<AudioRef> {
        return getAudioRefsForRun(surveyId)
            .values
            .flatten()
            .sortedBy { it.createdAt }
    }

    /**
     * Returns true if at least one audio ref exists for the question
     * in the given survey run.
     */
    fun hasAudioRef(
        questionId: String,
        surveyId: String = surveyUuid.value
    ): Boolean {
        return getAudioRefs(questionId).any { it.surveyId == surveyId }
    }

    /**
     * High-level helper to register an exported voice artifact.
     */
    fun onVoiceExported(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null,
        replace: Boolean = false
    ) {
        if (replace) {
            replaceAudioRef(
                questionId = questionId,
                fileName = fileName,
                byteSize = byteSize,
                checksum = checksum
            )
        } else {
            addAudioRef(
                questionId = questionId,
                fileName = fileName,
                byteSize = byteSize,
                checksum = checksum,
                dedupByFileName = true
            )
        }
        Log.d(TAG, "onVoiceExported -> q=$questionId, file=$fileName, replace=$replace")
    }

    /* ───────────────────────────── Prompt Helpers ───────────────────────────── */

    /**
     * Build a rendered prompt string for the given node and answer.
     */
    fun getPrompt(
        nodeId: String,
        question: String,
        answer: String
    ): String {
        val template = config.prompts
            .firstOrNull { it.nodeId == nodeId }
            ?.prompt
            ?: throw IllegalArgumentException(
                "No prompt defined for nodeId=$nodeId"
            )

        return renderTemplate(
            template = template,
            vars = mapOf(
                "QUESTION" to question.trim(),
                "ANSWER" to answer.trim(),
                "NODE_ID" to nodeId
            )
        )
    }

    /**
     * Replace placeholders in a template using the format `{{KEY}}`.
     */
    private fun renderTemplate(
        template: String,
        vars: Map<String, String>
    ): String {
        var out = template
        for ((key, value) in vars) {
            val pattern = Regex("\\{\\{\\s*$key\\s*\\}\\}")
            out = out.replace(pattern, value)
        }
        return out
    }

    /* ───────────────────────────── Navigation ───────────────────────────── */

    /**
     * Map a [Node.type] to a [NavKey] destination.
     */
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

    /**
     * Push a node into the internal stack and navigate to its destination.
     *
     * This method also updates [_currentNode] and [_canGoBack].
     */
    @Synchronized
    private fun push(node: Node) {
        _currentNode.value = node
        nodeStack.addLast(node.id)

        // Clear ephemeral per-node selection state on forward navigation.
        clearSelections()

        nav.add(navKeyFor(node))
        updateCanGoBack()

        Log.d(TAG, "push -> ${node.id}, navSize=${nav.size}, stackSize=${nodeStack.size}")
    }

    /**
     * Ensure that the question text for a given node ID is stored.
     */
    private fun ensureQuestion(id: String) {
        if (getQuestion(id).isEmpty()) {
            val questionText = nodeOf(id).question
            if (questionText.isNotEmpty()) {
                setQuestion(questionText, id)
            }
        }
    }

    /**
     * Navigate to the node with the given ID and push it onto the history.
     */
    @Synchronized
    fun goto(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)
        push(node)
    }

    /**
     * Replace the current node with another node without stacking.
     */
    @Synchronized
    fun replaceTo(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)

        if (nodeStack.isNotEmpty()) {
            nodeStack.removeLast()
            nav.removeLastOrNull()
        }

        push(node)
        Log.d(TAG, "replaceTo -> ${node.id}")
    }

    /**
     * Reset navigation back-stack to a single start destination.
     *
     * This intentionally avoids NavBackStack.clear() to reduce API coupling.
     */
    @Synchronized
    private fun resetNavToStart(start: Node) {
        val startKey = navKeyFor(start)

        while (nav.size > 0) {
            nav.removeLastOrNull()
        }
        nav.add(startKey)

        Log.d(TAG, "resetNavToStart -> key=$startKey, navSize=${nav.size}")
    }

    /**
     * Reset the navigation stack and move to the start node, clearing
     * all survey answers, questions, follow-ups, audio refs, and selections.
     */
    @Synchronized
    fun resetToStart() {
        // Regenerate UUID first to mark a clean run boundary.
        regenerateSurveyUuid()

        // Clear all survey state so previous session does not leak.
        resetQuestions()
        resetAnswers()
        resetFollowups()
        resetAudioRefs()
        clearSelections()

        // Reset internal history.
        nodeStack.clear()

        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.addLast(start.id)

        // Reset NavBackStack to a single start key.
        resetNavToStart(start)

        updateCanGoBack()

        // Bump session id so UI can treat this as a fresh run.
        _sessionId.update { it + 1 }

        Log.d(
            TAG,
            "resetToStart -> ${start.id}, " +
                    "session=${_sessionId.value}, uuid=${_surveyUuid.value}"
        )
    }

    /**
     * Navigate back to the previous node if possible.
     */
    @Synchronized
    fun backToPrevious() {
        if (nodeStack.size <= 1) {
            Log.d(TAG, "backToPrevious: at root (no-op)")
            return
        }

        nav.removeLastOrNull()
        nodeStack.removeLast()

        val prevId = nodeStack.last()
        _currentNode.value = nodeOf(prevId)
        updateCanGoBack()

        // Clear ephemeral selections on back to avoid UI reusing stale state.
        clearSelections()

        Log.d(TAG, "backToPrevious -> $prevId")
    }

    /**
     * Move forward to the next node based on the current node's [Node.nextId].
     */
    @Synchronized
    fun advanceToNext() {
        val cur = _currentNode.value
        val nextId = cur.nextId ?: run {
            Log.d(TAG, "advanceToNext: no nextId from ${cur.id}")
            return
        }

        if (!graph.containsKey(nextId)) {
            throw IllegalStateException(
                "nextId '$nextId' from node '${cur.id}' does not exist in graph."
            )
        }

        ensureQuestion(nextId)
        push(nodeOf(nextId))
    }

    /**
     * Get a [Node] instance for the given ID or throw an error.
     */
    private fun nodeOf(id: String): Node =
        graph[id] ?: error(
            "Node not found: id=$id (defined nodes=${graph.keys})"
        )

    /**
     * Update [_canGoBack] based on the current size of [nodeStack].
     */
    private fun updateCanGoBack() {
        _canGoBack.value = nodeStack.size > 1
    }

    /* ───────────────────────────── Map Helpers ───────────────────────────── */

    /**
     * Convert a generic immutable [Map] to a mutable [LinkedHashMap] copy.
     */
    private fun Map<String, String>.mutableLinked(): LinkedHashMap<String, String> =
        LinkedHashMap(this)

    /**
     * Convert a map of immutable lists to a mutable [LinkedHashMap] whose
     * values are [MutableList] copies.
     */
    private fun <T> Map<String, List<T>>.mutableLinkedLists():
            LinkedHashMap<String, MutableList<T>> {
        val result = LinkedHashMap<String, MutableList<T>>()
        for ((key, value) in this) {
            result[key] = value.toMutableList()
        }
        return result
    }

    /**
     * Convert a [LinkedHashMap] of mutable lists into a map of immutable lists.
     */
    private fun <T> LinkedHashMap<String, MutableList<T>>.toImmutableLists():
            Map<String, List<T>> =
        this.mapValues { (_, list) -> list.toList() }

    /* ───────────────────────────── Initialization ───────────────────────────── */

    /**
     * Initialization block that builds the graph from config and moves
     * the navigation state to the start node.
     */
    init {
        // Build the runtime graph from configuration DTOs.
        graph = config.graph.nodes
            .associateBy { it.id }
            .mapValues { (_, dto) -> dto.toVmNode() }

        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.clear()
        nodeStack.addLast(start.id)

        // IMPORTANT:
        // rememberNavBackStack(FlowHome) may already seed the back stack.
        // Only add a start key when the stack is empty.
        if (nav.size == 0) {
            nav.add(navKeyFor(start))
        }

        updateCanGoBack()

        Log.d(
            TAG,
            "init -> ${start.id}, session=${_sessionId.value}, uuid=${_surveyUuid.value}, navSize=${nav.size}"
        )
    }
}
