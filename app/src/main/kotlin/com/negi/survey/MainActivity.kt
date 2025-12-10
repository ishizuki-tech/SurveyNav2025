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

@file:Suppress("UnusedParameter")

package com.negi.survey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSavedStateNavEntryDecorator
import androidx.navigation3.scene.rememberSceneSetupNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.net.GitHubUploader
import com.negi.survey.screens.AiScreen
import com.negi.survey.screens.ConfigOptionUi
import com.negi.survey.screens.DoneScreen
import com.negi.survey.screens.IntroScreen
import com.negi.survey.screens.ReviewScreen
import com.negi.survey.screens.SpeechController
import com.negi.survey.screens.UploadProgressOverlay
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.Model
import com.negi.survey.slm.Repository
import com.negi.survey.slm.SLM
import com.negi.survey.slm.SlmDirectRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Root activity of the SurveyNav app.
 *
 * This activity is intentionally thin:
 * - Applies edge-to-edge system bar styling.
 * - Delegates all runtime state and UI composition to [AppNav].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /**
         * Prefer the modern edge-to-edge API.
         * Fall back to legacy insets handling on older or vendor-modified devices.
         */
        try {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark("#000000".toColorInt()),
                navigationBarStyle = SystemBarStyle.dark("#000000".toColorInt())
            )
        } catch (_: Throwable) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            window.statusBarColor = 0xFF000000.toInt()
            window.navigationBarColor = 0xFF000000.toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { window.isNavigationBarContrastEnforced = false }
            }
        }

        setContent {
            MaterialTheme {
                AppNav()
            }
        }
    }
}

/* ───────────────────────────── Visual Utilities ───────────────────────────── */

