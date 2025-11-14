/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.SurveyConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

private const val TAG = "SurveyVM"

/**
 * Port interface that abstracts minimal navigation stack operations.
 *
 * English comment:
 * This interface decouples the ViewModel from a specific navigation library
 * (such as Navigation3) and makes it easier to unit test navigation logic.
 *
 * @param K NavKey type that identifies destinations.
 */
interface BackStackPort<K : NavKey> {

    /**
     * English comment:
     * Push a new destination key onto the navigation stack.
     *
     * @param key Destination to add.
     * @return True if the destination was added.
     */
    fun add(key: K): Boolean

    /**
     * English comment:
     * Remove the last destination from the navigation stack, if any.
     *
     * @return The removed destination, or null if the stack was empty.
     */
    fun removeLastOrNull(): K?

    /**
     * English comment:
     * Clear the navigation stack completely.
     */
    fun clear()
}

/**
 * Adapter that bridges Navigation3's [NavBackStack] with [BackStackPort].
 *
 * English comment:
 * Use this class on the UI side to connect a Navigation3 back stack
 * to [SurveyViewModel] while keeping the ViewModel independent of
 * Navigation3 types.
 */
class Nav3BackStackAdapter(
    private val delegate: NavBackStack<NavKey>
) : BackStackPort<NavKey> {

    override fun add(key: NavKey): Boolean = delegate.add(key)

    override fun removeLastOrNull(): NavKey? = delegate.removeLastOrNull()

    override fun clear() {
        delegate.clear()
    }
}

/**
 * Survey node types used by the runtime flow.
 *
 * English comment:
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
 * English comment:
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

/**
 * NavKey definitions for each flow node destination.
 *
 * English comment:
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

/**
 * Events emitted by the ViewModel for one-off UI feedback.
 *
 * English comment:
 * Use these events to show snackbars, dialogs, or other transient messages.
 */
sealed interface UiEvent {

    /**
     * English comment:
     * Simple snackbar-like message.
     */
    data class Snack(val message: String) : UiEvent

    /**
     * English comment:
     * Dialog event that carries a title and message.
     */
    data class Dialog(
        val title: String,
        val message: String
    ) : UiEvent
}

/**
 * Main ViewModel responsible for managing survey navigation and state.
 *
 * English comment:
 * This ViewModel:
 *  - holds the current node and navigation history
 *  - tracks questions and answers per node
 *  - manages follow-up questions and responses
 *  - exposes navigation helpers (advance, back, reset)
 *
 * The ViewModel depends only on [BackStackPort] for navigation, which keeps
 * it decoupled from any concrete navigation library.
 *
 * @property nav Navigation back-stack port.
 * @property config Survey configuration loaded from JSON/YAML.
 */
