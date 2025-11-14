package com.negi.survey.vm

import org.junit.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.Model
import com.negi.survey.slm.SLM
import com.negi.survey.slm.SlmDirectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

open class AiViewModelSurveyBase {
    @get:Rule
    val modelRule = ModelAssetRule()

    protected lateinit var appCtx: Context
    protected lateinit var repo: SlmDirectRepository
    protected lateinit var vm: AiViewModel
    protected lateinit var config: SurveyConfig

    companion object {
        protected const val TAG = "AiViewModelSurvey"
        // ---- args & timeouts (unchanged) ----
        private fun argString(key: String): String? {
            val a = InstrumentationRegistry.getArguments()
            return a.getString(key) ?: System.getenv(key)
        }
        protected fun argInt(key: String): Int? = argString(key)?.toIntOrNull()
        protected fun argLong(key: String): Long? = argString(key)?.toLongOrNull()
        protected fun argBool(key: String): Boolean? = when (argString(key)?.lowercase()?.trim()) {
            "1","true","yes","y","on" -> true
            "0","false","no","n","off"-> false
            else -> null
        }
        @JvmStatic protected val INIT_TIMEOUT_SEC = 15L
        @JvmStatic protected val VM_TIMEOUT_SEC = 45L
        @JvmStatic protected val FIRST_CHUNK_TIMEOUT_MS: Long by lazy { argLong("FIRST_CHUNK_TIMEOUT_MS") ?: 5_000L }
        @JvmStatic protected val COMPLETE_TIMEOUT_MS: Long by lazy { argLong("COMPLETE_TIMEOUT_MS") ?: 45_000L }
        @JvmStatic protected val PER_PROMPT_GUARD_MS: Long by lazy { argLong("PER_PROMPT_GUARD_MS") ?: (VM_TIMEOUT_SEC * 1_000L + 10_000L) }
        @JvmStatic protected val INSTANCE_WAIT_MS: Long by lazy { argLong("INSTANCE_WAIT_MS") ?: 5_000L }
        @JvmStatic protected val BETWEEN_PROMPTS_IDLE_WAIT_MS: Long by lazy { argLong("IDLE_WAIT_MS") ?: 2_000L }
        @JvmStatic protected val BETWEEN_PROMPTS_COOLDOWN_MS: Long by lazy { argLong("COOLDOWN_MS") ?: 300L }
        @JvmStatic protected val MIN_STREAM_CHARS: Int by lazy { argInt("MIN_STREAM_CHARS") ?: 1 }
        @JvmStatic protected val MIN_FINAL_CHARS: Int by lazy { argInt("MIN_FINAL_CHARS") ?: 1 }
        @JvmStatic protected val PROMPT_LIMIT: Int? by lazy { argInt("PROMPT_LIMIT") }
        @JvmStatic protected val TEST_BUDGET_MS: Long by lazy { argLong("TEST_BUDGET_MS") ?: Long.MAX_VALUE }
        @JvmStatic protected val VERBOSE: Boolean by lazy { argBool("VERBOSE") ?: true }
        @JvmStatic protected val LOG_FULL_PROMPT: Boolean by lazy { argBool("LOG_FULL_PROMPT") ?: true }
        @JvmStatic protected lateinit var model: Model
        @JvmStatic protected val initialized = AtomicBoolean(false)

        @AfterClass @JvmStatic
        fun afterClass() {
            runCatching {
                if (::model.isInitialized && !SLM.isBusy(model)) {
                    SLM.cleanUp(model) {}
                }
            }.onFailure { Logx.w(TAG, "SLM cleanup failed: ${it.message}") }
        }
    }

    // ---- Public hook ----
    protected open fun configAssetName(): String = "survey_config1.yaml"

    @Before
    open fun setUp() {
        appCtx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1) Load config
        config = SurveyConfigLoader.fromAssets(appCtx, configAssetName())
        val issues = config.validate()
        Assert.assertTrue("SurveyConfig invalid:\n- " + issues.joinToString("\n- "), issues.isEmpty())

