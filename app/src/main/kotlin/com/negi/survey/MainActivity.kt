/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: MainActivity.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("UnusedParameter", "UnusedImport")

package com.negi.survey

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.screens.AiScreen
import com.negi.survey.screens.ConfigOptionUi
import com.negi.survey.screens.DoneScreen
import com.negi.survey.screens.IntroScreen
import com.negi.survey.screens.ReviewScreen
import com.negi.survey.screens.SpeechController
import com.negi.survey.screens.UploadProgressOverlay
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.LiteRtLM
import com.negi.survey.slm.LiteRtRepository
import com.negi.survey.slm.Model
import com.negi.survey.slm.Repository
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.AppViewModel
import com.negi.survey.vm.DlState
import com.negi.survey.vm.DownloadGate
import com.negi.survey.vm.FlowAI
import com.negi.survey.vm.FlowDone
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.FlowReview
import com.negi.survey.vm.FlowText
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.WhisperSpeechController
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root activity of the SurveyNav app.
 *
 * This activity is intentionally thin:
 * - Applies edge-to-edge system bar styling.
 * - Installs a crash capture handler (logcat snapshot + exception).
 * - On next startup, schedules pending crash reports for upload via WorkManager.
 * - Delegates all runtime state and UI composition to [AppNav].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Install crash capture as early as possible.
        runCatching { CrashCapture.install(applicationContext) }
            .onFailure { Log.w("CrashCapture", "install failed: ${it.message}", it) }

        // 2) On startup, enqueue any pending crash files for upload (if GH config is present).
        runCatching { CrashCapture.enqueuePendingCrashUploadsIfPossible(applicationContext) }
            .onFailure { Log.w("CrashCapture", "enqueuePendingCrashUploads failed: ${it.message}", it) }

        /**
         * Prefer the modern edge-to-edge API.
         *
         * NOTE:
         * - On Android 15+, setting statusBarColor/navigationBarColor is deprecated and often ignored.
         * - We keep system bars transparent and draw the desired "backplate" behind them in Compose.
         */
        try {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            )
        } catch (_: Throwable) {
            // Legacy fallback: still keep edge-to-edge and control icon appearance via insets controller.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { window.isNavigationBarContrastEnforced = false }
            }
        }

        setContent {
            MaterialTheme {
                SystemBarsBackplate(
                    statusBarColor = Color.Black,
                    navigationBarColor = Color.Black
                ) {
                    AppNav()
                }
            }
        }
    }
}

/* ───────────────────────────── Visual Utilities ───────────────────────────── */

/**
 * Draw a solid background behind the system bar insets.
 *
 * This avoids deprecated status/navigation bar color APIs and matches the Android 15+ edge-to-edge model:
 * keep system bars transparent, and render your own backplate behind them.
 */
@Composable
private fun SystemBarsBackplate(
    statusBarColor: Color,
    navigationBarColor: Color,
    content: @Composable () -> Unit
) {
    // Compute inset heights without windowInsetsHeight() (works on older Compose versions).
    val statusBarHeight = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()

    val navBarHeight = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        // Status bar backplate.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarHeight)
                .background(statusBarColor)
                .align(Alignment.TopCenter)
        )

        // Navigation bar backplate.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(navBarHeight)
                .background(navigationBarColor)
                .align(Alignment.BottomCenter)
        )

        // App content.
        // Use systemBars padding for maximum compatibility across Compose versions.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
        ) {
            content()
        }
    }
}

/**
 * A simple vertical gradient used as a dark backplate behind loading/error cards.
 */
@Composable
private fun animatedBackplate(): Brush =
    Brush.verticalGradient(
        0f to Color(0xFF202020),
        1f to Color(0xFF040404)
    )

/**
 * An ultra-thin neon-like edge glow for cards.
 *
 * This uses a radial gradient centered on the composable surface to create
 * a subtle halo that remains readable on a monochrome palette.
 */
@Composable
private fun Modifier.neonEdgeThin(
    color: Color = MaterialTheme.colorScheme.primary,
    intensity: Float = 0.035f,
    corner: Dp = 20.dp
): Modifier = this.then(
    Modifier.drawBehind {
        val radius = size.minDimension * 0.45f
        val cr = corner.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = intensity), Color.Transparent),
                center = center,
                radius = radius
            ),
            cornerRadius = CornerRadius(cr, cr)
        )
    }
)

