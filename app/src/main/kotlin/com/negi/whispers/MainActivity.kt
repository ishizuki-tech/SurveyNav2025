/*
 * ================================================================
 *  IshizukiTech LLC — Whisper Integration Framework
 *  ------------------------------------------------
 *  File: MainActivity.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * ================================================================
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the “Software”), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 * ================================================================
 */

@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.negi.whispers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.whispers.ui.main.MainScreenEntryPoint
import com.negi.whispers.ui.main.MainScreenViewModel
import com.negi.whispers.ui.theme.WhispersTheme
import kotlinx.serialization.InternalSerializationApi

/**
 * **MainActivity — Application entry point for the Whisper App.**
 *
 * ## Overview
 * Hosts the entire Compose UI hierarchy and manages global app initialization.
 * Designed for simplicity and full edge-to-edge rendering on modern Android devices.
 *
 * ## Responsibilities
 * - Initializes an immersive, edge-to-edge system UI layout.
 * - Instantiates [MainScreenViewModel] via an Application-aware factory.
 * - Injects [MainScreenEntryPoint] as the Compose root node.
 *
 * ## Design Rationale
 * - Uses [enableEdgeToEdge] to draw content beneath system bars, improving immersion.
 * - Avoids dependency injection frameworks for reduced startup overhead.
 * - Opts into [InternalSerializationApi] for `MyRecord`’s custom serialization.
 *
 * ## Usage
 * Called automatically when the application launches.
 * Handles system UI setup and forwards all interaction to the Compose layer.
 */
class MainActivity : ComponentActivity() {

    @OptIn(InternalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enables transparent status/navigation bars for immersive layout
        enableEdgeToEdge()

        setContent {
            // Instantiates MainScreenViewModel scoped to Application lifecycle
            val viewModel: MainScreenViewModel =
                viewModel(factory = MainScreenViewModel.factory(application))

            // Wraps UI content in app’s custom Material 3 theme
            WhispersTheme {
                MainScreenEntryPoint(viewModel)
            }
        }
    }
}
