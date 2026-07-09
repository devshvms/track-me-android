# TrackMe Android - Technical Documentation

Welcome to the TrackMe technical documentation. This guide is intended to help engineers understand the architecture, dependencies, and configuration of the project.

## 1. Technical Dependencies

TrackMe is built using modern Android development practices. Here are the core dependencies:

### Core & UI
*   **Kotlin (1.9+)**: The primary programming language.
*   **Jetpack Compose**: The modern declarative UI toolkit used for all screens.
*   **Navigation Compose**: For in-app routing between screens.
*   **Material 3**: The design system components used throughout the app.

### Architecture & Async
*   **Coroutines & Flow**: Used for asynchronous programming, background tasks, and reactive data streams.
*   **Lifecycle & ViewModel**: For managing UI state and surviving configuration changes.

### Data Storage & Cloud
*   **Room Database**: Local SQLite abstraction for persisting ride data, GPS points, and emergency contacts.
*   **Firebase**:
    *   **Auth**: Integrated via `androidx.credentials` (Google Sign-In).
    *   **Firestore**: For cloud syncing and backup of user data.
    *   **Crashlytics**: For remote error and crash logging.

### Location & Maps
*   **Google Play Services Location**: For fetching high-accuracy GPS coordinates via `FusedLocationProviderClient`.
*   **Google Maps SDK**: For rendering maps (`play-services-maps`) and **Maps Compose** for declarative map integration.
*   **Google Maps Utils**: For spherical geometry calculations and polyline utilities.

### Utilities
*   **Vico Charts**: For rendering the speed and altitude graphs in the Ride Detail screen.
*   **Coil Compose**: For image loading (if needed).

---

## 2. Codebase Structure (What to find where)

The project follows a feature-based and layer-based hybrid structure under `app/src/main/java/in/shvms/trackme/`:

```
trackme/
├── MainActivity.kt         // Entry point of the Android application.
├── TrackMeApp.kt           // Main Compose entry containing the Scaffold and global Snackbar.
├── Navigation.kt           // Defines the Navigation Graph and routes (Home, History, Settings, etc.).
├── config/
│   └── AppConfig.kt        // Global configuration constants (Map colors, export sizes, etc.).
├── auth/
│   └── AuthManager.kt      // Google Sign-In and Credential Manager integration.
├── ui/                     // UI Layer (Compose Screens and ViewModels)
│   ├── components/         // Reusable Compose components (e.g., SwipeToTriggerSlider).
│   ├── home/               // HomeScreen and HomeViewModel (Active tracking UI).
│   ├── history/            // HistoryScreen, RideDetailScreen and ViewModels (Past rides).
│   └── settings/           // SettingsScreen, EmergencySetupScreen, AccountManagementScreen.
├── service/                // Background Services and Device Integration
│   ├── TrackingService.kt  // Android Foreground Service ensuring OS doesn't kill tracking.
│   ├── LocationHelper.kt   // Wraps FusedLocationProviderClient.
│   ├── EmergencyManager.kt // Handles sending SMS and emergency logic.
│   ├── EmergencyBroadcastWorker.kt // WorkManager task for delayed emergency broadcasts.
│   └── TrackingManager.kt  // Singleton managing the tracking state across the app.
├── data/                   // Data Layer
│   ├── DataRepository.kt   // Single source of truth abstracting DB and Network.
│   ├── local/              // Room Database, DAOs (RideDao, EmergencyDao), Entities.
│   └── remote/             // FirestoreSyncManager for uploading/downloading rides.
├── domain/                 // Business Logic Use Cases
│   ├── processor/          // GPSProcessor (Filters inaccurate points, calculates distance).
│   ├── export/             // Exporters (NativeSnapshotImageExporterImpl, GPXExporter).
│   └── import/             // GPXParser (Parsing GPX files to Ride entities).
└── utils/
    └── logger/             // CrashlyticsErrorLogger for non-fatal exception tracking.
```

---

## 3. Credentials and Configuration Management

### Secrets & API Keys
Secrets are **not** checked into version control. They are managed via `local.properties` or environment variables:
*   `MAPS_API_KEY`: Injected into the `AndroidManifest.xml` via Gradle `resValue`.
*   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: Used for signing the Release APK.
*   `google-services.json`: Downloaded from the Firebase Console and placed in the `app/` directory to configure Firebase services.

### App Configuration
*   **`AppConfig.kt`**: Contains all non-secret business constants. This includes map overlay colors, static map URLs, image export aspect ratios, and padding configurations. It serves as the single source of truth for technical product configurations.

---

## 4. Technical Design Patterns

### Implemented Patterns
1.  **MVVM (Model-View-ViewModel)**: The core architecture. UI screens are stateless and observe `StateFlow` from ViewModels. ViewModels handle user intents and communicate with the Repository.
2.  **Repository Pattern**: `DataRepository` abstracts the origin of data. The rest of the app doesn't know if data comes from Room or Firestore.
3.  **Singleton Pattern**: Used for core managers (`TrackingManager`, `AuthManager`) that need a single global lifecycle matching the Application.
4.  **Observer Pattern**: Implemented heavily via Kotlin `Flow` and `StateFlow` for reactive UI updates (e.g., location updates stream from `TrackingManager` to `HomeViewModel`).
5.  **Strategy/Adapter Pattern**: Used in Exporters (`ImageExporter`, `GPXExporter`) to allow swapping out export logic.

### Areas for Improvement
1.  **Dependency Injection**: Currently, the app uses manual dependency injection or passing Singletons directly. **Action**: Implement **Hilt/Dagger** to manage dependencies cleanly, which will improve testability.
2.  **Domain Layer Isolation**: The domain layer currently depends on some Android framework classes. **Action**: Create pure Kotlin UseCases to further decouple business logic from the Android SDK.
3.  **Testing**: Introduce more robust Unit Tests for `GPSProcessor` and UI Tests for Compose components.

---

## 5. Developer Onboarding (Getting Started)

1.  **Clone the Repo**.
2.  Create a `local.properties` file in the root directory and add:
    ```properties
    MAPS_API_KEY=your_google_maps_api_key_here
    ```
3.  Obtain the `google-services.json` file from the lead engineer and place it in the `app/` folder.
4.  Sync Gradle. The app uses Kotlin DSL (`build.gradle.kts`) and version catalogs (if migrated) or direct dependencies.
5.  Run on an emulator or physical device. **Note**: A physical device is recommended for testing GPS and SMS emergency features.

> [!TIP]
> When debugging location issues, check `GPSProcessor.kt`. This class contains the filtering algorithms that discard inaccurate points or points that violate speed constraints.