/* ───────────────────────────── Init Gate ───────────────────────────── */

@Composable
fun InitGate(
    modifier: Modifier = Modifier,
    key: Any? = Unit,
    init: suspend () -> Unit,
    progressText: String = "Initializing…",
    subText: String = "Preparing on-device model and resources",
    onErrorMessage: (Throwable) -> String = { it.message ?: "Initialization failed" },
    content: @Composable () -> Unit
) {
    var isLoading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<Throwable?>(null) }
    var initJob by remember(key) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * Starts or restarts the initialization coroutine.
     *
     * Defensive:
     * - Cancels any in-flight init job to avoid concurrent init races.
     * - Keeps UI state coherent even when cancellation happens mid-flight.
     */
    fun kick() {
        initJob?.cancel()
        isLoading = true
        error = null
        initJob = scope.launch {
            try {
                init()
                isLoading = false
            } catch (ce: CancellationException) {
                // Cancellation is expected when key changes or composable disposes.
                throw ce
            } catch (t: Throwable) {
                error = t
                isLoading = false
            }
        }
    }

    /**
     * Cancel running init if this gate leaves composition.
     */
    DisposableEffect(key) {
        onDispose {
            initJob?.cancel()
        }
    }

    /**
     * Run initialization once when the given [key] enters composition.
     */
    LaunchedEffect(key) {
        kick()
    }

    val backplate = animatedBackplate()

    when {
        isLoading -> {
            Box(
                modifier
                    .fillMaxSize()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin()
                ) {
                    Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))

                        val pulse = rememberInfiniteTransition(label = "init_gate_pulse")
                        val alpha by pulse.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1100, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "init_gate_alpha"
                        )

                        Text(
                            progressText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            subText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        error != null -> {
            Box(
                modifier
                    .fillMaxSize()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin(
                            color = MaterialTheme.colorScheme.error,
                            intensity = 0.05f
                        )
                ) {
                    Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            onErrorMessage(error!!),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { kick() }) {
                            Text("Retry")
                        }
                    }
                }
            }
        }

        else -> {
            content()
        }
    }
}

/* ──────────────────────── Audio Permission Gate ─────────────────────────── */

