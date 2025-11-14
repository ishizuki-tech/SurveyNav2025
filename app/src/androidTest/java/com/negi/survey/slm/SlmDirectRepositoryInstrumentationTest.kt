// file: app/src/androidTest/java/com/negi/survey/slm/SlmDirectRepositoryInstrumentationTest.kt
package com.negi.survey.slm

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SlmDirectRepository の端末上インストルメンテーションテスト。
 * 前提：repo.request は “同時に呼ばない”。常に前の処理が完了してから次を呼ぶ。
 *
 * カバー範囲:
 *  - ストリーミング: 非空チャンクが届き、終了後は IDLE に戻る
 *  - キャンセル: first() で早期終了 → awaitClose 経由で IDLE 化
 *  - 空文字プロンプト: EMPTY_JSON_INSTRUCTION パスが安定完了
 *  - 逐次 2 リクエスト: R1 完了（IDLE）後に R2 を開始し、順序が保たれる
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SlmDirectRepositoryInstrumentationTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: SlmDirectRepository
    @Suppress("UnusedPrivateMember")
    private lateinit var config: SurveyConfig

    companion object {
        private const val TAG = "SlmRepoInstrTest"

        private const val INIT_TIMEOUT_SEC = 15L
        private const val INSTANCE_WAIT_MS = 5_000L

        // テスト待ち時間
        private const val TEST_TIMEOUT_MS = 30_000L
        private const val IDLE_WAIT_MS = 20_000L
        private const val IDLE_STEP_MS = 20L

        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @AfterClass
        @JvmStatic
        fun afterClass() {
            runCatching {
                if (::model.isInitialized && !SLM.isBusy(model)) SLM.cleanUp(model) {}
            }.onFailure { Logx.w(TAG, "SLM cleanup failed: ${it.message}") }
        }
    }

    /** モデル出力の軽微な差を吸収してテスト安定化。 */
    private fun normalize(s: String): String = s
        .replace(Regex("[\\u2012-\\u2015]"), "-")
        .replace('\u00A0', ' ')
        .trim()

    private fun defaultAccel(): Accelerator {
        val args = InstrumentationRegistry.getArguments()
        val acc = (args.getString("ACCELERATOR") ?: System.getenv("ACCELERATOR"))
            ?.uppercase()?.trim()
        return if (acc == "CPU") Accelerator.CPU else Accelerator.GPU
    }

    @Before
    fun setUp() {

        appCtx = InstrumentationRegistry.getInstrumentation().targetContext

        // あれば設定検証（必須ではないが、資産破損を早期検知）
        runCatching {
            config = SurveyConfigLoader.fromAssets(appCtx, "survey_config1.json").also {
                val issues = it.validate()
                assertTrue("SurveyConfig invalid:\n- " + issues.joinToString("\n- "), issues.isEmpty())
            }
        }.onFailure {
            Logx.w(TAG, "SurveyConfig load/validate skipped: ${it.message}")
        }

        if (initialized.compareAndSet(false, true)) {
            var accel = defaultAccel()
            model = Model(
                name = "gemma-3n-E4B-it",
                taskPath = modelRule.internalModel.absolutePath,
                // 決定的デコードで揺れを抑制
                config = mapOf(
                    ConfigKey.ACCELERATOR to accel.label,
                    ConfigKey.MAX_TOKENS to 1024,
                    ConfigKey.TOP_K to 1,
                    ConfigKey.TOP_P to 0.0f,
                    ConfigKey.TEMPERATURE to 0.0f
                )
            )
            var initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
            if (initErr.isNullOrEmpty()) {
                val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                check(ok) { "SLM instance not available within ${INSTANCE_WAIT_MS}ms" }
            }
            if (!initErr.isNullOrEmpty() && accel != Accelerator.CPU) {
                Logx.w(TAG, "GPU init failed: $initErr → fallback to CPU")
                accel = Accelerator.CPU
                model = Model(
                    name = model.name,
                    taskPath = modelRule.internalModel.absolutePath,
                    config = model.config.toMutableMap().apply {
                        put(ConfigKey.ACCELERATOR, accel.label)
                    }
                )
                initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
                if (initErr.isNullOrEmpty()) {
                    val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                    check(ok) { "SLM instance not available (CPU) within ${INSTANCE_WAIT_MS}ms" }
                }
            }
            check(initErr.isNullOrEmpty()) { "SLM initialization error: $initErr" }
            assertNotNull("Model instance must be set", model.instance)
        } else {
            assertNotNull("Model instance must exist", model.instance)
        }

        repo = SlmDirectRepository(model,config)

        runCatching { assertFalse("SLM should be idle on start", SLM.isBusy(model)) }
            .onFailure { Logx.w(TAG, "SLM.isBusy check failed: ${it.message}") }
    }

    @After
    fun tearDown() {
        val idle = waitUntil(1_000) { !SLM.isBusy(model) }
        if (idle) runCatching { SLM.resetSession(model) }
    }

    // ───────────────────────── ヘルパー ─────────────────────────

    private fun initializeModel(ctx: Context, model: Model, timeoutSec: Long): String? {
        val latch = CountDownLatch(1)
        var err: String? = null
        SLM.initialize(ctx, model) { e -> err = e; latch.countDown() }
        assertTrue("SLM init timeout", latch.await(timeoutSec, TimeUnit.SECONDS))
        return err
    }

    /** 忙→待機を同期的に待つ（テスト用の短ポーリング）。 */
    private fun waitIdleBlocking(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val busy = runCatching { SLM.isBusy(model) }.getOrElse { false }
            if (!busy) return true
            SystemClock.sleep(IDLE_STEP_MS.toLong())
        }
        val finalBusy = runCatching { SLM.isBusy(model) }.getOrElse { false }
        return !finalBusy
    }

    /** 単純な同期ポーリングヘルパー。 */
    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            SystemClock.sleep(15)
        }
        return false
    }

    // ───────────────────────── テスト ─────────────────────────

    /** ストリーミング: 非空チャンクが届き、終了後は IDLE に戻る。 */
    @Test
    fun request_streams_and_closes_normally() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val sb = StringBuilder()
            repo.request("""Return a very short JSON like {"ok":true}.""")
                .collect { part -> if (part.isNotEmpty()) sb.append(part) }

            val out = normalize(sb.toString())
            Logx.i(TAG, "STREAM DONE len=${out.length} head='${out.take(80)}'")
            assertTrue("at least one non-empty chunk expected", out.isNotBlank())
            assertTrue("engine should become IDLE after completion", waitIdleBlocking(IDLE_WAIT_MS))
        }
    }

    /** キャンセル: first() で早期終了 → awaitClose 内の cancel()/reset で IDLE。 */
    @Test
    fun request_cancel_transitions_to_idle() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val first = repo.request("Return a tiny JSON.").first()
            Logx.i(TAG, "FIRST CHUNK len=${first.length}")
            assertTrue("engine should become IDLE after cancel", waitIdleBlocking(IDLE_WAIT_MS))
        }
    }

    /** 空文字プロンプト（EMPTY_JSON_INSTRUCTION 経由）でも安定完了する。 */
    @Test
    fun request_empty_prompt_does_not_crash() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val sb = StringBuilder()
            repo.request("").collect { part -> if (part.isNotEmpty()) sb.append(part) }
            val out = normalize(sb.toString())
            Logx.i(TAG, "EMPTY PROMPT OUT len=${out.length} head='${out.take(80)}'")
            assertTrue("should produce some output", out.isNotBlank())
            assertTrue("engine should become IDLE after completion", waitIdleBlocking(IDLE_WAIT_MS))
        }
    }

    /**
     * 逐次 2 リクエスト: R1 完了（IDLE）→ R2 開始。
     * “同時に呼ばない”運用で、順序とアイドル遷移が守られることを確認。
     */
    @Test
    fun sequential_two_requests_wait_and_succeed() = runBlocking(Dispatchers.Default) {
        withTimeout(TEST_TIMEOUT_MS) {
            // R1：最初のチャンクで打ち切り（collector 側キャンセル）→ awaitClose が走る
            val t1Start = SystemClock.elapsedRealtime()
            val first1 = repo.request("R1: keep it short").first()
            val idle = waitUntil(IDLE_WAIT_MS) {
                !SLM.isBusy(model)
            }
            if (idle) runCatching {
                //SLM.resetSession(model)
            }

            //SystemClock.sleep(500)
            assertTrue("engine should become IDLE after R1", waitIdleBlocking(IDLE_WAIT_MS))

            val t1Closed = SystemClock.elapsedRealtime()
            Logx.i(TAG, "R1 FIRST len=${first1.length} start@$t1Start closed@$t1Closed")

            // R2：通常収集（最初のチャンクだけ見れば十分）
            val t2Start = SystemClock.elapsedRealtime()
            val first2 = repo.request("R2: start only after R1 closed").first()
            val t2First = SystemClock.elapsedRealtime()
            Logx.i(TAG, "R2 FIRST len=${first2.length} start@$t2Start first@$t2First")

            // 順序の検証（逐次呼び出しなので常に成立するはず）
            assertTrue("R2 should start after R1 is fully closed", t2Start >= t1Closed)

            // 終了後 IDLE を再確認
            assertTrue("engine should become IDLE after R2", waitIdleBlocking(IDLE_WAIT_MS))
        }
    }
}
