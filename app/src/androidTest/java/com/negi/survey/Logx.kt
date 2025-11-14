@file:Suppress("unused")

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/* ---------- chunked / boxed logger (ANDROID_MAX = 120) ---------- */
object Logx {
    /** 1 行あたりの上限（要望により 120） */
    private const val ANDROID_MAX = 120
    /** プレフィクス等の余白（安全マージン） */
    private const val SAFE_HEADROOM = 16
    /** 実際に 1 行として出力する最大長 */
    private val MAX_CHUNK = max(32, ANDROID_MAX - SAFE_HEADROOM)
    /** 枠内折返し時の 1 行幅（"║ " を除いた本文幅） */
    private val WRAP_WIDTH = max(20, MAX_CHUNK - 6)

    // ---- public API -------------------------------------------------------
    // 文字列そのまま版
    fun v(tag: String, msg: String) = emit(Log.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = emit(Log.DEBUG,   tag, msg)
    fun i(tag: String, msg: String) = emit(Log.INFO,    tag, msg)
    fun w(tag: String, msg: String) = emit(Log.WARN,    tag, msg)
    fun e(tag: String, msg: String) = emit(Log.ERROR,   tag, msg)
    fun wtf(tag: String, msg: String) = emit(Log.ASSERT, tag, msg)

    /** ボックス表示 */
    fun block(
        tag: String,
        title: String,
        body: String,
        priority: Int = Log.INFO,
    ) {
        chunk(priority, tag, "╔═ $title")
        if (body.isNotEmpty()) {
            val norm = body.normalize()
            for (line in norm.split('\n')) {
                val wrapped = wrap(line, WRAP_WIDTH)
                for (w in wrapped) {
                    chunk(priority, tag, "║ $w")
                }
            }
        }
        chunk(priority, tag, "╚═ end")
    }

    /** key-value を整形してボックス表示 */
    fun kv(
        tag: String,
        title: String,
        pairs: Map<String, String?>,
        priority: Int = Log.INFO,
        nullText: String = "null",
    ) {
        if (pairs.isEmpty()) {
            block(tag, title, "(empty)", priority)
            return
        }
        val keyWidth = pairs.keys.maxOf { it.length }
        val body = buildString {
            pairs.forEach { (k, raw) ->
                val v = (raw ?: nullText).normalize()
                val head = "${k.padEnd(keyWidth)}: "
                val lines = wrap(v, max(8, WRAP_WIDTH - head.length))
                if (lines.isEmpty()) {
                    appendLine(head)
                } else {
                    appendLine(head + lines.first())
                    for (i in 1 until lines.size) {
                        appendLine(" ".repeat(head.length) + lines[i])
                    }
                }
            }
        }.trimEnd()
        block(tag, title, body, priority)
    }

    /** 例外をボックス表示 */
    fun ex(
        tag: String,
        title: String,
        throwable: Throwable,
        priority: Int = Log.ERROR,
        message: String? = null,
    ) {
        val sb = StringBuilder()
        if (!message.isNullOrBlank()) {
            sb.appendLine(message.normalize())
            sb.appendLine()
        }
        sb.append(throwable.stackTraceString())
        block(tag, title, sb.toString(), priority)
    }

    // ---- internals --------------------------------------------------------

    /** 文字列を受け取り、必要なら分割して出力 */
    private fun emit(priority: Int, tag: String, message: String) {
        if (!isLoggable(tag, priority)) return
        chunk(priority, tag, message)
    }

    /** 1 呼び出し文字列を MAX_CHUNK で安全分割（インデックス表示なし） */
    private fun chunk(priority: Int, tag: String, text: String) {
        val t = text.normalize()
        if (t.length <= MAX_CHUNK) {
            Log.println(priority, tag, "$t  ")
            return
        }
        var i = 0
        val n = t.length
        while (i < n) {
            val end = min(n, i + MAX_CHUNK)
            Log.println(priority, tag, "${t.substring(i, end)}  ")
            i = end
        }
    }

    /** 単語境界（空白）優先で折り返し。見つからなければ強制折返し */
    private fun wrap(line: String, width: Int): List<String> {
        if (line.isEmpty()) return listOf("")
        if (width <= 1 || line.length <= width) return listOf(line)

        val out = ArrayList<String>(max(1, line.length / width + 1))
        var i = 0
        val n = line.length

        while (i < n) {
            var end = min(n, i + width)
            if (end < n) {
                // i..(end-1) に空白があるか後方から探索（手動ループで安全）
                var j = end - 1
                while (j > i && line[j] != ' ') j--
                if (j > i) end = j // 空白直前で切る（空白は次行へ持ち越さない）
            }
            val chunk = line.substring(i, end).trimEnd()
            out.add(chunk)
            // 次の開始位置：次文字が空白ならスキップ
            i = if (end < n && line[end] == ' ') end + 1 else end
        }
        return out
    }

    /** 改行正規化 + 末尾の余計な改行をカット */
    private fun String.normalize(): String =
        this.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')

    /** isLoggable は public inline から参照するため @PublishedApi internal に */
    @PublishedApi
    internal fun isLoggable(tag: String, priority: Int): Boolean =
        try { Log.isLoggable(tag, priority) } catch (_: Throwable) { true }

    /** cause 連鎖まで含めて自前整形 */
    private fun Throwable.stackTraceString(): String =
        buildString {
            appendLine(this@stackTraceString.toString())
            this@stackTraceString.stackTrace.forEach { appendLine("    at $it") }
            var c = this@stackTraceString.cause
            while (c != null && c !== this@stackTraceString) {
                appendLine("Caused by: $c")
                c.stackTrace.forEach { appendLine("    at $it") }
                c = c.cause
            }
        }.trimEnd()
}