@Composable
fun AudioPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val permission = Manifest.permission.RECORD_AUDIO

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    /**
     * Re-check permission when returning from Settings.
     */
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission =
                    ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Microphone permission is required for voice input.",
                    actionLabel = "Settings"
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    if (hasPermission) {
        content()
    } else {
        val backplate = animatedBackplate()

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(backplate)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .wrapContentWidth()
                    .neonEdgeThin()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Microphone",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "Microphone permission needed",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "To use voice input for survey answers, allow microphone access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { launcher.launch(permission) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Allow microphone")
                    }
                    IconButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Open app settings"
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

/* ───────────────────────────── App Nav Root ───────────────────────────── */

private const val DEFAULT_WHISPER_ASSET_MODEL = "models/ggml-small-q5_1.bin"
private const val DEFAULT_WHISPER_LANGUAGE = "auto"

@Composable
fun AppNav() {
    val appContext = LocalContext.current.applicationContext

    val options = remember(appContext) {
        val assetManager = appContext.assets

        // Collect YAML config candidates (supports subfolders such as "configs/").
        val yamlFiles = listAssetYamlConfigs(assetManager)
            .filter { path ->
                val name = path.substringAfterLast('/')
                name.endsWith(".yaml") && (name.startsWith("survey_") || name.startsWith("survey_config"))
            }
            .sorted()

        val mapped = yamlFiles.map { path ->
            val fileName = path.substringAfterLast('/')
            configOptionFromFileName(fileName = fileName).copy(id = path)
        }

        mapped.ifEmpty {
            listOf(
                ConfigOptionUi(
                    id = "survey_config1.yaml",
                    label = "Default config",
                    description = "Fallback survey configuration loaded from survey_config1.yaml."
                )
            )
        }
    }

    var chosen by remember { mutableStateOf<ConfigOptionUi?>(null) }
    var config by remember { mutableStateOf<SurveyConfig?>(null) }
    var configLoading by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }

    var selectionEpoch by remember { mutableStateOf(0) }

    if (chosen == null) {
        IntroScreen(
            options = options,
            defaultOptionId = options.firstOrNull()?.id,
            onStart = { option ->
                selectionEpoch += 1
                config = null
                configError = null
                configLoading = false
                chosen = option

                Log.d(
                    "MainActivity",
                    "Intro -> Start session. epoch=$selectionEpoch, file=${option.id}"
                )
            }
        )
        return
    }

    val sessionKey = remember(chosen!!.id, selectionEpoch) {
        "${chosen!!.id}@$selectionEpoch"
    }

    LaunchedEffect(sessionKey) {
        configLoading = true
        configError = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                SurveyConfigLoader.fromAssetsValidated(appContext, chosen!!.id)
            }
            config = loaded
            Log.d("MainActivity", "Config loaded. session=$sessionKey")
        } catch (t: Throwable) {
            config = null
            configError = t.message ?: "Failed to load survey configuration."
            Log.e("MainActivity", "Config load failed. session=$sessionKey", t)
        } finally {
            configLoading = false
        }
    }

    val backplate = animatedBackplate()

    when {
        configLoading || (config == null && configError == null) -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin()
                ) {
                    Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Loading survey configuration…",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Parsing YAML graph and SLM/Whisper metadata",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            return
        }

        configError != null -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin(
                            color = MaterialTheme.colorScheme.error,
                            intensity = 0.05f
                        )
                ) {
                    Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = configError!!,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                Log.d("MainActivity", "Error -> Back to selector. session=$sessionKey")
                                chosen = null
                                config = null
                                configError = null
                                configLoading = false
                            }
                        ) {
                            Text("Back to config selector")
                        }
                    }
                }
            }
            return
        }
    }

    val cfg = config!!

    val sessionVmStore = remember(sessionKey) { ViewModelStore() }
    val sessionVmOwner = remember(sessionVmStore) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = sessionVmStore
        }
    }

    DisposableEffect(sessionKey) {
        onDispose {
            Log.d("MainActivity", "Session dispose -> clearing ViewModelStore. session=$sessionKey")
            sessionVmStore.clear()
        }
    }

    val appVm: AppViewModel = viewModel(
        viewModelStoreOwner = sessionVmOwner,
        key = "AppViewModel_$sessionKey",
        factory = AppViewModel.factoryFromOverrides(
            modelUrlOverride = cfg.modelDefaults.defaultModelUrl,
            fileNameOverride = cfg.modelDefaults.defaultFileName,
            timeoutMsOverride = cfg.modelDefaults.timeoutMs,
            uiThrottleMsOverride = cfg.modelDefaults.uiThrottleMs,
            uiMinDeltaBytesOverride = cfg.modelDefaults.uiMinDeltaBytes
        )
    )

    val state by appVm.state.collectAsState()

    LaunchedEffect(state) {
        if (state is DlState.Idle) {
            Log.d("MainActivity", "DownloadGate idle -> start download. session=$sessionKey")
            appVm.ensureModelDownloaded(appContext)
        }
    }

    DownloadGate(
        state = state,
        onRetry = {
            Log.d("MainActivity", "DownloadGate retry. session=$sessionKey")
            appVm.ensureModelDownloaded(appContext)
        }
    ) { modelFile ->

        val modelConfig = remember(cfg) { buildModelConfig(cfg.slm) }

        val slmModel = remember(
            modelFile.absolutePath,
            modelConfig,
            cfg.modelDefaults.defaultFileName
        ) {
            val modelName = cfg.modelDefaults.defaultFileName
                ?.substringBeforeLast('.')
                ?.ifBlank { null }
                ?: "ondevice-slm"

            Model(
                name = modelName,
                taskPath = modelFile.absolutePath,
                config = modelConfig
            )
        }

        InitGate(
            key = slmModel,
            progressText = "Initializing Small Language Model…",
            subText = "Setting up accelerated runtime and buffers",
            onErrorMessage = { "Failed to initialize model: ${it.message}" },
            init = {
                withContext(Dispatchers.Default) {
                    LiteRtLM.initializeIfNeeded(
                        context = appContext,
                        model = slmModel,
                        supportImage = false,
                        supportAudio = false
                    )
                }
            }
        ) {
            val backStack = rememberNavBackStack(FlowHome)

            val repo: Repository = remember(appContext, slmModel, cfg) {
                LiteRtRepository(slmModel, cfg)
            }

            val vmSurvey: SurveyViewModel = viewModel(
                viewModelStoreOwner = sessionVmOwner,
                key = "SurveyViewModel_$sessionKey",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SurveyViewModel(nav = backStack, config = cfg) as T
                    }
                }
            )

            val vmAI: AiViewModel = viewModel(
                viewModelStoreOwner = sessionVmOwner,
                key = "AiViewModel_${sessionKey}_${slmModel.name}",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return AiViewModel(repo) as T
                    }
                }
            )

            val resetToSelector: () -> Unit = {
                Log.d("MainActivity", "resetToSelector invoked. session=$sessionKey")
                chosen = null
                config = null
                configError = null
                configLoading = false
            }

            val voiceEnabled = remember(cfg) { cfg.whisper.enabled ?: true }

            if (voiceEnabled) {
                AudioPermissionGate {
                    SurveyNavHost(
                        vmSurvey = vmSurvey,
                        vmAI = vmAI,
                        backStack = backStack,
                        onResetToSelector = resetToSelector,
                        whisperMeta = cfg.whisper,
                        sessionId = sessionKey,
                        sessionVmOwner = sessionVmOwner
                    )
                }
            } else {
                SurveyNavHost(
                    vmSurvey = vmSurvey,
                    vmAI = vmAI,
                    backStack = backStack,
                    onResetToSelector = resetToSelector,
                    whisperMeta = cfg.whisper,
                    sessionId = sessionKey,
                    sessionVmOwner = sessionVmOwner
                )
            }
        }
    }
}

