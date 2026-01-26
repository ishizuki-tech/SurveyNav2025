/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: FollowupExtractor.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Utility object for extracting follow-up questions and simple scores
 *  from raw SLM output. Supports:
 *    - Multiple embedded JSON fragments (JSONObject / JSONArray).
 *    - Robust key normalization (separator-insensitive + camelCase-aware).
 *    - Question-field detection with safer heuristics (reduced false positives).
 *    - Deduplication with stable encounter order.
 *    - Score extraction with JSON-first semantics and safer text fallback.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Utility for extracting follow-up questions (and simple scores) from raw text or JSON.
 *
 * Notes:
 * - Designed to be resilient to messy LLM outputs (mixed text + JSON fragments).
 * - Heuristics are tuned to reduce accidental extraction of long explanations
 *   (e.g., "message"/"body" fields) as follow-up questions.
 */
object FollowupExtractor {

    /* --------------------------------------------------------------------- */
    /* Configuration                                                         */
    /* --------------------------------------------------------------------- */

    /** Regex used to normalize key separators into a single dash. */
    private val KEY_SEP_REGEX =
        Regex("""[\s_\u200B\u200C\u200D\u2060\u2010-\u2015]+""")

    /** Trailing question marks (ASCII or full-width) to be coalesced to exactly one. */
    private val TRAILING_QUESTION_REGEX = Regex("[?？]+$")

    /** Matches integers 0..100; used only for last-resort scoring fallback. */
    private val NUMBER_0_TO_100_REGEX = Regex("""\b(?:100|[1-9]?\d)\b""")

    /**
     * Prefer extracting score from explicit patterns in plain text:
     * - "score: 85"
     * - "overall score = 92"
     * - "score - 70"
     */
    private val SCORE_NEAR_REGEX = Regex(
        pattern = """(?i)\b(?:overall\s*score|overall_score|overallScore|score)\b\s*(?:[:=]|-)\s*(100|[1-9]?\d)\b"""
    )

    /** Avoid accidentally capturing gigantic prompt blobs as a "question". */
    private const val MAX_QUESTION_CHARS: Int = 220

    /** Limit scanning cost for extremely large raw strings. */
    private const val MAX_SCAN_CHARS: Int = 200_000
    private const val MAX_FRAGMENTS: Int = 32
    private const val MAX_STACK_DEPTH: Int = 256

    /**
     * Normalize field keys for matching:
     * - Insert separators for camelCase and acronym boundaries.
     * - Lowercase with Locale.ROOT.
     * - Convert any run of separators to a single '-'.
     * - Trim leading/trailing dashes.
     */
    private fun normKey(k: String): String =
        decamel(k)
            .lowercase(Locale.ROOT)
            .replace(KEY_SEP_REGEX, "-")
            .trim('-')

