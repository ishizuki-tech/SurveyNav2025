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

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.negi.survey.screens.DoneScreen
import com.negi.survey.screens.IntroScreen
import com.negi.survey.screens.ReviewScreen
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
import com.negi.survey.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Root activity of the SurveyNav app.
 *
 * English comment:
 * - Enables edge-to-edge system bars with dark (black) backgrounds.
 * - Delegates all UI to [AppNav] which hosts the survey flow, model
 *   download gate, and SLM initialization gate.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // English comment:
        // Prefer modern edge-to-edge API. Fall back gracefully on older devices.
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
 * English comment:
 * Simple vertical gradient used as a dark backplate behind loading cards.
 */
@Composable
private fun animatedBackplate(): Brush =
    Brush.verticalGradient(
        0f to Color(0xFF202020),
        1f to Color(0xFF040404)
    )

/**
 * English comment:
 * Ultra-thin neon-like edge glow for cards.
 *
 * The effect is built with a radial gradient centered on the composable,
 * fading from a slightly tinted primary color into full transparency.
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
 * Generic initialization gate composable.
 *
 * English comment:
 * - Executes [init] exactly once for the given [key].
 * - While running, shows a blocking loading card with subtle animation.
 * - On failure, shows an error card with a "Retry" action.
 * - Once [init] succeeds, renders [content] and never shows the gate again
 *   for the same [key].
 *
 * Typical usage:
 *  - Wrapping SLM model initialization.
 *  - Wrapping heavy one-time setup logic that must complete before the
 *    main UI becomes interactive.
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

    // English comment:
    // Small helper that (re)starts the initialization coroutine.
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

    // English comment:
    // Run initialization once when the given [key] enters composition.
    LaunchedEffect(key) {
        kick()
    }

    val backplate = animatedBackplate()

    when {
        isLoading -> {
            // Loading state: full-screen dark background + centered card.
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

                        // English comment:
                        // Soft alpha pulsing for the headline to avoid a static feel.
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
            // Error state: same layout, but with error styling and Retry.
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
            // Success state: hand over control to the main content.
            content()
        }
    }
}

/* ───────────────────────────── App Nav Root ───────────────────────────── */

/**
 * Top-level navigation host for the SurveyNav app.
 *
 * English comment:
 * - Loads the YAML survey configuration from assets.
 * - Builds [AppViewModel] to manage model download and SLM defaults.
 * - Shows [DownloadGate] while the SLM model file is being downloaded.
 * - Once downloaded, initializes SLM via [InitGate] and then hosts the
 *   survey navigation flow with [SurveyNavHost].
 */
@Composable
fun AppNav() {
    val appContext = LocalContext.current.applicationContext

    // English comment:
    // 1) Load survey YAML once (graph + SLM metadata + model defaults).
    val config: SurveyConfig = remember(appContext) {
        SurveyConfigLoader.fromAssets(appContext, "survey_config1.yaml")
    }

    // English comment:
    // 2) Build AppViewModel with overrides injected from YAML model_defaults.
    val appVm: AppViewModel = viewModel(
        factory = AppViewModel.factoryFromOverrides(
            modelUrlOverride = config.modelDefaults.defaultModelUrl,
            fileNameOverride = config.modelDefaults.defaultFileName,
            timeoutMsOverride = config.modelDefaults.timeoutMs,
            uiThrottleMsOverride = config.modelDefaults.uiThrottleMs,
            uiMinDeltaBytesOverride = config.modelDefaults.uiMinDeltaBytes
        )
    )

    val state by appVm.state.collectAsState()

    // English comment:
    // Start model download once when entering Idle state.
    LaunchedEffect(state) {
        if (state is DlState.Idle) {
            appVm.ensureModelDownloaded(appContext)
        }
    }

    DownloadGate(
        state = state,
        onRetry = { appVm.ensureModelDownloaded(appContext) }
    ) { modelFile ->

        // English comment:
        // 3) Build SLM model configuration map from YAML metadata.
        val modelConfig = remember(config) { buildModelConfig(config.slm) }

        // English comment:
        // 4) Create a concrete SLM model descriptor.
        val slmModel = remember(modelFile.absolutePath, modelConfig) {
            val modelName = config.modelDefaults.defaultFileName
                ?.substringBeforeLast('.')
                ?.ifBlank { null }
                ?: "ondevice-slm"

            Model(
                name = modelName,
                taskPath = modelFile.absolutePath,
                config = modelConfig
            )
        }

        // English comment:
        // 5) Initialize SLM instance under InitGate.
        InitGate(
            key = slmModel,
            progressText = "Initializing Small Language Model…",
            subText = "Setting up accelerated runtime and buffers",
            onErrorMessage = { "Failed to initialize model: ${it.message}" },
            init = {
                withContext(Dispatchers.Default) {
                    suspendCancellableCoroutine { cont ->
                        SLM.initialize(appContext, slmModel) { err ->
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
            // English comment:
            // Ensure SLM resources are released when the composition is disposed.
            DisposableEffect(slmModel) {
                onDispose {
                    runCatching {
                        SLM.cleanUp(slmModel) { }
                    }
                }
            }

            val backStack = rememberNavBackStack(FlowHome)

            // English comment:
            // Repository used by AI ViewModel to talk to SLM.
            val repo: Repository = remember(appContext, slmModel, config) {
                SlmDirectRepository(slmModel, config)
            }

            // English comment:
            // Survey ViewModel: holds graph position, answers, and follow-ups.
            val vmSurvey: SurveyViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        SurveyViewModel(nav = backStack, config = config) as T
                }
            )

            // English comment:
            // AI ViewModel: wraps SLM calls and exposes responses to the UI.
            val vmAI: AiViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AiViewModel(repo) as T
                }
            )

            SurveyNavHost(vmSurvey, vmAI, backStack)
        }
    }
}