/* ───────────────────────────── Survey Nav Host ───────────────────────────── */

@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>,
    onResetToSelector: () -> Unit = {},
    whisperMeta: SurveyConfig.WhisperMeta = SurveyConfig.WhisperMeta(),
    sessionId: String = "session",
    sessionVmOwner: ViewModelStoreOwner? = null
) {
    UploadProgressOverlay()

    val appContext = LocalContext.current.applicationContext
    val owner = sessionVmOwner ?: LocalViewModelStoreOwner.current
    ?: error("Missing ViewModelStoreOwner")

    val latestNode by vmSurvey.currentNode.collectAsState()
    val latestNodeId = latestNode.id

    val voiceEnabled = remember(whisperMeta.enabled) { whisperMeta.enabled ?: true }

    // IMPORTANT:
    // Avoid Kotlin inferring an intersection type (ViewModel & SpeechController) for reified viewModel().
    // Always request the concrete ViewModel type explicitly, then upcast to SpeechController.
    val speechController: SpeechController = if (voiceEnabled) {
        val assetPath = remember(whisperMeta.assetModelPath) {
            whisperMeta.assetModelPath?.ifBlank { null } ?: DEFAULT_WHISPER_ASSET_MODEL
        }
        val lang = remember(whisperMeta.language) {
            whisperMeta.language?.trim()?.lowercase(Locale.US)?.ifBlank { null } ?: DEFAULT_WHISPER_LANGUAGE
        }

        val speechVm: WhisperSpeechController = viewModel(
            viewModelStoreOwner = owner,
            key = "WhisperSpeechController_${vmSurvey.hashCode()}_${assetPath}_$lang",
            factory = WhisperSpeechController.provideFactory(
                appContext = appContext,
                assetModelPath = assetPath,
                languageCode = lang,
                onVoiceExported = onVoiceExported@{ voice ->
                    val resolvedQid =
                        voice.questionId?.takeIf { it.isNotBlank() } ?: latestNodeId

                    if (resolvedQid.isBlank()) {
                        Log.w(
                            "MainActivity",
                            "onVoiceExported: missing questionId and fallback failed. file=${voice.fileName}"
                        )
                        return@onVoiceExported
                    }

                    Log.d(
                        "MainActivity",
                        "onVoiceExported: q=$resolvedQid, file=${voice.fileName}, bytes=${voice.byteSize}, checksum=${voice.checksum}"
                    )

                    vmSurvey.onVoiceExported(
                        questionId = resolvedQid,
                        fileName = voice.fileName,
                        byteSize = voice.byteSize,
                        checksum = voice.checksum,
                        replace = false
                    )
                }
            )
        )

        speechVm
    } else {
        remember { NoOpSpeechController() }
    }

    LaunchedEffect(sessionId, latestNodeId) {
        speechController.updateContext(
            surveyId = sessionId,
            questionId = latestNodeId
        )
    }

    val canGoBack by vmSurvey.canGoBack.collectAsState()

    /**
     * IMPORTANT:
     * - Do NOT use rememberViewModelStoreNavEntryDecorator() unless you also install the
     *   corresponding SavedState decorator for Navigation3.
     * - Otherwise, you may crash with:
     *   "ViewModelStoreNavEntryDecorator requires adding the SavedStateNavEntryDecorator..."
     *
     * This app already owns a session-wide ViewModelStore, so NavEntry ViewModel storage
     * is not required here.
     */
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<FlowHome> {
                HomeScreen(
                    onStart = {
                        Log.d("MainActivity", "Home -> Start survey")
                        vmSurvey.resetToStart()
                        vmAI.resetAll(keepError = false)
                        vmSurvey.advanceToNext()
                    }
                )
            }

            entry<FlowText> {
                val node by vmSurvey.currentNode.collectAsState()
                AiScreen(
                    nodeId = node.id,
                    vmSurvey = vmSurvey,
                    vmAI = vmAI,
                    onNext = { vmSurvey.advanceToNext() },
                    onBack = { vmSurvey.backToPrevious() },
                    speechController = speechController
                )
            }

            entry<FlowAI> {
                val node by vmSurvey.currentNode.collectAsState()
                AiScreen(
                    nodeId = node.id,
                    vmSurvey = vmSurvey,
                    vmAI = vmAI,
                    onNext = { vmSurvey.advanceToNext() },
                    onBack = { vmSurvey.backToPrevious() },
                    speechController = speechController
                )
            }

            entry<FlowReview> {
                ReviewScreen(
                    vm = vmSurvey,
                    onNext = { vmSurvey.advanceToNext() },
                    onBack = { vmSurvey.backToPrevious() }
                )
            }

            entry<FlowDone> {
                val gh = if (BuildConfig.GH_TOKEN.isNotEmpty()) {
                    GitHubUploader.GitHubConfig(
                        owner = BuildConfig.GH_OWNER,
                        repo = BuildConfig.GH_REPO,
                        branch = BuildConfig.GH_BRANCH,
                        pathPrefix = BuildConfig.GH_PATH_PREFIX,
                        token = BuildConfig.GH_TOKEN
                    )
                } else {
                    null
                }

                DoneScreen(
                    vm = vmSurvey,
                    onRestart = {
                        Log.d("MainActivity", "Done -> Restart requested (return to selector)")
                        vmAI.resetStates()
                        vmSurvey.resetToStart()
                        onResetToSelector()
                    },
                    gitHubConfig = gh
                )
            }
        }
    )

    BackHandler(enabled = canGoBack) {
        Log.d("MainActivity", "BackHandler -> backToPrevious")
        vmAI.resetStates()
        vmSurvey.backToPrevious()
    }
}

