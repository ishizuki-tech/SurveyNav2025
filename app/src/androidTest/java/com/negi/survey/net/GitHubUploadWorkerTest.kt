package com.negi.survey.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_BRANCH
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_FILE_NAME
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_FILE_PATH
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_OWNER
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_PATH_PREFIX
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_REPO
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_TOKEN
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.text.Charsets

@RunWith(AndroidJUnit4::class)
class GitHubUploadWorkerTest {

    @Test
    fun missingToken_returnsFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = createPayloadFile(context.filesDir, "worker_missing_token.json")

        val input = androidx.work.workDataOf(
            KEY_OWNER to "owner",
            KEY_REPO to "repo",
            KEY_BRANCH to "main",
            KEY_TOKEN to "", // ブランクトークンは failure を誘発する
            KEY_PATH_PREFIX to "",
            KEY_FILE_PATH to payload.absolutePath,
            KEY_FILE_NAME to payload.name
        )

        val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
            .setInputData(input)
            .build()

        val result = worker.doWork()

        payload.delete()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun reachingRetryLimit_surfacesFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = createPayloadFile(context.filesDir, "worker_retry_limit.json")

        val input = androidx.work.workDataOf(
            KEY_OWNER to "owner",
            KEY_REPO to "repo",
            KEY_BRANCH to "main",
            KEY_TOKEN to "fake-token", // 無効でも例外経路に入る
            KEY_PATH_PREFIX to "",
            KEY_FILE_PATH to payload.absolutePath,
            KEY_FILE_NAME to payload.name
        )

        val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
            .setInputData(input)
            .setRunAttemptCount(5) // MAX_ATTEMPTS と同じ
            .build()

        val result = worker.doWork()

        payload.delete()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    private fun createPayloadFile(baseDir: File, name: String): File {
        val file = File(baseDir, name)
        file.parentFile?.mkdirs()
        file.writeText("{}", Charsets.UTF_8)
        return file
    }
}