/* ───────────────────────────── Survey Nav Host ───────────────────────────── */

/**
 * Host composable for the survey navigation flow.
 *
 * English comment:
 * - Places [UploadProgressOverlay] at the root so upload HUD is always visible.
 * - Wires each navigation flow key (FlowHome / FlowText / FlowAI / FlowReview /
 *   FlowDone) to its corresponding screen.
 * - Manages back navigation via [BackHandler], delegating to [SurveyViewModel]
 *   and [AiViewModel] where appropriate.
 *
 * Restart behavior:
 * - When [DoneScreen] calls `onRestart`, this function:
 *   1) Resets AI and survey state.
 *   2) Shrinks [backStack] to a single FlowHome entry.
 *   3) Causes [NavDisplay] to render IntroScreen again.
 */
@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>
) {
    // English comment:
    // Global background upload HUD — always attached at the window root.
    UploadProgressOverlay()

    val canGoBack by vmSurvey.canGoBack.collectAsState()

    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSceneSetupNavEntryDecorator(),
            rememberSavedStateNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            // Home (intro) screen.
            entry<FlowHome> {
                IntroScreen(
                    onStart = {
                        // English comment:
                        // Reset internal state and advance to the first node.
                        vmSurvey.resetToStart()
                        vmSurvey.advanceToNext()
                    }
                )
            }

            // Plain text question screen.
            entry<FlowText> {
                val node by vmSurvey.currentNode.collectAsState()
                AiScreen(
                    nodeId = node.id,
                    vmSurvey = vmSurvey,
                    vmAI = vmAI,
                    onNext = { vmSurvey.advanceToNext() },
                    onBack = { vmSurvey.backToPrevious() }
                )
            }

            // AI-driven question/answer screen (same UI, different node type).
            entry<FlowAI> {
                val node by vmSurvey.currentNode.collectAsState()
                AiScreen(
                    nodeId = node.id,
                    vmSurvey = vmSurvey,
                    vmAI = vmAI,
                    onNext = { vmSurvey.advanceToNext() },
                    onBack = { vmSurvey.backToPrevious() }
                )
            }

            // Review screen before finalization.
            entry<FlowReview> {
                ReviewScreen(
                    vm = vmSurvey,
                    onNext = { vmSurvey.advanceToNext() },
                    onBack = { vmSurvey.backToPrevious() }
                )
            }

            // Final summary + export / upload screen.
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
                        // English comment:
                        // 1) Reset AI and survey internal state for a fresh run.
                        vmAI.resetStates()
                        vmSurvey.resetToStart()

                        // English comment:
                        // 2) Reset navigation backstack so only FlowHome remains.
                        //    This assumes FlowHome is the start destination and
                        //    is present as the first entry.
                        while (backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                        // After this, NavDisplay will render FlowHome again.
                    },
                    gitHubConfig = gh
                )
            }
        }
    )

    // English comment:
    // Only intercept system back when there is something to pop inside
    // the survey flow. On the root (FlowHome only), let the system handle
    // back (e.g., finish the activity).
    BackHandler(enabled = canGoBack) {
        vmAI.resetStates()
        vmSurvey.backToPrevious()
    }
}

/* ───────────────────────────── SLM Config Helpers ────────────────────────── */

/**
 * Build a normalized model configuration map for the SLM engine.
 *
 * English comment:
 * - Reads SLM metadata from [SurveyConfig.SlmMeta].
 * - Fills in default values if fields are missing.
 * - Normalizes numeric types and clamps sensitive ranges.
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
 * Normalize JVM number types for SLM configuration values.
 *
 * English comment:
 * - MAX_TOKENS, TOP_K → Int
 * - TOP_P, TEMPERATURE → Double
 * - Falls back to safe defaults when parsing fails.
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
 * Clamp sampling parameters to safe ranges before passing them to the engine.
 *
 * English comment:
 * - TOP_P is clamped to [0.0, 1.0].
 * - TEMPERATURE is clamped to [0.0, +∞).
 */
private fun clampRanges(m: MutableMap<ConfigKey, Any>) {
    val topP = (m[ConfigKey.TOP_P] as Number)
        .toDouble()
        .coerceIn(0.0, 1.0)
    val temp = (m[ConfigKey.TEMPERATURE] as Number)
        .toDouble()
        .coerceAtLeast(0.0)

    m[ConfigKey.TOP_P] = topP
    m[ConfigKey.TEMPERATURE] = temp
}