/* ───────────────────────────── Home Screen ───────────────────────────── */

@Composable
private fun HomeScreen(
    onStart: () -> Unit
) {
    val backplate = animatedBackplate()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backplate)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            modifier = Modifier
                .wrapContentWidth()
                .neonEdgeThin()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Survey ready",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tap Start to begin answering the configured survey.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onStart) {
                    Text("Start survey")
                }
            }
        }
    }
}

/* ───────────────────────────── SLM Config Helpers ────────────────────────── */

private fun buildModelConfig(slm: SurveyConfig.SlmMeta): MutableMap<ConfigKey, Any> {
    // Force the value type to Any to avoid Kotlin inferring an intersection type
    // like Comparable<*> & Serializable when mixing String/Int/Double literals.
    val out: MutableMap<ConfigKey, Any> = mutableMapOf(
        ConfigKey.ACCELERATOR to ((slm.accelerator ?: "GPU").uppercase(Locale.US)),
        ConfigKey.MAX_TOKENS to ((slm.maxTokens ?: 512).toInt()),
        ConfigKey.TOP_K to ((slm.topK ?: 1).toInt()),
        ConfigKey.TOP_P to ((slm.topP ?: 0.0).toDouble()),
        ConfigKey.TEMPERATURE to ((slm.temperature ?: 0.0).toDouble())
        // If you support repetition penalty, add it here with a corresponding ConfigKey:
        // ConfigKey.REPETITION_PENALTY to ((slm.repetitionPenalty ?: 1.0).toDouble())
    )

    normalizeNumberTypes(out)
    clampRanges(out)
    return out
}

