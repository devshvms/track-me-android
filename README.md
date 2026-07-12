# TrackMe 🚵‍♂️🗺️ (v1.4.0)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Firebase](https://img.shields.io/badge/firebase-%23039BE5.svg?style=for-the-badge&logo=firebase)

> **Product Vision:** TrackMe is designed to be the ultimate companion for cyclists, runners, and explorers. We believe in privacy-first tracking that seamlessly works offline, but elegantly syncs to the cloud when you want it to. Track your journey, analyze your performance, and share your adventures.

## 🌟 Key Features (v1.4.0)

*   **Complete Data Ownership & Cloud Export:** Download your entire tracking history from the cloud in a secure, streamlined asynchronous flow to prevent vendor lock-in.
*   **Live Ride Sharing with Notifications:** Share your real-time ride progress with friends or family via a secure live-tracking link, complete with interactive red-dot UI notifications and personalized SMS templates.
*   **Emergency SOS & Safety Beacon:** Configure emergency contacts and broadcast immediate emergency alerts with a streamlined push-button action directly from the HUD.
*   **Offline-First Architecture:** Rides are saved locally to a robust Room Database. No internet required to track a ride—even in the most remote locations.
*   **Smart Cloud Synchronization (`WorkManager`):** Automated daily periodic background syncing (`SyncWorker`) with lightweight downstream lazy-loading (`syncPeriodic`), plus manual full cloud sync (`syncAll`) with a sleek `CloudSync` button.
*   **Ride History Overhaul:** High-density compact list design with native sticky section headers (`stickyHeader`), vector route thumbnails (`RoutePreviewThumbnail`), and quick Sync & Distance filters. Includes intelligent graph axis labeling that prevents edge clipping.
*   **Global Multi-Language Localization:** Built-in dynamic locale support for 7 languages (`en`, `es`, `fr`, `de`, `hi`, `ja`, `zh`) across all application screens.
*   **In-App Auto-Update Notifications:** Automatic non-blocking version checks on app launch against Firestore remote configuration (`config/app_release`) and GitHub Releases API.
*   **Modern Declarative UI:** Built entirely with Jetpack Compose for a buttery-smooth, reactive user experience following Material 3 design guidelines.

## 🛠️ Technology Stack

*   **Language:** Kotlin
*   **UI Toolkit:** Jetpack Compose, Material Design 3
*   **Architecture:** MVVM (Model-View-ViewModel) with Clean Architecture principles
*   **Asynchronous Programming:** Kotlin Coroutines & `StateFlow`
*   **Background Tasks:** Android Jetpack `WorkManager` (`SyncWorker`)
*   **Local Storage:** Room Database (SQLite)
*   **Backend & Auth:** Firebase Authentication (Google Sign-In) & Cloud Firestore
*   **Location & Maps:** Google Play Services Location APIs, Google Static Maps API

## 🚀 Getting Started

### Prerequisites
*   Android Studio Iguana (or newer)
*   JDK 17
*   Minimum SDK: 24 (Android 7.0)
*   Target SDK: 36 (Android 16)

### Setup Instructions

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/your-org/track-me-android.git
    cd track-me-android
    ```

2.  **Configure Firebase:**
    *   Go to the [Firebase Console](https://console.firebase.google.com/) and create a new project.
    *   Add an Android app with the package name `com.example.trackme`.
    *   Download `google-services.json` and place it in the `app/` directory.
    *   Enable **Google Sign-In** in Firebase Authentication.
    *   Enable **Firestore Database**.

3.  **Configure API Keys Securely:**
    *   Obtain a Google Maps API Key from the Google Cloud Console.
    *   **CRITICAL:** Do not hardcode API keys in `strings.xml`. Add them to your `local.properties` file (which is git-ignored):
        ```properties
        MAPS_API_KEY=your_actual_api_key_here
        WEB_CLIENT_ID=your_web_client_id_here.apps.googleusercontent.com
        ```
    *   *Note: The `build.gradle.kts` is configured to read these properties and inject them into the Manifest and BuildConfig during compilation.*

4.  **Build and Run:**
    *   Sync the Gradle project and hit "Run" on an emulator (with location mocking) or a physical device.

## 🤝 Contributing
Please read our [Technical Documentation](./TECHNICAL_DOCUMENTATION.md) to understand the architecture and coding conventions before submitting a Pull Request.

---
*Architected with ❤️ for performance, scalability, and maintainability.*
