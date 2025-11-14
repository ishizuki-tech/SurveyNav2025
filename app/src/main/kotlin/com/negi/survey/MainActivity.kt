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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App entry point — edge-to-edge & black system bars with light icons.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // English comment:
        // Prefer new API (light icons on black bars). Fall back gracefully for older devices.
        try {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark("#000000".toColorInt()),
                navigationBarStyle = SystemBarStyle.dark("#000000".toColorInt())
            )
        } catch (_: Throwable) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.isAppearanceLightStatusBars = false
            c.isAppearanceLightNavigationBars = false
            window.statusBarColor = 0xFF000000.toInt()
            window.navigationBarColor = 0xFF000000.toInt()
            if (Build.VERSION.SDK_INT >= 29) {
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

@Composable
private fun animatedBackplate(): Brush =
    Brush.verticalGradient(
        0f to Color(0xFF202020),
        1f to Color(0xFF040404)
    )

/**
 * English comment:
 * Ultra-thin neon-like edge glow for cards.
 */
@Composable
private fun Modifier.neonEdgeThin(
    color: Color = MaterialTheme.colorScheme.primary,
    intensity: Float = 0.035f,
    corner: Dp = 20.dp
): Modifier = this.then(
    Modifier.drawBehind {
        val r = size.minDimension * 0.45f
        val cr = corner.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = intensity), Color.Transparent),
                center = center,
                radius = r
            ),
            cornerRadius = CornerRadius(cr, cr)
        )
    }
)

/* ───────────────────────────── Init Gate ───────────────────────────── */

/**
 * English comment:
 * Generic initialization gate:
 * - Runs [init] once for the given [key]
 * - Shows a loading card while running
 * - Shows an error card with retry button on failure
 * - Renders [content] after success
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

                        val pulse = rememberInfiniteTransition(label = "pulse")
                        val alpha by pulse.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1100, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "loading_alpha"
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

        else -> content()
    }
}

/* ───────────────────────────── App Nav Root ───────────────────────────── */

@Composable
fun AppNav() {
    val appContext = LocalContext.current.applicationContext
    val appVm: AppViewModel = viewModel(factory = AppViewModel.factory())
    val state by appVm.state.collectAsState()

    // English comment:
    // Start model download once when entering Idle.
    LaunchedEffect(state) {
        if (state is DlState.Idle) {
            appVm.ensureModelDownloaded(appContext)
        }
    }

    DownloadGate(
        state = state,
        onRetry = { appVm.ensureModelDownloaded(appContext) }
    ) { modelFile ->

        // 1) Load survey config
        val config: SurveyConfig = remember(appContext) {
            SurveyConfigLoader.fromAssets(appContext, "survey_config1.yaml")
        }

        // 2) Build model config from YAML SLM meta
        val modelConfig = remember(config) { buildModelConfig(config.slm) }

        // 3) Create SLM model descriptor
        val slmModel = remember(modelFile.absolutePath, modelConfig) {
            Model(
                name = "gemma-3n-E4B-it",
                taskPath = modelFile.absolutePath,
                config = modelConfig
            )
        }

        // 4) Initialize SLM under gate
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
            // Cleanup on composition disposal (activity destroy, etc.).
            DisposableEffect(slmModel) {
                onDispose {
                    runCatching {
                        SLM.cleanUp(slmModel) { }
                    }
                }
            }

            val backStack = rememberNavBackStack(FlowHome)

            // Repo to talk to SLM
            val repo: Repository = remember(appContext, slmModel, config) {
                SlmDirectRepository(slmModel, config)
            }

            // ViewModels
            val vmSurvey: SurveyViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        SurveyViewModel(nav = backStack, config = config) as T
                }
            )

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

/**
 * Survey flow host.
 *
 * English comment:
 * - UploadProgressOverlay is placed at the root so it can overlay all scenes.
 * - imePadding() is applied inside each screen, not on the root, to avoid
 *   jumping the whole layout.
 */
@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>
) {
    // Global background upload overlay
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
            entry<FlowHome> {
                IntroScreen(
                    onStart = {
                        vmSurvey.resetToStart()
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
                    onBack = { vmSurvey.backToPrevious() }
                )
            }
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
                    onRestart = { vmSurvey.resetToStart() },
                    gitHubConfig = gh
                )
            }
        }
    )

    // English comment:
    // Only intercept system back when we actually have somewhere to go back to.
    // On the root screen, let the system handle back (e.g., close the app).
    BackHandler(enabled = canGoBack) {
        vmAI.resetStates()
        vmSurvey.backToPrevious()
    }
}

/* ───────────────────────────── SLM Config Helpers ────────────────────────── */

/**
 * English comment:
 * Build a normalized model config map for the SLM engine from YAML metadata.
 * - Normalizes numeric types (Int/Double)
 * - Clamps TOP_P to [0.0, 1.0]
 * - Clamps TEMPERATURE to [0.0, +∞)
 */
private fun buildModelConfig(slm: SurveyConfig.SlmMeta): MutableMap<ConfigKey, Any> {
    val out = mutableMapOf<ConfigKey, Any>(
        ConfigKey.ACCELERATOR to ((slm.accelerator ?: "GPU").uppercase()),
        ConfigKey.MAX_TOKENS  to (slm.maxTokens ?: 512),
        ConfigKey.TOP_K       to (slm.topK ?: 1),
        ConfigKey.TOP_P       to (slm.topP ?: 0.0),
        ConfigKey.TEMPERATURE to (slm.temperature ?: 0.0)
    )
    normalizeNumberTypes(out)
    clampRanges(out)
    return out
}

/**
 * English comment:
 * Ensure all numeric values have consistent JVM number types:
 * - MAX_TOKENS, TOP_K as Int
 * - TOP_P, TEMPERATURE as Double
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
 * English comment:
 * Clamp sampling parameters to safe ranges before passing them to the engine.
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