private fun normalizeNumberTypes(m: MutableMap<ConfigKey, Any>) {
    // Keep everything in stable numeric types for downstream APIs.
    m[ConfigKey.MAX_TOKENS] = (m[ConfigKey.MAX_TOKENS] as? Number)?.toInt() ?: 512
    m[ConfigKey.TOP_K] = (m[ConfigKey.TOP_K] as? Number)?.toInt() ?: 1
    m[ConfigKey.TOP_P] = (m[ConfigKey.TOP_P] as? Number)?.toDouble() ?: 0.0
    m[ConfigKey.TEMPERATURE] = (m[ConfigKey.TEMPERATURE] as? Number)?.toDouble() ?: 0.0
}

private fun clampRanges(m: MutableMap<ConfigKey, Any>) {
    val maxTokens = (m[ConfigKey.MAX_TOKENS] as Number).toInt().coerceAtLeast(1)
    val topK = (m[ConfigKey.TOP_K] as Number).toInt().coerceAtLeast(1)
    val topP = (m[ConfigKey.TOP_P] as Number).toDouble().coerceIn(0.0, 1.0)
    val temp = (m[ConfigKey.TEMPERATURE] as Number).toDouble().coerceAtLeast(0.0)

    m[ConfigKey.MAX_TOKENS] = maxTokens
    m[ConfigKey.TOP_K] = topK
    m[ConfigKey.TOP_P] = topP
    m[ConfigKey.TEMPERATURE] = temp
}

/* ───────────────────────────── No-op Speech ───────────────────────────── */

private class NoOpSpeechController : SpeechController {

    private val _isRecording = MutableStateFlow(false)
    private val _isTranscribing = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    override fun updateContext(surveyId: String?, questionId: String?) {
        // No-op
    }

    override fun startRecording() {
        _error.value = "Voice input is disabled by configuration."
    }

    override fun stopRecording() {
        // No-op
    }

    override fun toggleRecording() {
        startRecording()
    }
}

/* ───────────────────────────── Config UI Helpers ───────────────────────────── */

private fun configOptionFromFileName(fileName: String): ConfigOptionUi {
    val stem = fileName.removeSuffix(".yaml").removeSuffix(".yml")
    val lower = stem.lowercase(Locale.US)

    val isDemo = "demo" in lower
    val isFull = "full" in lower
    val isFaw = ("faw" in lower) || ("fall_armyworm" in lower) || ("armyworm" in lower)

    val configNumber = Regex("""(?:^|_)survey_config(\d+)(?:_|$)""")
        .find(lower)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    val baseLabel = when {
        isDemo -> "Demo config"
        isFull -> "Full config"
        configNumber != null -> "Config $configNumber"
        else -> prettyNameFromFileStem(stem)
    }

    val label = if (isFaw && !baseLabel.contains("FAW")) {
        "$baseLabel — FAW"
    } else {
        baseLabel
    }

    val pretty = prettyNameFromFileStem(stem)
    val description = buildString {
        if (isFaw) append("FAW survey configuration. ") else append("Survey configuration. ")
        if (pretty.isNotBlank()) append("“$pretty”. ")
        append("Loaded from $fileName.")
    }

    return ConfigOptionUi(
        id = fileName,
        label = label,
        description = description
    )
}

private fun prettyNameFromFileStem(stem: String): String {
    val tokens = stem
        .replace('-', '_')
        .split('_')
        .filter { it.isNotBlank() }
        .filterNot { t ->
            val x = t.lowercase(Locale.US)
            x == "survey" ||
                    x == "config" ||
                    x == "configs" ||
                    x == "followup" ||
                    x == "followups" ||
                    x == "fu"
        }

    if (tokens.isEmpty()) return stem

    return tokens.joinToString(" ") { token ->
        token.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }
}

/**
 * List YAML files from assets.
 *
 * Supports a shallow recursion so that configs can live in:
 * - assets/
 * - assets/configs/
 * - assets/surveys/
 *
 * Returned paths are asset-relative (e.g., "survey_config1.yaml" or "configs/survey_config2.yaml").
 */