    /**
     * Insert separators into camelCase / acronym boundaries.
     *
     * Rules (simple and robust):
     * - lower/digit -> Upper inserts '-'
     * - Upper + Upper + lower: split before the last Upper to keep acronyms together
     *   (e.g., "JSONScore" -> "JSON-Score")
     */
    private fun decamel(s: String): String {
        if (s.isEmpty()) return s

        val out = StringBuilder(s.length + 8)
        val n = s.length

        fun isUpper(c: Char) = c in 'A'..'Z'
        fun isLower(c: Char) = c in 'a'..'z'
        fun isDigit(c: Char) = c in '0'..'9'

        for (i in 0 until n) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else '\u0000'
            val next = if (i + 1 < n) s[i + 1] else '\u0000'

            val boundary1 = (isUpper(c) && (isLower(prev) || isDigit(prev)))
            val boundary2 = (isUpper(prev) && isUpper(c) && isLower(next))

            if (i > 0 && (boundary1 || boundary2)) out.append('-')
            out.append(c)
        }
        return out.toString()
    }

    /** Raw followup-like keys we consider as primary containers. */
    private val FOLLOWUP_KEYS_RAW: List<String> = listOf(
        // Singular
        "followup question",
        "follow-up question",
        "follow_up_question",
        "followUpQuestion",
        "followupQuestion",

        // Plural / list containers
        "followup",
        "follow-up",
        "followups",
        "follow-ups",
        "followup-questions",
        "follow-up-questions",
        "follow_up_questions",
        "followUpQuestions",
        "followupQuestions",
        "follow-up-q",
        "next-questions",
        "suggested-questions",
        "suggestedQuestions"
    )

    /** Normalized followup-like keys we consider as primary containers. */
    private val FOLLOWUP_KEYS_NORM: Set<String> =
        FOLLOWUP_KEYS_RAW.map(::normKey).toSet()

    /**
     * Strong question-bearing fields:
     * - These are commonly used to carry actual question strings.
     */
    private val QUESTION_FIELDS_STRONG_RAW: List<String> = listOf(
        "followup question",
        "follow-up question",
        "follow_up_question",
        "followUpQuestion",
        "followupQuestion",
        "question",
        "q",
        "text"
    )

    /**
     * Weak/question-adjacent fields:
     * - These may contain explanations, instructions, or entire prompts.
     * - We only accept them when the value looks like a real question (e.g., ends with '?').
     */
    private val QUESTION_FIELDS_WEAK_RAW: List<String> = listOf(
        "content",
        "title",
        "prompt",
        "message",
        "body",
        "value"
    )

    private val QUESTION_FIELDS_STRONG_NORM: Set<String> =
        QUESTION_FIELDS_STRONG_RAW.map(::normKey).toSet()

    private val QUESTION_FIELDS_WEAK_NORM: Set<String> =
        QUESTION_FIELDS_WEAK_RAW.map(::normKey).toSet()

    /* --------------------------------------------------------------------- */
    /* Public API                                                            */
    /* --------------------------------------------------------------------- */

    /**
     * Extract follow-up questions from free-form [raw] text.
     *
     * Strategy:
     * - Parse one or more JSON fragments (including code fences).
     * - Traverse and collect candidate question strings.
     * - Deduplicate while preserving encounter order.
     *
     * Fallback:
     * - If no JSON yields a question, split raw text into sentence-like chunks
     *   and collect those that end with '?' or '？'.
     */
    @JvmStatic
    fun fromRaw(raw: String, max: Int = Int.MAX_VALUE): List<String> {
        if (raw.isBlank() || max <= 0) return emptyList()

        val out = LinkedHashSet<String>()

        // Extract JSON from:
        // (1) fenced blocks, (2) the whole raw string.
        val candidates = buildList {
            addAll(extractCodeFenceBodies(raw))
            add(raw)
        }

        for (cand in candidates) {
            if (out.size >= max) break
            val fragments = extractJsonFragments(cand)
            for (frag in fragments) {
                if (out.size >= max) break
                collect(frag, out, max)
            }
        }

        // Plain text fallback
        if (out.isEmpty()) {
            for (piece in splitSentenceLike(raw)) {
                if (out.size >= max) break
                val trimmed = piece.trim()
                if (trimmed.endsWith("?") || trimmed.endsWith("？")) {
                    addIfMeaningful(trimmed, out, max, requireQuestionMark = true)
                }
            }
        }

        return out.toList().take(max)
    }

    /**
     * Extract follow-up questions from a JSON-like root node or list of nodes.
     *
     * Accepted values:
     * - JSONObject / JSONArray / String
     * - List of the above
     */
    @JvmStatic
    fun fromJsonAny(any: Any, max: Int = Int.MAX_VALUE): List<String> {
        if (max <= 0) return emptyList()

        val out = LinkedHashSet<String>()
        when (any) {
            is List<*> -> for (elem in any) {
                if (elem != null && out.size < max) collect(elem, out, max)
            }
            else -> collect(any, out, max)
        }
        return out.toList().take(max)
    }

    /** Convenience: return the first follow-up question found in [rawText], or null if none. */
    @JvmStatic
    fun extractFollowupQuestion(rawText: String): String? =
        runCatching { fromRaw(rawText, max = 3).firstOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Extract an integer score in the range 0..100 from [text].
     *
     * Strategy:
     *  (1) Parse JSON fragments and recursively look for score keys.
     *  (2) If not found, try "score: 85" style patterns in raw text.
     *  (3) Last resort: only if the text contains "score" somewhere,
     *      pick the last 0..100 integer from the text (reduced false positives).
     */
    @JvmStatic
    fun extractScore(text: String): Int? {
        val candidates = buildList {
            addAll(extractCodeFenceBodies(text))
            add(text)
        }

        // (1) JSON-first
        for (cand in candidates) {
            val fragments = extractJsonFragments(cand)
            for (frag in fragments) {
                val v = when (frag) {
                    is JSONObject -> findScoreRecursive(frag)
                    is JSONArray -> findScoreRecursive(frag)
                    else -> null
                }
                if (v != null) return clamp0to100(v)
            }
        }

        // (2) "score: 85" style patterns
        SCORE_NEAR_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { n ->
            return clamp0to100(n)
        }

        // (3) Last-resort: only if "score" is mentioned at least once
        if (!text.contains("score", ignoreCase = true)) return null

        val lastMatch = NUMBER_0_TO_100_REGEX
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(0)
            ?.toIntOrNull()

        return lastMatch?.let(::clamp0to100)
    }

    /* --------------------------------------------------------------------- */
    /* Internal helpers                                                      */
    /* --------------------------------------------------------------------- */

    private fun clamp0to100(x: Int): Int = max(0, min(100, x))

    /**
     * Depth-first traversal collecting candidate questions into [out].
     *
     * Important:
     * - We prioritize followup container keys (FOLLOWUP_KEYS_NORM).
     * - We only accept weak fields (message/body/prompt/...) if they look like real questions.
     */
    private fun collect(node: Any?, out: MutableSet<String>, max: Int) {
        if (node == null || out.size >= max) return

        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= max) break
                    val v = node.opt(i)
                    when (v) {
                        is String -> addIfMeaningful(v, out, max, requireQuestionMark = false)
                        is JSONObject -> {
                            extractQuestionField(v)?.let { addIfMeaningful(it, out, max, requireQuestionMark = false) }
                            collect(v, out, max)
                        }
                        is JSONArray -> collect(v, out, max)
                    }
                }
            }

            is JSONObject -> {
                // (1) Preferentially process followup-like keys (normalized).
                val iter1 = node.keys()
                while (iter1.hasNext() && out.size < max) {
                    val key = iter1.next()
                    if (FOLLOWUP_KEYS_NORM.contains(normKey(key))) {
                        when (val value = node.opt(key)) {
                            is String -> addIfMeaningful(value, out, max, requireQuestionMark = false)
                            is JSONArray -> collect(value, out, max)
                            is JSONObject -> {
                                extractQuestionField(value)?.let { addIfMeaningful(it, out, max, requireQuestionMark = false) }
                                collect(value, out, max)
                            }
                        }
                    }
                }
                if (out.size >= max) return

                // (2) Traverse all fields; pick strings in question-like fields; recurse into nested structures.
                val iter2 = node.keys()
                while (iter2.hasNext() && out.size < max) {
                    val k = iter2.next()
                    val v = node.opt(k)
                    when (v) {
                        is JSONArray, is JSONObject -> collect(v, out, max)
                        is String -> {
                            val kn = normKey(k)

                            val isStrongField =
                                kn in QUESTION_FIELDS_STRONG_NORM ||
                                        kn == "question" ||
                                        kn.endsWith("-question") ||
                                        kn.endsWith("-q")

                            val isWeakField =
                                kn in QUESTION_FIELDS_WEAK_NORM ||
                                        kn.contains("follow-up") ||
                                        kn.contains("followup")

                            when {
                                isStrongField -> {
                                    addIfMeaningful(v, out, max, requireQuestionMark = false)
                                }
                                isWeakField -> {
                                    // Accept only if it looks like an actual question.
                                    addIfMeaningful(v, out, max, requireQuestionMark = true)
                                }
                            }
                        }
                    }
                }
            }

            is String -> addIfMeaningful(node, out, max, requireQuestionMark = false)
        }
    }

    /**
     * Try to extract a question string from common fields in [obj].
     *
     * Strategy:
     * - Build a normalized key→value map using [normKey].
     * - Strong fields: accept if non-blank.
     * - Weak fields: accept only if the string ends with '?' or '？'.
     * - Weak match: any field whose normalized name contains "question" and looks like a question.
     */
    private fun extractQuestionField(obj: JSONObject): String? {
        val normalizedMap = mutableMapOf<String, Any?>()
        val itAll = obj.keys()
        while (itAll.hasNext()) {
            val k = itAll.next()
            normalizedMap[normKey(k)] = obj.opt(k)
        }

        // Strong match: exact normalized candidate keys
        for (candidate in QUESTION_FIELDS_STRONG_RAW) {
            val v = normalizedMap[normKey(candidate)]
            if (v is String && v.isNotBlank()) return v.trim()
        }

        // Weak match: accept only if it looks like a real question
        for (candidate in QUESTION_FIELDS_WEAK_RAW) {
            val v = normalizedMap[normKey(candidate)]
            if (v is String && v.isNotBlank()) {
                val t = v.trim()
                if (t.endsWith("?") || t.endsWith("？")) return t
            }
        }

        // Weak match: any field whose normalized name contains "question"
        for ((kNorm, v) in normalizedMap) {
            if (kNorm.contains("question") && v is String && v.isNotBlank()) {
                val t = v.trim()
                // Require it to look like a question to avoid capturing blobs.
                if (t.endsWith("?") || t.endsWith("？")) return t
            }
        }

        return null
    }

    /**
     * Add a normalized non-empty string to [out] if still under [max].
     *
     * Heuristics:
     * - Trim and reject empty.
     * - Reject strings that are mostly punctuation/symbols.
     * - Optionally require a trailing question mark for weak-field acceptance.
     * - Cap length to avoid huge blobs.
     * - Coalesce trailing question marks to exactly one.
     */
    private fun addIfMeaningful(
        s: String,
        out: MutableSet<String>,
        max: Int,
        requireQuestionMark: Boolean
    ) {
        if (out.size >= max) return

        val t0 = s.trim()
        if (t0.isEmpty()) return

        if (requireQuestionMark && !(t0.endsWith("?") || t0.endsWith("？"))) return
        if (!containsAnyLetterOrDigit(t0)) return

        // Prevent prompt-size strings from being treated as questions.
        val t = if (t0.length > MAX_QUESTION_CHARS) t0.take(MAX_QUESTION_CHARS).trimEnd() else t0

        val normalized = TRAILING_QUESTION_REGEX.replace(t) { m ->
            // Preserve the type of question mark the model used
            if (m.value.contains('？')) "？" else "?"
        }

        // Avoid adding empty / punctuation-only after normalization.
        if (normalized.isBlank()) return
        if (!containsAnyLetterOrDigit(normalized)) return

        out.add(normalized)
    }

    private fun containsAnyLetterOrDigit(s: String): Boolean {
        for (ch in s) {
            if (ch.isLetterOrDigit()) return true
        }
        return false
    }

    /* ----------------------- Score (recursive JSON) ----------------------- */

    /** Allowed score keys (normalized). */
    private val SCORE_KEYS = setOf(
        "overall-score",
        "overallscore", // tolerance when separators are lost upstream
        "score"
    )

    private fun findScoreRecursive(obj: JSONObject): Int? {
        // 1) Direct keys on this object (normalized).
        val norm = mutableMapOf<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            norm[normKey(k)] = obj.opt(k)
        }
        for (k in SCORE_KEYS) {
            norm[k]?.let { v ->
                parseNumberOrNull(v)?.let { n -> return clamp0to100(n) }
            }
        }

        // 2) Recurse into child values.
        val it2 = obj.keys()
        while (it2.hasNext()) {
            val k = it2.next()
            when (val v = obj.opt(k)) {
                is JSONObject -> findScoreRecursive(v)?.let { return it }
                is JSONArray -> findScoreRecursive(v)?.let { return it }
            }
        }
        return null
    }

    private fun findScoreRecursive(arr: JSONArray): Int? {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is JSONObject -> findScoreRecursive(v)?.let { return it }
                is JSONArray -> findScoreRecursive(v)?.let { return it }
            }
        }
        return null
    }

    private fun parseNumberOrNull(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    /* ----------------------- Plain-text sentence split -------------------- */

    /**
     * A tiny sentence-like splitter used only for non-JSON fallback.
     *
     * This avoids zero-width regex split pitfalls and keeps punctuation
     * attached to the fragment.
     */
    private fun splitSentenceLike(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()

        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) out.add(t)
            sb.setLength(0)
        }

        for (ch in raw) {
            when (ch) {
                '\r', '\n' -> flush()
                '。', '．', '!', '！', '?', '？' -> {
                    sb.append(ch)
                    flush()
                }
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    /* ----------------------- JSON fragment extraction --------------------- */

    /**
     * Extract all code-fence bodies (```...```) anywhere in the raw text.
     *
     * More tolerant than strict newline-based fences:
     * - Accepts optional newline after the opening fence.
     * - Accepts optional newline before the closing fence.
     */
    private fun extractCodeFenceBodies(raw: String): List<String> {
        val re = Regex("""```[A-Za-z0-9_-]*\s*\n?([\s\S]*?)\n?```""")
        return re.findAll(raw).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * Extract JSON fragments embedded in [raw].
     *
     * Behavior:
     * - Attempts whole-string parse first; if it succeeds, returns a single fragment.
     * - Otherwise scans for balanced '{...}' / '[...]' regions while:
     *   - Respecting string literals.
     *   - Skipping escaped quotes.
     *
     * Safety:
     * - Limits scan size and fragment count to avoid worst-case blowups on huge garbage text.
     */
    private fun extractJsonFragments(raw: String): List<Any> {
        val sTrim = raw.trim()
        if (sTrim.isEmpty()) return emptyList()

        // Quick path: whole string is a single JSON value.
        parseAny(sTrim)?.let { return listOf(it) }

        // Reduce scan cost on very large strings: scan head + tail.
        val scanText = if (sTrim.length <= MAX_SCAN_CHARS) {
            sTrim
        } else {
            val headLen = (MAX_SCAN_CHARS * 0.6).toInt().coerceAtLeast(10_000)
            val tailLen = MAX_SCAN_CHARS - headLen
            sTrim.take(headLen) + "\n" + sTrim.takeLast(tailLen)
        }

        val fragments = mutableListOf<Any>()
        val n = scanText.length
        var i = 0

        while (i < n && fragments.size < MAX_FRAGMENTS) {
            val ch = scanText[i]
            if (ch == '{' || ch == '[') {
                val start = i
                val stack = ArrayDeque<Char>()
                stack.addLast(ch)
                var inString = false
                i++ // move past opener

                while (i < n && stack.isNotEmpty()) {
                    if (stack.size > MAX_STACK_DEPTH) {
                        // Too deep; treat as invalid and bail out of this opener.
                        break
                    }

                    val c = scanText[i]
                    if (inString) {
                        if (c == '\\') {
                            // Skip escaped char safely
                            i += if (i + 1 < n) 2 else 1
                            continue
                        } else if (c == '"') {
                            inString = false
                        }
                    } else {
                        when (c) {
                            '"' -> inString = true
                            '{' -> stack.addLast('{')
                            '[' -> stack.addLast('[')
                            '}' -> if (stack.isNotEmpty() && stack.last() == '{') stack.removeLast()
                            ']' -> if (stack.isNotEmpty() && stack.last() == '[') stack.removeLast()
                        }
                    }
                    i++
                }

                val endIdx = i
                if (stack.isEmpty() && endIdx <= n) {
                    val frag = scanText.substring(start, endIdx)
                    parseAny(frag)?.let { fragments.add(it) }
                    continue
                } else {
                    // Unbalanced or too deep; skip this opener and move on.
                    i = start + 1
                }
            } else {
                i++
            }
        }

        return fragments
    }

    /** Try to parse [s] into a JSONObject or JSONArray; returns null on failure. */
    private fun parseAny(s: String): Any? = try {
        val t = s.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}
