# TrackMe 🚵‍♂️🗺️ (v1.8.1)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Firebase](https://img.shields.io/badge/firebase-%23039BE5.svg?style=for-the-badge&logo=firebase)
![PostHog](https://img.shields.io/badge/posthog-%23000000.svg?style=for-the-badge&logo=posthog)

> **Product Vision:** TrackMe is designed to be the ultimate companion for cyclists, runners, and explorers. We believe in privacy-first tracking that seamlessly works offline, but elegantly syncs to the cloud when you want it to. Track your journey, analyze your performance, and share your adventures.

## 🌟 Key Features (v1.8.1)

*   **Camera follow you can leave (1.7.3):** Pan or zoom while recording and the map stays where you put it; the recentre control is what brings follow back. Tap a rider in your group to see them on the map, with a short trail showing their heading.
*   **Material 3 Design System (1.8.0):** Every colour in the app is now generated from a single brand seed (`#29B6F6`) as tonal ramps, rather than hand-picked hex values — with shape, spacing, elevation and motion tokens alongside it. Fixes a live accessibility defect where warning amber measured 2.15:1 on light surfaces, below the WCAG AA minimum of 4.5:1. Animation runs on a spring-based motion scheme rather than hand-picked durations, so an interrupted animation carries its velocity through instead of snapping. All five phases complete — tokens, components, every screen, notifications, and motion; see [`docs/DESIGN_SYSTEM_1.8.md`](docs/DESIGN_SYSTEM_1.8.md).
*   **A map that belongs to the app (1.8.0):** The basemap follows your theme instead of being permanently Google's default light map, drawn from the app's own neutral ramp so map and panels read as one surface. While recording, the camera pitches to 45° and turns to your direction of travel; any pan hands control back to you until you ask for follow again.
*   **Ride controls in the shade (1.8.0):** Pause, resume and finish without unlocking — and on Android 16, the ride is promoted to a status-bar chip showing elapsed time.

*   **End-to-End Encrypted (E2EE) Group Rides:** Create or join group ride sessions with client-side AES-GCM-256 location & presence encryption. Group decryption keys (`#k=...`) remain in the URI fragment and are never shared with servers.
*   **Rider Status (1.7.2):** Say what is happening — a fuel stop, a vehicle issue, a break — as a structured code whose severity is readable without knowing the message, so an older client renders a newer status at the right urgency instead of dropping it. Statuses travel in their own encrypted slot, so a rider whose location permission is revoked can still be heard.
*   **Honest freshness (1.7.2):** Every roster row carries how long ago the group last heard from that member, anchored to the relay's clock and advanced on a monotonic one — no device wall clock enters the answer. Home says plainly when the group has stopped receiving your updates.
*   **Live Member Roster & Presence Tracking:** Real-time presence status (active, paused, left) and live locations of group members rendered smoothly on HUD and map controls.
*   **Leader Controls & Universal Invite Links:** Session leaders can edit ride destination and start time, remove members, or end the group session seamlessly. Deep link opening via `/g/{code}#k={key}` with Android Digital Asset Links & fallback support.
*   **Production-Grade Firestore Security Lockdown:** Enforces strict user-isolated access control lists (`firestore.rules`) validating `uid`-based ownership across all user data, ride histories, and feedback submissions.
*   **PostHog Telemetry Engine & Remote Kill-Switch:** Comprehensive privacy-aware event tracking (`AnalyticsManager`) across authentication, ride lifecycle, live sharing, and group rides—dynamically toggled via Firebase Remote Config (`telemetry_enabled`).
*   **Centralized UI Theming & Chart Polish:** Standardized color tokens across `RideDetailScreen`, `HomeScreen`, `ActiveRideHudPanel`, and `InteractiveShareLocationButton`, along with improved padding and edge avoidance on interactive scrubbable charts.
*   **Complete Data Ownership & Cloud Export:** Download your entire tracking history from the cloud in a secure, tokenized on-demand ZIP flow to prevent vendor lock-in.
*   **Live Ride Sharing with Notifications:** Share your real-time ride progress with friends or family via a secure live-tracking link, complete with interactive red-dot UI notifications and personalized SMS templates.
*   **Offline-First Architecture:** Rides are saved locally to a robust Room Database. No internet required to track a ride—even in the most remote locations.
*   **Smart Cloud Synchronization (`WorkManager`):** Automated daily periodic background syncing (`SyncWorker`) with lightweight downstream lazy-loading (`syncPeriodic`), plus manual full cloud sync (`syncAll`) with a sleek `CloudSync` button.
*   **Global Multi-Language Localization:** Built-in dynamic locale support for 7 languages (`en`, `es`, `fr`, `de`, `hi`, `ja`, `zh`) across all application screens.
*   **Modern Declarative UI:** Built entirely with Jetpack Compose for a buttery-smooth, reactive user experience following Material 3 design guidelines.

## 🛠️ Technology Stack

*   **Language:** Kotlin
*   **UI Toolkit:** Jetpack Compose, Material Design 3
*   **Design System:** Two-tier design tokens generated from a single brand seed — colour, shape, spacing, elevation and spring-based motion ([`docs/DESIGN_SYSTEM_1.8.md`](docs/DESIGN_SYSTEM_1.8.md))
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