open class SurveyViewModel(
    private val nav: NavBackStack<NavKey>,
    private val config: SurveyConfig
) : ViewModel() {

    /**
     * English comment:
     * Survey graph as a map from node ID to [Node].
     */
    private val graph: Map<String, Node>

    /**
     * English comment:
     * ID of the starting node defined in [SurveyConfig.graph.startId].
     */
    private val startId: String = config.graph.startId

    /**
     * English comment:
     * Internal stack that tracks the sequence of visited node IDs.
     */
    private val nodeStack = ArrayDeque<String>()

    /**
     * English comment:
     * StateFlow representing the currently active [Node].
     */
    private val _currentNode = MutableStateFlow(
        Node(id = "Loading", type = NodeType.START)
    )
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    /**
     * English comment:
     * Whether backwards navigation is currently possible.
     *
     * This is true when the navigation stack contains more than one node.
     */
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /**
     * English comment:
     * UI-level event stream (snackbars, dialogs, etc.).
     */
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /**
     * English comment:
     * Node ID to question text map, kept for the duration of the flow.
     *
     * This allows the UI to re-use original questions even after navigation.
     */
    private val _questions =
        MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val questions: StateFlow<Map<String, String>> = _questions.asStateFlow()

    /**
     * English comment:
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
     * English comment:
     * Retrieve a question text by key (node ID) or return an empty string.
     */
    fun getQuestion(key: String): String = questions.value[key].orEmpty()

    /**
     * English comment:
     * Clear all stored questions.
     */
    fun resetQuestions() {
        _questions.value = LinkedHashMap()
    }

    /**
     * English comment:
     * Node ID to answer text map.
     */
    private val _answers =
        MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()

    /**
     * English comment:
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
     * English comment:
     * Retrieve an answer by key (node ID) or return an empty string.
     */
    fun getAnswer(key: String): String = answers.value[key].orEmpty()

    /**
     * English comment:
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
     * English comment:
     * Single-choice selection for the current node.
     */
    private val _single = MutableStateFlow<String?>(null)
    val single: StateFlow<String?> = _single.asStateFlow()

    /**
     * English comment:
     * Set the current single-choice selection, or null to clear.
     */
    fun setSingleChoice(opt: String?) {
        _single.value = opt
    }

    /**
     * English comment:
     * Multi-choice selection set for the current node.
     */
    private val _multi = MutableStateFlow<Set<String>>(emptySet())
    val multi: StateFlow<Set<String>> = _multi.asStateFlow()

    /**
     * English comment:
     * Toggle the presence of a multi-choice option in the selection set.
     *
     * If the option is not present, it is added; otherwise it is removed.
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
     * English comment:
     * Clear both single- and multi-choice selections for the current node.
     */
    fun clearSelections() {
        _single.value = null
        _multi.value = emptySet()
    }

    /**
     * English comment:
     * Follow-up entry used to track AI-generated questions and answers.
     *
     * @property question Text of the follow-up question.
     * @property answer Optional answer text (null if not yet answered).
     * @property askedAt Timestamp when the follow-up was generated.
     * @property answeredAt Timestamp when the follow-up was answered, or null.
     */
    data class FollowupEntry(
        val question: String,
        val answer: String? = null,
        val askedAt: Long = System.currentTimeMillis(),
        val answeredAt: Long? = null
    )

    /**
     * English comment:
     * Map from node ID to a list of follow-up entries.
     */
    private val _followups =
        MutableStateFlow<Map<String, List<FollowupEntry>>>(LinkedHashMap())
    val followups: StateFlow<Map<String, List<FollowupEntry>>> =
        _followups.asStateFlow()

    /**
     * English comment:
     * Add a follow-up question for a given node ID.
     *
     * @param nodeId Node ID that owns the follow-up.
     * @param question Follow-up question text.
     * @param dedupAdjacent If true, ignore the new question when it is the
     * same as the last question in the list.
     */
    fun addFollowupQuestion(
        nodeId: String,
        question: String,
        dedupAdjacent: Boolean = true
    ) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists()
            val list = mutable.getOrPut(nodeId) { mutableListOf() }
            val last = list.lastOrNull()
            if (!(dedupAdjacent && last?.question == question)) {
                list.add(FollowupEntry(question = question))
            }
            mutable.toImmutableLists()
        }
    }

    /**
     * English comment:
     * Answer the last unanswered follow-up for the given node ID.
     *
     * If all follow-ups already have answers, this method is a no-op.
     */
    fun answerLastFollowup(
        nodeId: String,
        answer: String
    ) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists()
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
     * English comment:
     * Answer a follow-up at a specific index for the given node ID.
     *
     * If the index is out of range, this method is a no-op.
     */
    fun answerFollowupAt(
        nodeId: String,
        index: Int,
        answer: String
    ) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists()
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
     * English comment:
     * Remove all follow-ups associated with the given node ID.
     */
    fun clearFollowups(nodeId: String) {
        _followups.update { old ->
            val mutable = old.mutableLinkedLists()
            mutable.remove(nodeId)
            mutable.toImmutableLists()
        }
    }

    /**
     * English comment:
     * Build a rendered prompt string for the given node and answer.
     *
     * The template is looked up from [SurveyConfig.prompts] by node ID and
     * uses placeholders like {{QUESTION}}, {{ANSWER}}, and {{NODE_ID}}.
     *
     * @throws IllegalArgumentException if no prompt is defined for the node.
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
     * English comment:
     * Replace placeholders in a template using the format {{KEY}}.
     *
     * @param template Template text containing placeholders.
     * @param vars Map of placeholder keys to replacement values.
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

    /**
     * English comment:
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
     * English comment:
     * Push a node into the internal stack and navigate to its destination.
     *
     * This method also updates [_currentNode] and [_canGoBack].
     */
    @Synchronized
    private fun push(node: Node) {
        _currentNode.value = node
        nodeStack.addLast(node.id)
        nav.add(navKeyFor(node))
        updateCanGoBack()
        Log.d(TAG, "push -> ${node.id}")
    }

    /**
     * English comment:
     * Ensure that the question text for a given node ID is stored.
     *
     * If there is no stored question yet, the question from the node
     * definition is inserted into the question map.
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
     * English comment:
     * Hook for UI to clear transient selections when the node changes.
     */
    fun onNodeChangedResetSelections() {
        clearSelections()
    }

    /**
     * English comment:
     * Navigate to the node with the given ID and push it onto the history.
     *
     * @throws IllegalStateException if the node ID does not exist.
     */
    @Synchronized
    fun goto(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)
        push(node)
    }

    /**
     * English comment:
     * Replace the current node with another node without stacking.
     *
     * This is effectively a "jump" that pops the current node and then
     * pushes the new node.
     *
     * @throws IllegalStateException if the node ID does not exist.
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
     * English comment:
     * Reset the navigation stack and move to the start node.
     */
    @Synchronized
    fun resetToStart() {
        nav.clear()
        nodeStack.clear()

        val start = nodeOf(startId)
        _currentNode.value = start
        nodeStack.addLast(start.id)
        nav.add(navKeyFor(start))
        updateCanGoBack()

        Log.d(TAG, "resetToStart() -> ${start.id}")
    }

    /**
     * English comment:
     * Navigate back to the previous node if possible.
     *
     * If already at the root node, this method is a no-op.
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

        Log.d(TAG, "backToPrevious -> $prevId")
    }

    /**
     * English comment:
     * Move forward to the next node based on the current node's [Node.nextId].
     *
     * If there is no [Node.nextId], nothing happens. If the next node ID
     * is not present in the graph, an [IllegalStateException] is thrown.
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
     * English comment:
     * Get a [Node] instance for the given ID or throw an error.
     *
     * This method assumes configuration has already been validated.
     */
    private fun nodeOf(id: String): Node =
        graph[id] ?: error(
            "Node not found: id=$id (defined nodes=${graph.keys})"
        )

    /**
     * English comment:
     * Update [_canGoBack] based on the current size of [nodeStack].
     */
    private fun updateCanGoBack() {
        _canGoBack.value = nodeStack.size > 1
    }

    /**
     * English comment:
     * Convert a generic immutable [Map] to a mutable [LinkedHashMap] copy.
     */
    private fun Map<String, String>.mutableLinked(): LinkedHashMap<String, String> =
        LinkedHashMap(this)

    /**
     * English comment:
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
     * English comment:
     * Convert a [LinkedHashMap] of mutable lists into a map of immutable lists.
     */
    private fun <T> LinkedHashMap<String, MutableList<T>>.toImmutableLists():
            Map<String, List<T>> =
        this.mapValues { (_, list) -> list.toList() }

    /**
     * English comment:
     * Initialization block that builds the graph from config and moves
     * the navigation state to the start node.
     */
    init {
        // Build the runtime graph from configuration DTOs.
        graph = config.graph.nodes
            .associateBy { it.id }
            .mapValues { (_, dto) -> dto.toVmNode() }

        // Initialize navigation at the start node.
        val start = nodeOf(startId)
        _currentNode.value = start
        nodeStack.addLast(start.id)
        nav.add(navKeyFor(start))
        updateCanGoBack()

        Log.d(TAG, "init -> ${start.id}")
    }
}