/**
 * A simple vertical gradient used as a dark backplate behind
 * loading/error cards.
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
 * This uses a radial gradient centered on the composable surface
 * to create a subtle halo that remains readable on a monochrome palette.
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

/**
 * A generic initialization gate composable.
 *
 * Contract:
 * - Executes [init] once per [key].
 * - While running, blocks the subtree and shows a loading card.
 * - On failure, shows an error card with a Retry action.
 * - On success, renders [content].
 *
 * This is safe to use for expensive or stateful components such as
 * on-device model initialization.
 */
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
    val scope = rememberCoroutineScope()

    /**
     * Starts or restarts the initialization coroutine.
     */
    fun kick() {
        isLoading = true
        error = null
        scope.launch {
            try {
                init()
                isLoading = false
            } catch (t: Throwable) {
                error = t
                isLoading = false
            }
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

/**
 * A simple permission gate for [Manifest.permission.RECORD_AUDIO].
 *
 * Behavior:
 * - If granted, renders [content].
 * - If denied, shows an explanation card with a request button.
 * - On denial, offers a snackbar action to open app settings.
 *
 * The permission state is re-checked on ON_RESUME to correctly
 * reflect changes made in the system settings screen.
 */
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

/**
 * Top-level navigation host for the SurveyNav app.
 *
 * Pipeline:
 *  1) Intro/config selection.
 *  2) Asset config load and structural validation.
 *  3) Model download gate.
 *  4) SLM initialization gate.
 *  5) Survey navigation host.
 *
 * Important:
 * This function introduces a "selection epoch" to generate a unique
 * session key per user selection, ensuring ViewModels are not reused
 * when the same config is re-selected after a restart.
 */
@Composable
fun AppNav() {
    val appContext = LocalContext.current.applicationContext

    /**
     * Configuration options surfaced on the intro screen.
     *
     * Automatically discovers survey_config*.yaml under assets.
     */
    val options = remember(appContext) {
        val assetManager = appContext.assets
        val files = assetManager.list("")?.toList().orEmpty()

        val yamlFiles = files
            .filter { it.startsWith("survey_config") && it.endsWith(".yaml") }
            .sorted()

        val mapped = yamlFiles.map { fileName ->
            val base = fileName.removeSuffix(".yaml")

            val label = when (fileName) {
                "survey_config1.yaml" -> "Demo config"
                "survey_config2.yaml" -> "Full (1 follow-up)"
                "survey_config3.yaml" -> "Full (3 follow-ups)"
                else -> base
                    .replace('_', ' ')
                    .replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                    }
            }

            val description = when (fileName) {
                "survey_config1.yaml" ->
                    "Short FAW demo with a few questions."
                "survey_config2.yaml" ->
                    "Long-form survey with a single follow-up per item."
                "survey_config3.yaml" ->
                    "Long-form survey with three follow-ups per item."
                else ->
                    "Survey configuration loaded from $fileName."
            }

            ConfigOptionUi(
                id = fileName,
                label = label,
                description = description
            )
        }

        mapped.ifEmpty {
            listOf(
                ConfigOptionUi(
                    id = "survey_config1.yaml",
                    label = "Default config",
                    description = "Fallback survey configuration."
                )
            )
        }
    }

    var chosen by remember { mutableStateOf<ConfigOptionUi?>(null) }
    var config by remember { mutableStateOf<SurveyConfig?>(null) }
    var configLoading by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }

    /**
     * A monotonically increasing epoch that changes every time
     * the user starts a new config session.
     *
     * This prevents Activity-scoped ViewModelStore from reusing
     * previous instances when the same config file is selected again.
     */
    var selectionEpoch by remember { mutableStateOf(0) }

    /**
     * Stage 1: No config chosen yet.
     */
    if (chosen == null) {
        IntroScreen(
            options = options,
            defaultOptionId = options.firstOrNull()?.id,
            onStart = { option ->
                /**
                 * Start a brand-new session:
                 * - Increment epoch
                 * - Clear stale config UI state
                 * - Bind the new selection
                 */
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

    /**
     * A unique session key per selection event.
     *
     * Example:
     *  - survey_config2.yaml@1
     *  - survey_config2.yaml@2  (re-selected after restart)
     */
    val sessionKey = remember(chosen!!.id, selectionEpoch) {
        "${chosen!!.id}@$selectionEpoch"
    }

    /**
     * Stage 2: Load the chosen configuration once per session key.
     *
     * Using [sessionKey] (not only file id) ensures that selecting the
     * same file again after a restart still triggers a fresh load path.
     */
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

    /**
     * Stage 3+: Config is successfully loaded.
     */
    val cfg = config!!

    /**
     * App-level download/UI ViewModel.
     *
     * Keyed by [sessionKey] to guarantee a fresh instance
     * even when the same config file is re-selected.
     */
    val appVm: AppViewModel = viewModel(
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

    /**
     * Start model download once when entering Idle state.
     */
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
                    suspendCancellableCoroutine { cont ->
                        SLM.ensureInitialized(appContext, slmModel) { err ->
                            if (err.isEmpty()) {
                                cont.resume(Unit)
                            } else {
                                cont.resumeWithException(IllegalStateException(err))
                            }
                        }
                    }
                }
            }
        ) {
            /**
             * IMPORTANT:
             * Do not call SLM.release() in this composable scope.
             *
             * Releasing on dispose can cause repeated init loops during
             * config reloads or transient recompositions.
             * The runtime should own the lifecycle of the model instance.
             */

            val backStack = rememberNavBackStack(FlowHome)

            val repo: Repository = remember(appContext, slmModel, cfg) {
                SlmDirectRepository(slmModel, cfg)
            }

            /**
             * Survey ViewModel keyed by session.
             *
             * This ensures that "Restart -> reselect same config"
             * cannot resurrect stale navigation/answer state.
             */
            val vmSurvey: SurveyViewModel = viewModel(
                key = "SurveyViewModel_$sessionKey",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SurveyViewModel(nav = backStack, config = cfg) as T
                    }
                }
            )

            /**
             * AI ViewModel keyed by session and model identity.
             *
             * This prevents repository/model mismatches across sessions.
             */
            val vmAI: AiViewModel = viewModel(
                key = "AiViewModel_${sessionKey}_${slmModel.name}",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return AiViewModel(repo) as T
                    }
                }
            )

            /**
             * Hard reset back to the config selector.
             *
             * The next selection will increment [selectionEpoch]
             * and produce a new [sessionKey].
             */
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
                        whisperMeta = cfg.whisper
                    )
                }
            } else {
                SurveyNavHost(
                    vmSurvey = vmSurvey,
                    vmAI = vmAI,
                    backStack = backStack,
                    onResetToSelector = resetToSelector,
                    whisperMeta = cfg.whisper
                )
            }
        }
    }
}

/* ───────────────────────────── Survey Nav Host ───────────────────────────── */

/**
 * Host composable for the survey navigation flow.
 *
 * Responsibilities:
 * - Places [UploadProgressOverlay] at the root so upload HUD is always visible.
 * - Wires each navigation flow key to its corresponding screen.
 * - Manages back navigation via [BackHandler].
 *
 * Restart behavior:
 * - When [DoneScreen] calls `onRestart`, this function:
 *   1) Resets AI and survey state.
 *   2) Requests a full return to the config selector via [onResetToSelector].
 */