private fun listAssetYamlConfigs(assetManager: android.content.res.AssetManager): List<String> {
    val roots = listOf("", "configs", "surveys")
    val out = mutableListOf<String>()

    fun walk(dir: String, depth: Int) {
        if (depth > 2) return

        val items = runCatching { assetManager.list(dir) }.getOrNull() ?: return
        for (name in items) {
            val path = if (dir.isBlank()) name else "$dir/$name"

            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                out += path
                continue
            }

            // AssetManager.list() does not clearly distinguish files/dirs; try to descend.
            walk(path, depth + 1)
        }
    }

    roots.forEach { walk(it, 0) }

    return out.distinct()
}

/* ───────────────────────────── Crash Capture + Startup Upload ───────────────────────────── */

/**
 * Crash capture strategy:
 * - On uncaught exception, dump an exception header + logcat snapshot to a gzip file under
 *   app-private storage: files/diagnostics/crash/.
 * - On the next app start, scan that directory and enqueue uploads via WorkManager if GitHub
 *   config is present (BuildConfig.GH_*).
 *
 * Notes:
 * - This intentionally does NOT attempt network I/O during the crash.
 * - This keeps the crash handler fast and reduces the chance of ANR during fatal unwind.
 */
private object CrashCapture {

    private const val TAG = "CrashCapture"
    private const val CRASH_DIR_REL = "diagnostics/crash"

    private const val MAX_LOGCAT_BYTES = 850_000
    private const val LOGCAT_MAX_MS = 700L

    private const val LOGCAT_TAIL_LINES_PID = "2000"
    private const val LOGCAT_TAIL_LINES_FALLBACK = "3000"

    private const val MAX_FILES_TO_KEEP = 80
    private const val MAX_FILES_TO_ENQUEUE = 20

    private val installed = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val prior = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Prevent re-entrancy storms (rare, but can happen if handler itself crashes).
            if (!capturing.compareAndSet(false, true)) {
                try {
                    prior?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                    hardKill()
                }
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                val file = runCatching { captureCrashToFile(appContext, thread, throwable) }
                    .onFailure { e -> Log.e(TAG, "Crash capture failed: ${e.message}", e) }
                    .getOrNull()

                if (file != null) {
                    Log.e(TAG, "Crash captured: ${file.absolutePath}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Crash capture unexpected failure: ${t.message}", t)
            } finally {
                // Always delegate to preserve normal crash behavior / system reporting.
                try {
                    if (prior != null) {
                        prior.uncaughtException(thread, throwable)
                    } else {
                        hardKill()
                    }
                } catch (_: Throwable) {
                    hardKill()
                }
            }
        }

