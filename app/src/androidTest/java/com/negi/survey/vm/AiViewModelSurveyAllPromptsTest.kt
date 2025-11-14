package com.negi.survey.vm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.negi.survey.slm.SLM
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelSurveyAllPromptsTest : AiViewModelSurveyBase() {
    override fun configAssetName(): String = "survey_config1.yaml"
    @Test
    fun evaluateAllPrompts() = runBlocking {

        logModelConfig(model)

        val questionById = config.graph.nodes.associate { it.id to it.question }
        val all = config.prompts
        val prompts = PROMPT_LIMIT?.takeIf { it > 0 }?.let { all.take(it) } ?: all

        var tested = 0
        val testStart = System.currentTimeMillis()

        for (idx in prompts.indices) {
            val p = prompts[idx]

            val elapsed = System.currentTimeMillis() - testStart
            if (elapsed > TEST_BUDGET_MS) {
                Logx.w(
                    TAG,
                    "Test budget exceeded: $elapsed ms > $TEST_BUDGET_MS ms; stopping at idx=$idx"
                )
                break
            }

            val originalQuestion = questionById[p.nodeId]
            assumeTrue(
                "Skip: no question for nodeId=${p.nodeId}",
                !originalQuestion.isNullOrBlank()
            )

            val generatedAnswer = generateAnswerWithSlm(
                model = model,
                question = originalQuestion!!,
                firstChunkTimeoutMs = FIRST_CHUNK_TIMEOUT_MS,
                completeTimeoutMs = COMPLETE_TIMEOUT_MS,
                quietMs = 250L,
                enforceWordCap = true
            )

            Logx.kv(
                TAG, "Q&A",
                mapOf(
                    "Original Question" to originalQuestion,
                    "Generated Answer " to generatedAnswer)
            )

            val answerText = normalizeForModel(generatedAnswer)
            val filledPrompt = fillPlaceholders(p.prompt, originalQuestion, answerText)
            val fullPrompt = repo.buildPrompt(filledPrompt)

            if (VERBOSE) {
                Logx.kv(
                    TAG, "PROMPT META",
                    mapOf(
                        "Index" to "${idx + 1}/${config.prompts.size}",
                        "NodeId" to p.nodeId,
                        "Template.len" to "${p.prompt.length}",
                        "Question.len" to "${originalQuestion.length}",
                        "Answer.len" to "${answerText.length}",
                        "FullPrompt.len" to "${fullPrompt.length}"
                    )
                )
                if (LOG_FULL_PROMPT) Logx.block(TAG, "FULL PROMPT", fullPrompt)
            }

            val t0 = System.currentTimeMillis()
            val out: String = try {
                withTimeout(PER_PROMPT_GUARD_MS) {
                    runOnce(
                        prompt = fullPrompt,
                        firstChunkTimeoutMs = FIRST_CHUNK_TIMEOUT_MS,
                        completeTimeoutMs = COMPLETE_TIMEOUT_MS,
                        minStreamChars = MIN_STREAM_CHARS,
                        tailGraceMs = 300L,
                        minFinalChars = MIN_FINAL_CHARS
                    )
                }
            } catch (t: Throwable) {
                runCatching { vm.cancel() }
                runCatching { if (SLM.isBusy(model)) SLM.cancel(model) }
                val errState =
                    "vm.error=${vm.error.value}, loading=${vm.loading.value}, stream.len=${vm.stream.value.length}"
                throw AssertionError(
                    "Model error (phase=await) for nodeId=${p.nodeId}: ${t::class.simpleName}: ${t.message} ($errState)",
                    t
                )
            }

            val dur = System.currentTimeMillis() - t0
            assertTrue("Empty output for nodeId=${p.nodeId}", out.isNotBlank())

            val outOne = oneLine(out)
            val followupsCount = vm.followups.value?.size ?: 0
            val scoreVal = vm.score.value
            scoreVal?.let { assertTrue("score must be 1..100", it in 1..100) }

            if (VERBOSE) {

                val qLog = originalQuestion.replace("\r", " ").replace("\n", " ").take(200)
                val aLog = answerText.replace("\r", " ").replace("\n", " ").take(200)
                val scoreLog = scoreVal?.toString() ?: "<none>"

                Logx.kv(
                    TAG, "EVAL DONE", mapOf(
                        "Raw.Buf" to outOne,
                        "Raw.len" to "${outOne.length}",
                        "Question" to qLog,
                        "Answer" to aLog,
                        "Score" to scoreLog,
                        "Followups.count" to "$followupsCount",
                        "Duration in ms" to "$dur"
                    )
                )
            }

            dumpAllFollowups()

            val becameIdle = waitUntil(BETWEEN_PROMPTS_IDLE_WAIT_MS) { !SLM.isBusy(model) }
            assertTrue("Engine did not become idle after nodeId=${p.nodeId}", becameIdle)
            if (becameIdle) runCatching { SLM.resetSession(model) }

            SystemClock.sleep(BETWEEN_PROMPTS_COOLDOWN_MS)
            tested++
        }

        assertTrue("No prompts were tested (0)", tested > 0)
    }
}
