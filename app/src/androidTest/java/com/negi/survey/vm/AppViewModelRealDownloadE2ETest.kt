package com.negi.survey.vm

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@LargeTest
class EnsureModelDownloadedRealE2E {

    private lateinit var ctx: Context
    private lateinit var baseClient: OkHttpClient

    @Before
    fun setup() {

        ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val token = BuildConfig.HF_TOKEN
        Assume.assumeTrue("HF_TOKEN is blank. Set a valid token to run this test.",
            token.isNotBlank()
        )

        baseClient = AppViewModel.defaultClient(token)
    }

    @After
    fun tearDown() { /* no-op */ }

    @Test
    fun smoke_range_1MiB() = runBlocking<Unit> {
        val url = AppViewModel.DEFAULT_MODEL_URL
        Assume.assumeTrue("Target not reachable", canReach(url))

        val rangedClient = baseClient.newBuilder()
            .addNetworkInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Range", "bytes=0-1048575") // 先頭1MiBだけ
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .build()

        val vm = AppViewModel(modelUrl = url, client = rangedClient)

        // 既存ファイル掃除
        val fileName = Uri.parse(url).lastPathSegment ?: "model.litertlm"
        File(ctx.filesDir, fileName).delete()

        vm.ensureModelDownloaded(ctx)

        val done = withTimeout(120_000) {
            vm.state.first { it is DlState.Done } as DlState.Done
        }

        Log.i("RealDlE2E", "Downloaded ${done.file} len=${done.file.length()}")
        assertTrue(done.file.exists())
        assertTrue("file should be partial (<=1MiB)", done.file.length() in 1..1_048_576)

        done.file.delete()
    }

    @Test
    fun without_token_should_error() = runBlocking<Unit> {
        val url = AppViewModel.DEFAULT_MODEL_URL
        Assume.assumeTrue("Target not reachable", canReach(url))

        val noAuthClient = AppViewModel.defaultClient(hfToken = null)
        val vm = AppViewModel(modelUrl = url, client = noAuthClient)

        // 既存ファイル掃除
        val fileName = Uri.parse(url).lastPathSegment ?: "model.litertlm"
        File(ctx.filesDir, fileName).delete()

        vm.ensureModelDownloaded(ctx)

        val err = withTimeout(60_000) {
            vm.state.first { it is DlState.Error } as DlState.Error
        }
        assertTrue("expected HTTP error, but was: ${err.message}", err.message.contains("HTTP"))
    }

    private fun canReach(url: String): Boolean = try {
        val head = Request.Builder().url(url).head().build()
        baseClient.newCall(head).execute().use { r ->
            r.isSuccessful || (r.code in 200..399)
        }
    } catch (_: Throwable) { false }
}
