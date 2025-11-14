// app/src/androidTest/java/com/negi/survey/vm/AiViewModelInstrumentationTest.kt
package com.negi.survey.vm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.Model
import com.negi.survey.slm.SLM
import com.negi.survey.slm.SlmDirectRepository

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelInstrumentationTest {

    @get:Rule val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: SlmDirectRepository
    private lateinit var vm: AiViewModel
    private lateinit var config: SurveyConfig

    companion object {
        private const val TAG = "AiVmInstrTest"
        private const val TIMEOUT_SEC = 60L
        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @AfterClass @JvmStatic
        fun afterClass() {
            runCatching { SLM.cleanUp(model) {} }
        }
    }

    @Before
    fun setUp() {
        appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        config = SurveyConfigLoader.fromAssets(appCtx, "survey_config1.yaml")
        val issues = config.validate()
        assertTrue("SurveyConfig invalid:\n- " + issues.joinToString("\n- "), issues.isEmpty())
        runBlocking {
            appCtx = InstrumentationRegistry.getInstrumentation().targetContext
            requireNotNull(appCtx) { "targetContext is null" }
            if (initialized.compareAndSet(false, true)) {
                model = Model(
                    name = "gemma3-local-test",
                    taskPath = modelRule.internalModel.absolutePath,
                    config = mapOf(
                        ConfigKey.ACCELERATOR to Accelerator.GPU.label,
                        ConfigKey.MAX_TOKENS to 4096,
                        ConfigKey.TOP_K to 40,
                        ConfigKey.TOP_P to 0.9f,
                        ConfigKey.TEMPERATURE to 0.7f
                    )
                )
                val latch = CountDownLatch(1)
                var initError: String? = null
                SLM.initialize(appCtx, model) { err ->
                    initError = err
                    latch.countDown()
                }
                assertTrue("SLM init timeout", latch.await(30, TimeUnit.SECONDS))
                require(initError != null) { "SLM init callback not invoked" }
                require(initError!!.isEmpty()) { "SLM initialization error: $initError" }
                assertNotNull("Model instance must be created", model.instance)
            }
            else {
                assertNotNull("Model instance must exist", model.instance)
            }

            repo = SlmDirectRepository(model,config)
            vm = AiViewModel(repo, timeout_ms = TIMEOUT_SEC * 1000)

            runCatching { assertFalse("busy should be false at test start", SLM.isBusy(model)) }
        }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { vm.cancel() } }
    }

    // ---- 強制JSONのプロンプト（スモーク用） ----
    private fun jsonPrompt(
        q: String = "How many days to harvest?",
        a: String = "About 90 days."
    ): String = buildString {
        appendLine("You are a strict JSON generator.")
        appendLine("Return a SINGLE-LINE JSON object with EXACT keys:")
        appendLine(" - \"analysis\": short string")
        appendLine(" - \"expected answer\": short string (<200 chars)")
        appendLine(" - \"follow-up questions\": array of EXACTLY 3 short strings")
        appendLine(" - \"score\": integer 0..100")
        appendLine("No markdown fences. No extra text. One line only.")
        append("Question: "); append(q); append("  ")
        append("Answer: "); append(a)
    }.trim()

    private suspend fun runOnce(
        prompt: String = jsonPrompt(),
        firstChunkTimeoutMs: Long = 60_000L,
        completeTimeoutMs: Long = 120_000L,
        minStreamChars: Int = 4
    ) {
        val job = vm.evaluateAsync(prompt)
        try {
            // 1) 「loading==true」or「stream に minStreamChars 以上」のどちらか早い方を待つ
            withTimeout(firstChunkTimeoutMs) {
                merge(
                    vm.loading.filter { it }.map { Unit },
                    vm.stream.filter { it.length >= minStreamChars }.map { Unit }
                ).first()
            }

            // 2) 完了の一次条件は raw!=null（ViewModel が確定させた合図）
            withTimeout(completeTimeoutMs) {
                vm.raw.first { it != null }
            }

            // 3) 任意：loading==false を“観測できるなら”観測（観測できなくてもスルー）
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                if (vm.loading.value) vm.loading.first { !it }
            }

            // 4) 検証
            require(vm.stream.value.isNotEmpty()) { "stream was empty" }


            val raw = vm.raw.value ?: error("raw was null (error=${vm.error.value})")
            vm.score.value?.let { require(it in 0..100) { "score out of range: $it" } }
            if (vm.followupQuestion.value != null) {
                require(!vm.followupQuestion.value.isNullOrBlank()) { "followupQuestion was blank" }
            }
        } finally {
            vm.cancel()
            job.cancel()
        }
    }

    // ---- Repeated smoke tests（ブロックボディ化） ----
    @Test fun canUseRealModel01() { runBlocking { runOnce() } }
    @Test fun canUseRealModel02() { runBlocking { runOnce() } }
    @Test fun canUseRealModel03() { runBlocking { runOnce() } }
    @Test fun canUseRealModel04() { runBlocking { runOnce() } }
    @Test fun canUseRealModel05() { runBlocking { runOnce() } }
    @Test fun canUseRealModel06() { runBlocking { runOnce() } }
    @Test fun canUseRealModel07() { runBlocking { runOnce() } }
    @Test fun canUseRealModel08() { runBlocking { runOnce() } }
    @Test fun canUseRealModel09() { runBlocking { runOnce() } }
    @Test fun canUseRealModel10() { runBlocking { runOnce() } }
    @Test fun canUseRealModel11() { runBlocking { runOnce() } }
    @Test fun canUseRealModel12() { runBlocking { runOnce() } }

    // ---- Behavior tests（ブロックボディ化） ----
    @Test
    fun cancelsCleanly() {
        runBlocking {
            val job = vm.evaluateAsync(jsonPrompt(q = "Write a poem slowly", a = "OK"))
            try {
                withTimeout(30_000) { vm.stream.filter { it.isNotEmpty() }.first() }
            } finally {
                vm.cancel()
                job.cancel()
            }
            withTimeout(30_000) { vm.loading.filter { it == false }.first() }
            val err = vm.error.value
            assertTrue("expected cancel; err=$err", err == null || err == "cancelled")
            Log.d(TAG, "cancel observed: err=$err streamLen=${vm.stream.value.length}")
        }
    }

    @Test
    fun timesOutProperly() {
        runBlocking {
            val job = vm.evaluateAsync(prompt = jsonPrompt(), timeoutMs = 1_000)
            try {
                withTimeout(30_000) { vm.loading.filter { it == false }.first() }
            } finally {
                vm.cancel()
                job.cancel()
            }
            val err = vm.error.value
            assertTrue("expected timeout; err=$err", err == null || err == "timeout")
            Log.d(TAG, "timeout observed: err=$err rawLen=${vm.raw.value?.toString()?.length ?: -1}")
        }
    }
}