        // 2) Merge SLM runtime config
        //    IMPORTANT: do NOT pass the survey file here. Use a dedicated profile file or null.
        val mergedSlm = buildSlmRuntimeConfig(
            context = appCtx,
            configSlm = config.slm,
            assetYamlFile = "slm_config.yml", // ← put a small flat file in assets, or set to null
            hardDefaults = SlmDefaults(
                accelerator = Accelerator.GPU.label,
                maxTokens = 512,
                topK = 1,
                topP = 0.0,
                temperature = 0.0
            ),
            testOverrides = TestOverrides(
                accelerator = null,
                maxTokens = null,
                topK = null,
                topP = null,
                temperature = null
            )
        )

        // 2.5) Log merged map BEFORE model creation (very helpful)
        Logx.block(TAG, "SLM MERGED CONFIG (pre-Model)", buildString {
            mergedSlm.forEach { (k, v) -> appendLine("${k.name}: $v") }
        })

        // 3) Init model GPU → fallback CPU
        if (initialized.compareAndSet(false, true)) {
            val firstAccel =
                (mergedSlm[ConfigKey.ACCELERATOR] as? String)?.let {
                    if (it.equals(Accelerator.CPU.label, true)) Accelerator.CPU else Accelerator.GPU
                } ?: defaultAccel()

            model = Model(
                name = "gemma-3n-E4B-it",
                taskPath = modelRule.internalModel.absolutePath,
                config = mergedSlm.toMutableMap().apply {
                    normalizeNumberTypesInPlace(this)
                    put(ConfigKey.ACCELERATOR, firstAccel.label)
                }
            )

            var initErr = initialize(INIT_TIMEOUT_SEC)
            if (initErr.isNullOrEmpty()) {
                val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                check(ok) { "SLM instance not available within ${INSTANCE_WAIT_MS}ms" }
            }
            if (!initErr.isNullOrEmpty() && firstAccel != Accelerator.CPU) {
                Logx.w(TAG, "GPU init failed: $initErr → fallback to CPU")
                model = Model(
                    name = model.name,
                    taskPath = modelRule.internalModel.absolutePath,
                    config = model.config.toMutableMap().apply {
                        put(ConfigKey.ACCELERATOR, Accelerator.CPU.label)
                    }.also { normalizeNumberTypesInPlace(it) }
                )
                initErr = initialize(INIT_TIMEOUT_SEC)
                if (initErr.isNullOrEmpty()) {
                    val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                    check(ok) { "SLM instance not available (CPU) within ${INSTANCE_WAIT_MS}ms" }
                }
            }
            check(initErr.isNullOrEmpty()) { "SLM initialization error: $initErr" }
            Assert.assertNotNull("Model instance must be set", model.instance)
        } else {
            Assert.assertNotNull("Model instance must exist", model.instance)
        }

        // 3.5) Log final model config (after possible fallback)
        logModelConfig(model)

        // 4) Wire repo & VM
        repo = SlmDirectRepository(model, config)
        vm = AiViewModel(repo, timeout_ms = VM_TIMEOUT_SEC * 1000L)

