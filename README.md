# SurveyNav 2025

Config-driven survey navigator app for Android, designed as a reference implementation of the **“SLM Integration Framework”** for small language model (SLM) workflows.

This repository shows how to:

- Describe survey flows and AI prompts in portable YAML / JSON.
- Load, validate, and run those flows on Android using Jetpack Compose.
- Attach SLM metadata (model, decoding params, etc.) that downstream engines can use.

> Status: **WIP / experimental.** Public APIs and config schema are not stable yet and may change.

---

## Table of contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Configuration](#configuration)
    - [SurveyConfig schema](#surveyconfig-schema)
    - [YAML example](#yaml-example)
- [Getting started](#getting-started)
    - [Requirements](#requirements)
    - [Download (APK)](#download-apk)
    - [Build and run from source](#build-and-run-from-source)
- [CI / CD & GitHub integration](#ci--cd--github-integration)
    - [GitHub Pages](#github-pages)
    - [GitHub Wiki auto-update](#github-wiki-auto-update)
    - [GH_TOKEN setup](#gh_token-setup)
- [Project structure](#project-structure)
- [Roadmap](#roadmap)
- [License](#license)

---

## Overview

SurveyNav 2025 is an Android app that renders survey flows from a portable configuration file.

The main idea:

- You **do not** hard-code the question flow in Kotlin.
- Instead, you write a **`SurveyConfig`** file (YAML or JSON) that describes:
    - Nodes (START / QUESTION / AI / REVIEW / DONE, etc.).
    - Edges between nodes (`nextId`).
    - Text for prompts and questions.
    - Metadata for the SLM backend (model name, decoding params, accelerator, etc.).

At runtime, the app:

1. Loads the config from `assets/` using `SurveyConfigLoader`.
2. Validates it and fails early with a clear error if something is wrong.
3. Uses the config to drive the UI and SLM calls.

This repo is intended as:

- A **reference** for building config-driven survey apps.
- A **playground** for experimenting with SLM-backed question/answer flows on-device.

---
## Demo screenshots and video

<p align="center">
<img src="images/Screenshot_20251117_134942.png" alt="Home screen – SurveyNav 2025" width="120">
<img src="images/Screenshot_20251117_135301.png" alt="Question screen – SurveyNav 2025" width="120">
</p>

## Demo videos

<p align="center">
  <video
    src="videos/Screen_recording_20251117_131203.mp4"
    controls
    width="180"
    style="max-width: 100%; height: auto;">
    Your browser does not support the video tag.
  </video>
  <video
    src="videos/Screen_recording_20251117_131343.mp4"
    controls
    width="180"
    style="max-width: 100%; height: auto;">
    Your browser does not support the video tag.
  </video>
  <video
    src="videos/Screen_recording_20251117_132537.mp4"
    controls
    width="180"
    style="max-width: 100%; height: auto;">
    Your browser does not support the video tag.
  </video>
</p>

---
## Features

- **Config-driven survey graph**

    - Nodes, edges, prompts, and SLM metadata are defined in a single `SurveyConfig`.
    - YAML or JSON, with safe, forward-compatible parsing.

- **Strict validation with friendly errors**

    - Checks for missing `startId`, unknown `nodeId` / `nextId`, duplicate IDs, etc.
    - Validates SLM params (`top_k`, `top_p`, `temperature`, `max_tokens`, etc.).
    - Returns a list of human-readable error messages, instead of crashing deep in the stack.

- **Jetpack Compose UI**

    - Single-activity, Compose-based UI for rendering survey screens.
    - Config is loaded via `SurveyConfigLoader.fromAssets(...)` and stored in Compose state.

- **SLM-aware metadata**

    - SLM configuration is represented by a serializable `SlmMeta` data class.
    - The configuration file (YAML / JSON) uses **snake_case** keys like  
      `model_name`, `model_family`, `max_tokens`, `top_k`, `top_p`, `temperature`, `stop`.
    - These are mapped to Kotlin camelCase fields via `@SerialName`, so you can keep configs stable while refactoring code.
    - Actual SLM execution is intentionally decoupled, so you can plug in your own engine.

- **YAML + JSON support**

    - Uses `kotlinx.serialization` for JSON.
    - Uses KAML (`com.charleskorn.kaml.Yaml`) for YAML.
    - Can sniff format from file extension or content.

---

## Architecture

High-level components:

- **`SurveyConfig` (model)**  
  Serializable data classes describing:

    - `SurveyConfig.Prompt`
    - `SurveyConfig.Graph`
    - `SurveyConfig.SlmMeta`
    - `NodeDTO` + `NodeType` enum for graph nodes

  Includes helper methods (names simplified here):

    - `validate(): List<String>`
    - `toJson(pretty: Boolean)`
    - `toJsonl()`
    - `composeSystemPrompt()` (for building composite system prompts)

- **`SurveyConfigLoader` (config loader)**

  Responsible for:

    - Loading config from assets (`fromAssets`), files (`fromFile`), or raw strings (`fromString`).
    - Normalizing input text (BOM removal, newline unification).
    - Auto-detecting format (YAML vs JSON) via file extension or content sniffing.
    - Using lenient JSON parsing with `ignoreUnknownKeys = true`.

- **UI layer (Compose)**

    - `MainActivity` sets up the Compose content and loads the config:

      ```kotlin
      val config = remember(appContext) {
          SurveyConfigLoader.fromAssets(appContext, "survey_config1.yaml")
      }
      ```

    - A view-model or state holder can then use `config.graph`, `config.prompts`, and `config.slm`
      to drive the screens and SLM calls.

- **SLM backend (pluggable)**

    - The app does not hard-code a specific SLM engine.
    - The `SlmMeta` struct is designed to be mapped into your own engine configuration
      (e.g. llama.cpp, MediaPipe Tasks, custom SLM runtime, etc.).

---

## Configuration

### SurveyConfig schema

The `SurveyConfig` data class roughly looks like this (simplified and aligned with the YAML keys):

```kotlin
@Serializable
data class SurveyConfig(
    val prompts: List<Prompt> = emptyList(),
    val graph: Graph,
    val slm: SlmMeta = SlmMeta()
) {
    @Serializable
    data class Prompt(
        val id: String,
        val nodeId: String,
        val role: String,
        val content: String
    )

    @Serializable
    data class Graph(
        val startId: String,
        val nodes: List<NodeDTO>
    )

    @Serializable
    data class SlmMeta(
        @SerialName("model_name")   val modelName: String = "",
        @SerialName("model_family") val modelFamily: String = "",
        val backend: String = "",
        val accelerator: String = "CPU",
        @SerialName("max_tokens")   val maxTokens: Int = 256,
        @SerialName("top_k")        val topK: Int = 40,
        @SerialName("top_p")        val topP: Double = 0.95,
        val temperature: Double = 0.7,
        val stop: List<String> = emptyList()
    )
}
```

Notes:

- The **config file** uses snake_case keys (`model_name`, `model_family`, `max_tokens`, …).
- The **Kotlin code** uses camelCase field names, bridged via `@SerialName`.
- The actual project may contain more fields and stricter validation, but this gives the idea.

### YAML example

A minimal `survey_config1.yaml` might look like:

```yaml
graph:
  startId: Start
  nodes:
    - id: Start
      type: START
      nextId: Q1

    - id: Q1
      type: AI
      question: "Please describe your farm size and main crops."
      nextId: REVIEW

    - id: REVIEW
      type: REVIEW
      nextId: DONE

    - id: DONE
      type: DONE

prompts:
  - id: sys-1
    nodeId: Q1
    role: system
    content: |
      You are an assistant helping to collect survey responses from farmers.
      Ask clear, concise questions and confirm critical details.

slm:
  model_name: "my-slm-model"
  model_family: "llama"
  backend: "local"
  accelerator: "CPU"
  max_tokens: 256
  top_k: 40
  top_p: 0.95
  temperature: 0.7
  stop: ["</s>"]
```

Place your config under:

```text
app/src/main/assets/survey_config1.yaml
```

and adjust the file name in `MainActivity` if needed.

---

## Getting started

### Requirements

- Android Studio (Hedgehog / Jellyfish / newer)
- JDK 17+
- Android SDK platform 35
- Android build-tools 35.0.0
- A device or emulator running a reasonably recent Android version (see `minSdk` in `app/build.gradle.kts`)

### Download (APK)

If you just want to try the app without building from source:

- Visit the GitHub Pages site (see [GitHub Pages](#github-pages)).
- Download the latest APK to your Android device.
- Install it by opening the downloaded APK on your device (you may need to allow installs from your browser / file manager).

### Build and run from source

1. **Clone the repo**

   ```bash
   git clone https://github.com/ishizuki-tech/SurveyNav2025.git
   cd SurveyNav2025
   ```

2. **Open in Android Studio**

    - Use “Open” and select the root directory.
    - Let Gradle sync and download dependencies.

3. **Run on device/emulator**

    - Select the `app` run configuration.
    - Click **Run**.
    - The app should start and load the default `survey_config1.yaml` from `assets/`.

4. **Edit the survey**

    - Modify `app/src/main/assets/survey_config1.yaml`.
    - Rebuild and rerun the app to see the new flow.

---

## CI / CD & GitHub integration

This repository is set up to use GitHub Actions for:

- Building the Android app on CI.
- Installing the Android SDK (platform 35, build-tools 35.0.0).
- Optionally publishing:
    - A GitHub Pages site with documentation / links.
    - A GitHub Wiki page with generated metadata.

### GitHub Pages

The Pages site is expected at:

- `https://ishizuki-tech.github.io/SurveyNav2025/`

Depending on the workflow configuration, this may be built from:

- The `gh-pages` branch, or
- A `docs/` directory in the main branch.

Check `.github/workflows/*.yml` in this repo to see the current setup.

### GitHub Wiki auto-update

Some workflows may push generated content (such as model metadata, config docs, or release info) to:

- `https://github.com/ishizuki-tech/SurveyNav2025/wiki`

This typically requires a token with `repo` scope, configured as a secret.

### GH_TOKEN setup

If CI logs show something like:

```text
GH_TOKEN is not configured
```

then you need to:

1. **Create a Personal Access Token** on GitHub (a classic token is fine), with at least:

    - `repo` (for pushing to wiki / pages branches)
    - `workflow` (if workflows need to be triggered)

2. **Add it as a repository secret**:

    - Go to: `Settings` → `Secrets and variables` → `Actions`
    - Add a new repository secret:
        - Name: `GH_TOKEN`
        - Value: `<your personal access token>`

3. **Re-run the workflow.**

Security note:

- Never commit this token to the repository or hard-code it in workflows.
- Always store it as an Actions secret or use the built-in `GITHUB_TOKEN` where possible.

If you prefer, you can also modify the workflows to use GitHub’s built-in `GITHUB_TOKEN` instead of a custom `GH_TOKEN`, but the default token has some limitations when pushing to wiki or triggering downstream workflows.

---

## Project structure

High-level layout (simplified):

```text
SurveyNav2025/
├─ app/
│  ├─ src/
│  │  ├─ main/
│  │  │  ├─ java/ or kotlin/
│  │  │  │  └─ com/negi/survey/
│  │  │  │     ├─ MainActivity.kt
│  │  │  │     └─ config/
│  │  │  │        ├─ SurveyConfig.kt      (model)
│  │  │  │        └─ SurveyConfigLoader.kt (loader + validation)
│  │  │  └─ assets/
│  │  │     └─ survey_config1.yaml
│  │  └─ androidTest/ ...
│  └─ build.gradle.kts
├─ .github/
│  └─ workflows/
│     └─ *.yml (CI / Pages / Wiki)
└─ README.md  ← you are here
```

Names and paths may vary slightly in the actual repo, but this is the intended structure.  
In a more advanced setup, you may extract `SurveyConfig` and `SurveyConfigLoader` into a shared library module (e.g. an `androidslm` module) and keep this app as a thin reference implementation.

---

## Roadmap

Planned / possible extensions:

- More node types (branching logic, loops, external data lookups).
- Built-in visualizations of the survey graph (debugging aid).
- Integration demo with a specific SLM engine (e.g. llama.cpp, MediaPipe Tasks).
- Richer validation (reachability analysis, cycle detection).
- In-app editor to tweak and reload configs at runtime.
- Additional examples for different domains (agriculture, education, health, etc.).

Contributions, issues, and discussion are all welcome.

---

## License

Unless otherwise noted in individual files:

- License: **MIT License**
- Copyright:
    - `© 2025 IshizukiTech LLC. All rights reserved.`
