# SurveyNav

SurveyNav is an **Android survey application** designed for offline-first data collection.  
It leverages Jetpack Compose, Navigation 3, and ViewModels to provide a flexible engine for multi-step surveys, AI-powered follow-ups, and speech input.

---

## 📌 Features

- **Survey Navigation Engine**  
  - Compose Navigation3-based survey flow  
  - Dynamic node definitions (e.g., Q1, Q2, Review, End)  
  - AI interstitial screens (response evaluation, follow-up insertion)

- **UI**  
  - Built with Jetpack Compose + Material3  
  - Supports free text, single-choice, multi-choice questions  
  - Smooth animations and scrollable layouts

- **AI Integration (MediaPipe GenAI / llama.cpp / whisper.cpp planned)**  
  - Small Language Model (SLM) for response validation  
  - Auto-generated follow-up questions  
  - Offline speech recognition with Whisper / ONNX Runtime

- **Developer Friendly**  
  - FakeRepository for testing  
  - State management with Kotlin Flow + StateFlow  
  - Unit / Instrumented test support

---

## 🚀 Getting Started

### Requirements
- Android Studio Ladybug or newer
- Kotlin 2.0+
- Gradle 8+
- JDK 17

### Build
```bash
git clone https://github.com/ishizuki-tech/SurveyNav.git
cd SurveyNav
./gradlew assembleDebug
```

Open the project in Android Studio, select the `app` module, and run on an emulator or device.

---

## 📂 Project Structure

```
SurveyNav/
 ├─ app/                 # Main Android app
 │   ├─ src/main/kotlin/ # Kotlin source code
 │   └─ src/main/res/    # Resources
 ├─ gradle/              # Gradle wrapper
 ├─ build.gradle.kts     # Root Gradle config
 ├─ settings.gradle.kts
 └─ README.md
```

---

## 🔒 Handling Secrets

⚠️ **Do not commit API tokens or credentials.**  
Instead, store them in `local.properties` or `gradle.properties` and reference via `BuildConfig`.

Example: `local.properties`
```properties
HF_TOKEN=your_hf_token_here
```

Example: `build.gradle.kts`
```kotlin
android {
    defaultConfig {
        buildConfigField("String", "HF_TOKEN", ""${properties["HF_TOKEN"] ?: ""}"")
    }
}
```

---

## 🧪 Testing

- Unit tests:
  ```bash
  ./gradlew test
  ```
- Instrumented tests:
  ```bash
  ./gradlew connectedAndroidTest
  ```

---

## 📌 Roadmap

- [ ] GitHub Actions CI workflow  
- [ ] External JSON-based survey graph definition  

---

## 📜 License

This project is licensed under the [MIT License](https://opensource.org/licenses/MIT).

---