        runCatching { Assert.assertFalse("SLM should be idle on start", SLM.isBusy(model)) }
            .onFailure { Logx.w(TAG, "SLM.isBusy check failed: ${it.message}") }
    }

    @After
    open fun tearDown() {
        runCatching { vm.cancel() }
        val idle = waitUntil(3_000L) { !SLM.isBusy(model) }
        if (idle) runCatching { SLM.resetSession(model) }
    }

    // ---- helpers (unchanged from your version except tiny improvements) ----

    protected fun defaultAccel(): Accelerator {
        val args = InstrumentationRegistry.getArguments()
        val acc = (args.getString("ACCELERATOR") ?: System.getenv("ACCELERATOR"))
            ?.uppercase()?.trim()
        return if (acc == "CPU") Accelerator.CPU else Accelerator.GPU
    }

    protected fun normalizeForModel(s: String): String = s
        .replace(Regex("[\\u2012-\\u2015]"), "-")
        .replace('\u00A0', ' ')
        .trim()

    protected fun fillPlaceholders(tpl: String, q: String, a: String): String {
        val hadQ = "{{QUESTION}}" in tpl
        val hadA = "{{ANSWER}}" in tpl
        val base = tpl.replace("{{QUESTION}}", q).replace("{{ANSWER}}", a).trim()
        return buildString {
            appendLine(base)
            if (hadQ && !base.contains("Question:", true)) appendLine("Question: $q")
            if (hadA && !base.contains("Answer:", true)) append("Answer: $a")
        }.trim()
    }

    protected fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            SystemClock.sleep(15L)
        }
        return false
    }

    protected fun initialize(timeoutSec: Long): String? {
        val latch = CountDownLatch(1)
        var initError: String? = null
        SLM.initialize(appCtx, model) { err ->
            initError = err
            latch.countDown()
        }
        Assert.assertTrue("SLM init timeout", latch.await(timeoutSec, TimeUnit.SECONDS))
        return initError
    }

    protected data class StrongAnswerStyle(
        val persona: String = "Kenyan smallholder maize farmer",
        val wordsMin: Int = 25,
        val wordsMax: Int = 35,
        val requireNumbers: Boolean = true,
        val requireUnits: Boolean = true,
        val mentionSeasonOrMonthIfImplied: Boolean = true,
        val forbidHedging: Boolean = true,
        val oneSentence: Boolean = true,
        val plainAscii: Boolean = true
    )

    protected fun buildStrongAnswerPrompt(
        question: String,
        style: StrongAnswerStyle = StrongAnswerStyle()
    ): String {
        val rules = buildString {
            appendLine("ROLE: ${style.persona}.")
            appendLine("TASK: Answer the question below as a definitive, exemplary response from your own perspective.")
            append("OUTPUT RULES: ")
            val rulesList = mutableListOf<String>()
            if (style.oneSentence) rulesList += "one sentence"
            rulesList += "between ${style.wordsMin}-${style.wordsMax} words (strict)"
            rulesList += "plain text only"
            rulesList += "single line only"
            rulesList += "no bullet points"
            rulesList += "no quotes"
            rulesList += "no follow-up questions"
            rulesList += "no preamble"
            if (style.plainAscii) rulesList += "ASCII punctuation only"
            if (style.requireNumbers) rulesList += "include at least one specific number or range"
            if (style.requireUnits) rulesList += "use clear units when applicable"
            if (style.mentionSeasonOrMonthIfImplied) rulesList += "mention season or month if relevant"
            if (style.forbidHedging) rulesList += "avoid hedging words"
            appendLine(rulesList.joinToString("; ") + ".")
            appendLine("TONE: practical, concise, first-person farmer voice (I/we), field-tested advice.")
            appendLine("CONSTRAIN: Do not restate the question. Do not add explanations.")
            appendLine()
            appendLine("Question: ${question.replace('\n', ' ').trim()}")
            append("Answer:")
        }.trim()
        return rules.replace(Regex("\\s+"), " ")
    }

    private class PartialAssembler {
        private val sb = StringBuilder()
        private var latest: String = ""
        fun ingest(part: String) { if (part.isNotEmpty()) { sb.append(part); latest = sb.toString() } }
        fun result(): String = latest
    }

    protected suspend fun generateAnswerWithSlm(
        model: Model,
        question: String,
        firstChunkTimeoutMs: Long = FIRST_CHUNK_TIMEOUT_MS,
        completeTimeoutMs: Long = COMPLETE_TIMEOUT_MS,
        quietMs: Long = 250L,
        enforceWordCap: Boolean = true
    ): String {
        check(waitUntil(firstChunkTimeoutMs) { !SLM.isBusy(model) }) {
            "SLM stayed busy for ${firstChunkTimeoutMs}ms before runInference"
        }

        val prompt = buildStrongAnswerPrompt(question)

        val firstSeen = CompletableDeferred<Unit>()
        val doneSeen = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        var lastChangeAt = SystemClock.elapsedRealtime()
        val assembler = PartialAssembler()

        SLM.runInference(
            model = model,
            input = prompt,
            listener = { partial: String, done: Boolean ->
                if (partial.isNotEmpty()) {
                    assembler.ingest(partial)
                    lastChangeAt = SystemClock.elapsedRealtime()
                    if (!firstSeen.isCompleted && partial.any { !it.isWhitespace() }) firstSeen.complete(Unit)
                }
                if (done && !doneSeen.isCompleted) {
                    doneSeen.complete(Unit)
                    if (!firstSeen.isCompleted) firstSeen.complete(Unit)
                }
            },
            onClean = {
                if (!cleaned.isCompleted) cleaned.complete(Unit)
                if (!firstSeen.isCompleted) firstSeen.complete(Unit)
            }
        )

        try {
            withTimeout(firstChunkTimeoutMs) { firstSeen.await() }
            val finished = withTimeoutOrNull(completeTimeoutMs) {
                while (true) {
                    if (doneSeen.isCompleted || cleaned.isCompleted) break
                    val quiet = SystemClock.elapsedRealtime() - lastChangeAt >= quietMs
                    if (quiet && !SLM.isBusy(model)) break
                    delay(25L)
                }
            } != null
            if (!finished) Logx.w(TAG, "slm stream soft-timeout; using partial (len=${assembler.result().length})")
        } finally {
            if (SLM.isBusy(model)) runCatching { SLM.cancel(model) }
            waitUntil(5_000L) { !SLM.isBusy(model) }
            runCatching { SLM.resetSession(model) }
            SystemClock.sleep(200L)
        }

        val out = assembler.result()
        require(out.isNotBlank()) { "empty answer from SLM for q='${question.take(80)}...'" }
        return out
    }

    protected fun oneLine(s: String?): String =
        s?.replace("\r", " ")?.replace("\n", " ")?.trim().orEmpty()

    // ---- SLM config merge & helpers ----

    protected data class SlmDefaults(
        val accelerator: String = Accelerator.GPU.label,
        val maxTokens: Int = 512,
        val topK: Int = 1,
        val topP: Double = 0.0,
        val temperature: Double = 0.0
    )
    protected data class TestOverrides(
        val accelerator: String? = null,
        val maxTokens: Int? = null,
        val topK: Int? = null,
        val topP: Double? = null,
        val temperature: Double? = null
    )

    protected fun buildSlmRuntimeConfig(
        context: Context,
        configSlm: SurveyConfig.SlmMeta?,
        assetYamlFile: String?,
        hardDefaults: SlmDefaults,
        testOverrides: TestOverrides
    ): Map<ConfigKey, Any> {
        val base = mutableMapOf<ConfigKey, Any>(
            ConfigKey.ACCELERATOR to hardDefaults.accelerator,
            ConfigKey.MAX_TOKENS  to hardDefaults.maxTokens,
            ConfigKey.TOP_K       to hardDefaults.topK,
            ConfigKey.TOP_P       to hardDefaults.topP,
            ConfigKey.TEMPERATURE to hardDefaults.temperature
        )
        fun putIfNotNull(k: ConfigKey, v: Any?) { if (v != null) base[k] = v }

        // config.slm
        configSlm?.let { slm ->
            putIfNotNull(ConfigKey.ACCELERATOR, slm.accelerator?.takeIf { it.isNotBlank() })
            putIfNotNull(ConfigKey.MAX_TOKENS,  slm.maxTokens)
            putIfNotNull(ConfigKey.TOP_K,       slm.topK)
            putIfNotNull(ConfigKey.TOP_P,       slm.topP)
            putIfNotNull(ConfigKey.TEMPERATURE, slm.temperature)
        }

        // assets/slm_config.yml (flat keys only)
        val appliedKeys = mutableListOf<String>()
        assetYamlFile?.let { fname ->
            runCatching {
                val yamlText = context.assets.open(fname).bufferedReader().use { it.readText() }
                val parsed = parseSimpleYamlSlmMap(yamlText)
                parsed["accelerator"]?.let { base[ConfigKey.ACCELERATOR] = it; appliedKeys += "accelerator" }
                (parsed["max_tokens"] as? Number)?.toInt()?.let { base[ConfigKey.MAX_TOKENS] = it; appliedKeys += "max_tokens" }
                (parsed["top_k"] as? Number)?.toInt()?.let      { base[ConfigKey.TOP_K] = it;      appliedKeys += "top_k" }
                (parsed["top_p"] as? Number)?.toDouble()?.let   { base[ConfigKey.TOP_P] = it;      appliedKeys += "top_p" }
                (parsed["temperature"] as? Number)?.toDouble()?.let { base[ConfigKey.TEMPERATURE] = it; appliedKeys += "temperature" }
            }.onSuccess {
                if (appliedKeys.isNotEmpty()) {
                    Logx.w(TAG, "assets/$fname applied keys: ${appliedKeys.joinToString()}")
                } else {
                    Logx.w(TAG, "assets/$fname present but no recognized SLM keys")
                }
            }.onFailure {
                Logx.w(TAG, "assets/$fname not applied: ${it.message}")
            }
        }

        // test overrides
        putIfNotNull(ConfigKey.ACCELERATOR, testOverrides.accelerator)
        putIfNotNull(ConfigKey.MAX_TOKENS,  testOverrides.maxTokens)
        putIfNotNull(ConfigKey.TOP_K,       testOverrides.topK)
        putIfNotNull(ConfigKey.TOP_P,       testOverrides.topP)
        putIfNotNull(ConfigKey.TEMPERATURE, testOverrides.temperature)

        // instrumentation args (final override)
        val args = InstrumentationRegistry.getArguments()
        fun argStr(name: String) = (args.getString(name) ?: System.getenv(name))?.trim()
        argStr("ACCELERATOR")?.takeIf { it.equals("CPU", true) || it.equals("GPU", true) }?.let {
            base[ConfigKey.ACCELERATOR] = it
        }
        argStr("MAX_TOKENS")?.toIntOrNull()?.let { base[ConfigKey.MAX_TOKENS] = it }
        argStr("TOP_K")?.toIntOrNull()?.let      { base[ConfigKey.TOP_K] = it }
        argStr("TOP_P")?.toDoubleOrNull()?.let   { base[ConfigKey.TOP_P] = it }
        argStr("TEMPERATURE")?.toDoubleOrNull()?.let {
            base[ConfigKey.TEMPERATURE] = it
        }

        // normalize & clamp
        normalizeNumberTypesInPlace(base)
        base[ConfigKey.TOP_P] = ((base[ConfigKey.TOP_P] as Number).toDouble()).coerceIn(0.0, 1.0)
        base[ConfigKey.TEMPERATURE] = maxOf((base[ConfigKey.TEMPERATURE] as Number).toDouble(), 0.0)

        return base
    }

    protected fun parseSimpleYamlSlmMap(yaml: String): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        yaml.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                var raw = line.substring(idx + 1).trim()
                if (raw.contains("#")) raw = raw.substringBefore("#").trim()
                raw = raw.trim().trim('"')
                when (key) {
                    "accelerator" -> if (raw.isNotEmpty()) map[key] = raw
                    "max_tokens", "top_k" -> raw.toIntOrNull()?.let { map[key] = it }
                    "top_p", "temperature" -> raw.toDoubleOrNull()?.let { map[key] = it }
                }
            }
        return map
    }

    protected fun normalizeNumberTypesInPlace(m: MutableMap<ConfigKey, Any>) {
        m[ConfigKey.MAX_TOKENS] = (m[ConfigKey.MAX_TOKENS] as? Number)?.toInt() ?: 256
        m[ConfigKey.TOP_K] = (m[ConfigKey.TOP_K] as? Number)?.toInt() ?: 1
        m[ConfigKey.TOP_P] = (m[ConfigKey.TOP_P] as? Number)?.toDouble() ?: 0.0
        m[ConfigKey.TEMPERATURE] = (m[ConfigKey.TEMPERATURE] as? Number)?.toDouble() ?: 0.0
    }

    protected fun dumpAllFollowups() {
        val fuList = try { vm.followups.value.toList() } catch (_: Throwable) { emptyList() }
        if (fuList.isEmpty()) {
            if (VERBOSE) Logx.block(TAG, "FOLLOWUPS (0)", "<none>")
            return
        }
        val body = buildString {
            fuList.forEachIndexed { i, s -> append(i + 1).append(". ").append(oneLine(s)).append('\n') }
        }.trimEnd()
        if (VERBOSE) Logx.block(TAG, "FOLLOWUPS (${fuList.size})", body)
    }

    protected fun logModelConfig(model: Model) {
        val cfg = model.config
        fun <T> get(k: ConfigKey, cast: (Any?) -> T?): T? = cast(cfg[k])
        val accel = get(ConfigKey.ACCELERATOR) { it as? String }
        val maxTokens = get(ConfigKey.MAX_TOKENS) { (it as? Number)?.toInt() }
        val topK = get(ConfigKey.TOP_K) { (it as? Number)?.toInt() }
        val topP = get(ConfigKey.TOP_P) { (it as? Number)?.toDouble() }
        val temperature = get(ConfigKey.TEMPERATURE) { (it as? Number)?.toDouble() }
        Logx.kv(TAG, "SLM MODEL CONFIG",
            mapOf(
                "name" to model.name,
                "taskPath" to model.taskPath,
                "ACCELERATOR" to (accel ?: "<unset>"),
                "MAX_TOKENS" to (maxTokens?.toString() ?: "<unset>"),
                "TOP_K" to (topK?.toString() ?: "<unset>"),
                "TOP_P" to (topP?.toString() ?: "<unset>"),
                "TEMPERATURE" to (temperature?.toString() ?: "<unset>")
            )
        )
    }

    // ---- runOnce unchanged (kept) ----
    protected suspend fun runOnce(
        prompt: String,
        firstChunkTimeoutMs: Long = FIRST_CHUNK_TIMEOUT_MS,
        completeTimeoutMs: Long = COMPLETE_TIMEOUT_MS,
        minStreamChars: Int = MIN_STREAM_CHARS,
        tailGraceMs: Long = 300L,
        minFinalChars: Int = MIN_FINAL_CHARS
    ): String {
        val job = vm.evaluateAsync(prompt)
        try {
            withTimeout(firstChunkTimeoutMs) {
                merge(
                    vm.stream.filter { it.length >= minStreamChars }.map { Unit },
                    vm.raw.filterNotNull().map { Unit },
                    vm.error.filterNotNull().map { e -> throw CancellationException("model error (first-signal): $e") }
                ).first()
            }
            val completionTag = withTimeout(completeTimeoutMs) {
                val loadingToFalseAfterChange = vm.loading.drop(1).filter { !it }.map { "LOADED" }
                merge(
                    vm.raw.filterNotNull().map { "RAW" },
                    loadingToFalseAfterChange,
                    vm.error.filterNotNull().map { e -> throw CancellationException("model error (completion): $e") }
                ).first()
            }
            if (completionTag == "LOADED" && vm.raw.value.isNullOrBlank()) {
                withTimeoutOrNull<Unit>(tailGraceMs) {
                    merge(
                        vm.raw.filterNotNull().map { Unit },
                        vm.stream.drop(1).map { Unit },
                        vm.error.filterNotNull().map { e -> throw CancellationException("model error (tail): $e") }
                    ).first()
                }
                val settleBudgetMs = minOf(150L, tailGraceMs / 2)
                val stableWindowMs = 80L
                val start = SystemClock.elapsedRealtime()
                var lastLen = vm.stream.value.length
                var lastChangeAt = start
                while (SystemClock.elapsedRealtime() - start < settleBudgetMs) {
                    delay(30L)
                    val now = SystemClock.elapsedRealtime()
                    val cur = vm.stream.value.length
                    if (cur != lastLen) { lastLen = cur; lastChangeAt = now }
                    if (now - lastChangeAt >= stableWindowMs) break
                }
            }
            val out = vm.raw.value?.takeIf { it.isNotBlank() } ?: vm.stream.value
            require(out.length >= minFinalChars) {
                "empty/short output @finalize: len=${out.length}, error=${vm.error.value}, loading=${vm.loading.value}, stream.len=${vm.stream.value.length}"
            }
            return out
        } finally {
            runCatching { vm.cancel() }
            runCatching { job.cancel() }
        }
    }
}