        Log.d(TAG, "Installed default uncaught exception handler.")
    }

    /**
     * On app startup, enqueue any pending crash files for upload (if GH is configured).
     */
    fun enqueuePendingCrashUploadsIfPossible(context: Context) {
        val cfg = buildCrashGitHubConfigOrNull() ?: run {
            Log.d(TAG, "GitHub config missing; crash uploads will remain local.")
            return
        }

        val dir = crashDir(context).apply { mkdirs() }

        // Purge old files defensively.
        purgeOldCrashFiles(dir)

        val files = dir.listFiles { f ->
            f.isFile && f.length() > 0L && !f.name.startsWith(".")
        }?.toList().orEmpty()

        if (files.isEmpty()) return

        Log.d(TAG, "Found ${files.size} pending crash file(s). Enqueuing uploads…")

        files
            .sortedByDescending { it.lastModified() }
            .take(MAX_FILES_TO_ENQUEUE)
            .forEach { file ->
                // Worker should delete local file upon successful upload.
                GitHubUploadWorker.enqueueExistingPayload(
                    context = context.applicationContext,
                    cfg = cfg,
                    file = file
                )
            }
    }

    /**
     * Capture crash data into a gzip file and return it.
     */
    private fun captureCrashToFile(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ): File {
        val dir = crashDir(context).apply { mkdirs() }

        // Keep storage from exploding.
        purgeOldCrashFiles(dir)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pid = Process.myPid()
        val name = "crash_${stamp}_pid${pid}.log.gz"
        val outFile = File(dir, name)

        FileOutputStream(outFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                val header = buildString {
                    appendLine("=== Crash Report ===")
                    appendLine("time_local=$stamp")
                    appendLine("pid=$pid")
                    appendLine("thread=${thread.name}")
                    appendLine("sdk=${Build.VERSION.SDK_INT}")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("appId=${BuildConfig.APPLICATION_ID}")
                    appendLine("versionName=${BuildConfig.VERSION_NAME}")
                    appendLine("versionCode=${BuildConfig.VERSION_CODE}")
                    appendLine()
                    appendLine("=== Exception ===")
                    appendLine(Log.getStackTraceString(throwable))
                    appendLine()
                    appendLine("=== Logcat (best-effort) ===")
                }.toByteArray(Charsets.UTF_8)

                gz.write(header)

                val logBytes = collectLogcatBytes(
                    pid = pid,
                    maxBytes = MAX_LOGCAT_BYTES,
                    maxMs = LOGCAT_MAX_MS
                )

                gz.write(logBytes)
                gz.flush()
            }
        }

        return outFile
    }

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_DIR_REL)

    private fun purgeOldCrashFiles(dir: File) {
        val all = dir.listFiles { f -> f.isFile && f.length() > 0L }?.toList().orEmpty()
        if (all.size <= MAX_FILES_TO_KEEP) return

        val sorted = all.sortedBy { it.lastModified() }
        val toDelete = sorted.take(all.size - MAX_FILES_TO_KEEP)
        toDelete.forEach { f -> runCatching { f.delete() } }
    }

    /**
     * Collect logcat output as bytes (best-effort).
     *
     * This tries to restrict to the current PID when supported.
     * If it fails, it falls back to a generic logcat dump.
     */
    private fun collectLogcatBytes(pid: Int, maxBytes: Int, maxMs: Long): ByteArray {
        val primary = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-b", "main",
            "-b", "system",
            "-b", "crash",
            "--pid=$pid",
            "-t", LOGCAT_TAIL_LINES_PID
        )

        val fallback = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-b", "main",
            "-b", "system",
            "-b", "crash",
            "-t", LOGCAT_TAIL_LINES_FALLBACK
        )

        return runCatching { execAndReadCapped(primary, maxBytes, maxMs) }
            .recoverCatching { execAndReadCapped(fallback, maxBytes, maxMs) }
            .getOrElse { e ->
                ("(logcat capture failed: ${e.message})\n").toByteArray(Charsets.UTF_8)
            }
    }

    /**
     * Execute a command and read stdout up to [maxBytes] and [maxMs].
     *
     * redirectErrorStream(true) avoids deadlock if stderr fills up.
     */
    private fun execAndReadCapped(cmd: List<String>, maxBytes: Int, maxMs: Long): ByteArray {
        val start = SystemClock.elapsedRealtime()

        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)

        val proc = pb.start()

        return try {
            proc.inputStream.use { input ->
                val out = ByteArrayOutputStream(minOf(maxBytes, 128 * 1024))
                val buf = ByteArray(16 * 1024)

                while (out.size() < maxBytes) {
                    if (SystemClock.elapsedRealtime() - start > maxMs) break

                    val remaining = maxBytes - out.size()
                    val n = input.read(buf, 0, minOf(buf.size, remaining))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }

                out.toByteArray()
            }
        } finally {
            // Best-effort cleanup. Do not block inside crash handler.
            runCatching { proc.destroy() }
        }
    }

    /**
     * Build a GitHub config that stores crash logs under:
     *   <GH_PATH_PREFIX>/diagnostics/crash/
     */
    private fun buildCrashGitHubConfigOrNull(): GitHubUploader.GitHubConfig? {
        if (BuildConfig.GH_TOKEN.isBlank()) return null
        if (BuildConfig.GH_OWNER.isBlank() || BuildConfig.GH_REPO.isBlank()) return null

        val basePrefix = BuildConfig.GH_PATH_PREFIX.trim('/')
        val crashPrefix = listOf(basePrefix, "diagnostics/crash")
            .filter { it.isNotBlank() }
            .joinToString("/")

        return GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            branch = BuildConfig.GH_BRANCH.ifBlank { "main" },
            pathPrefix = crashPrefix,
            token = BuildConfig.GH_TOKEN
        )
    }

    private fun hardKill() {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