@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>,
    onResetToSelector: () -> Unit = {},
    whisperMeta: SurveyConfig.WhisperMeta = SurveyConfig.WhisperMeta()
) {
    UploadProgressOverlay()

    val appContext = LocalContext.current.applicationContext

    val latestNode by vmSurvey.currentNode.collectAsState()
    val latestNodeId = latestNode.id

    val voiceEnabled = remember(whisperMeta.enabled) { whisperMeta.enabled ?: true }

    /**
     * Speech controller is created only when voice is enabled.
     *
     * The key includes SurveyViewModel identity plus asset/language
     * to avoid accidental reuse across stateful Whisper sessions.
     */
    val speechController: SpeechController = if (voiceEnabled) {
        val assetPath = remember(whisperMeta.assetModelPath) {
            whisperMeta.assetModelPath?.ifBlank { null } ?: DEFAULT_WHISPER_ASSET_MODEL
        }
        val lang = remember(whisperMeta.language) {
            whisperMeta.language?.trim()?.lowercase()?.ifBlank { null } ?: DEFAULT_WHISPER_LANGUAGE
        }

        viewModel(
            key = "WhisperSpeechController_${vmSurvey.hashCode()}_${assetPath}_$lang",
            factory = WhisperSpeechController.provideFactory(
                appContext = appContext,
                assetModelPath = assetPath,
                languageCode = lang,
                onVoiceExported = onVoiceExported@{ voice ->
                    /**
                     * Resolve an effective question id:
                     * - Use the explicit questionId from the voice context if present.
                     * - Otherwise fall back to the latest node id.
                     */
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
    } else {
        remember { NoOpSpeechController() }
    }

    val canGoBack by vmSurvey.canGoBack.collectAsState()

    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSceneSetupNavEntryDecorator(),
            rememberSavedStateNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
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

/**
 * A simple home screen shown after model initialization.
 *
 * This keeps a consistent design language with the init/download gates.
 */
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

/**
 * Builds a normalized model configuration map for the SLM engine.
 *
 * Strategy:
 * - Reads SLM metadata from [SurveyConfig.SlmMeta].
 * - Applies conservative defaults when fields are missing.
 * - Normalizes numeric types for JNI/engine stability.
 * - Clamps sensitive ranges.
 */
private fun buildModelConfig(slm: SurveyConfig.SlmMeta): MutableMap<ConfigKey, Any> {
    val out = mutableMapOf<ConfigKey, Any>(
        ConfigKey.ACCELERATOR to (slm.accelerator ?: "GPU").uppercase(),
        ConfigKey.MAX_TOKENS to (slm.maxTokens ?: 512),
        ConfigKey.TOP_K to (slm.topK ?: 1),
        ConfigKey.TOP_P to (slm.topP ?: 0.0),
        ConfigKey.TEMPERATURE to (slm.temperature ?: 0.0)
    )
    normalizeNumberTypes(out)
    clampRanges(out)
    return out
}

/**
 * Normalizes JVM number types for SLM configuration values.
 *
 * Rationale:
 * Some inference backends are strict about primitive types.
 * This helper reduces variability introduced by YAML/JSON parsing.
 */
private fun normalizeNumberTypes(m: MutableMap<ConfigKey, Any>) {
    m[ConfigKey.MAX_TOKENS] =
        (m[ConfigKey.MAX_TOKENS] as? Number)?.toInt() ?: 256
    m[ConfigKey.TOP_K] =
        (m[ConfigKey.TOP_K] as? Number)?.toInt() ?: 1
    m[ConfigKey.TOP_P] =
        (m[ConfigKey.TOP_P] as? Number)?.toDouble() ?: 0.0
    m[ConfigKey.TEMPERATURE] =
        (m[ConfigKey.TEMPERATURE] as? Number)?.toDouble() ?: 0.0
}

/**
 * Clamps sampling parameters to safe ranges before passing them to the engine.
 *
 * Defensive rules:
 * - MAX_TOKENS: >= 1
 * - TOP_K: >= 1
 * - TOP_P: [0.0, 1.0]
 * - TEMPERATURE: >= 0.0
 */
private fun clampRanges(m: MutableMap<ConfigKey, Any>) {
    val maxTokens = (m[ConfigKey.MAX_TOKENS] as Number)
        .toInt()
        .coerceAtLeast(1)
    val topK = (m[ConfigKey.TOP_K] as Number)
        .toInt()
        .coerceAtLeast(1)
    val topP = (m[ConfigKey.TOP_P] as Number)
        .toDouble()
        .coerceIn(0.0, 1.0)
    val temp = (m[ConfigKey.TEMPERATURE] as Number)
        .toDouble()
        .coerceAtLeast(0.0)

    m[ConfigKey.MAX_TOKENS] = maxTokens
    m[ConfigKey.TOP_K] = topK
    m[ConfigKey.TOP_P] = topP
    m[ConfigKey.TEMPERATURE] = temp
}

/* ───────────────────────────── No-op Speech ───────────────────────────── */

/**
 * No-op speech controller for configs that disable Whisper.
 *
 * This preserves the UI contract without requiring conditional screen code.
 */
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
