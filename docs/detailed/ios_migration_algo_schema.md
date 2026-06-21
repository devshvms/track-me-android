# TrackMe: iOS Migration Guide - Comprehensive Feature Mapping

This document provides a detailed breakdown of the algorithms, database schema, and all core features used in the Android TrackMe application, mapping them to their direct equivalents for an iOS replica. This ensures all functionality is replicated accurately without losing any features.

## 1. Core Algorithms & Business Logic (`GPSProcessor`)

### A. Outlier Removal & Acceleration Tracking
- **Android:** Calculates speed between consecutive GPS points. If required acceleration exceeds `MAX_ACCELERATION_G` (1.5G, or ~14.7 m/s²), the point is discarded. Uses Android's `Location.distanceBetween`.
- **iOS:** Calculate distance using `CLLocation.distance(from:)`. Compute time delta using `Date.timeIntervalSince(_:)`. Filter points where `(speedDelta / timeDelta) > 14.7`.

### B. Altitude & Speed Smoothing (Simple Moving Average)
- **Android:** Applies a moving average with a window size of 5 to smooth out altitude and speed jitters.
- **iOS:** Implement an array extension or a sliding window algorithm in Swift to average the `altitude` and `speed` properties over 5 consecutive `CLLocation` objects.

### C. Retroactive Auto-Pause Detection
- **Android:** Evaluates a 15-second sliding window. If average speed is below `2.5 km/h` (~0.69 m/s), the point is marked `isPaused = true`.
- **iOS:** Apply a similar sliding window across the smoothed array of points in Swift, tagging a custom `isPaused` boolean.

### D. 4D Douglas-Peucker Line Simplification
- **Android:** Compresses route data (epsilon = 2.0). Uses Heron's formula for cross-track distance, factoring in geographic, altitude, and speed deviation.
- **iOS:** Implement a recursive Douglas-Peucker algorithm in Swift using `CLLocation` math to match the 4D weighting.

## 2. Database Schema (Room to CoreData/SwiftData)

### Ride Entity (High-Level Metadata)
| Android (Room) | iOS (CoreData / SwiftData) | Description |
| :--- | :--- | :--- |
| `id: Long` (AutoGenerate) | `id: UUID` or `Int64` | Primary Key |
| `startTime: Long` | `startTime: Date` | Epoch timestamp |
| `endTime: Long?` | `endTime: Date?` | Nullable end time |
| `sourceInfo: String` | `sourceInfo: String` | Default "iOS Device" |
| `isBroadcasted: Boolean` | `isBroadcasted: Bool` | Emergency broadcast state |
| `isSynced: Boolean` | `isSynced: Bool` | Upload state to Firestore |
| `firestoreId: String?` | `firestoreId: String?` | Link to remote doc |
| `title: String?` | `title: String?` | User-defined or auto title |
| `postRideCalculation` (Embedded)| Embedded/Relationships | Contains maxSpeed, distance, avgSpeed, etc. |

### GPS Point Entity (Trajectory Data)
| Android (Room) | iOS (CoreData / SwiftData) | Description |
| :--- | :--- | :--- |
| `id: Long` (AutoGenerate) | `id: UUID` | Primary Key |
| `rideId: Long` | `ride: RideEntity` | Foreign Key (Relationship) |
| `latitude: Double` | `latitude: Double` | From `CLLocationDegrees` |
| `longitude: Double` | `longitude: Double` | From `CLLocationDegrees` |
| `altitude: Double` | `altitude: Double` | From `CLLocationDistance` |
| `accuracy: Float` | `accuracy: Float` | `horizontalAccuracy` |
| `speed: Float` | `speed: Float` | `CLLocationSpeed` |
| `timestamp: Long` | `timestamp: Date` | Ping time |
| `isPaused: Boolean` | `isPaused: Bool` | Assigned by Auto-Pause algo |

## 3. Advanced Features & Exporters

### A. Emergency SOS System (`EmergencyBroadcastWorker`)
- **Android Workflow:** Triggers an active emergency state. Sends automated SMS messages using `SmsManager` to saved contacts. Replaces tags in the user's template (`[Location Link]`, `[Battery Percent]`, `[Device Name]`, `[Timestamp]`) with real data. Progressively backs off the broadcast frequency (every 2m for first 10m, then 10m for an hour, then 1h up to 24h). Vibrates the phone up to `MAX_HAPTIC_MESSAGES` (5) times to provide physical feedback that the SOS was sent.
- **iOS Equivalent:** iOS restrictions strictly prohibit apps from sending SMS silently in the background without user interaction via `MFMessageComposeViewController`. 
  - *Workaround:* Use a backend service (e.g., Firebase Cloud Functions + Twilio) to send the SMS. The iOS app will just ping Firestore with the latest coordinates and trigger the cloud function. Use `UIImpactFeedbackGenerator` or `AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)` for the haptic feedback.

### B. High-Quality Image Export (`ImageExporter`)
- **Android Workflow:** Supports two modes: Native Map Snapshot and Google Static Maps API. Creates a composite bitmap (PNG) combining the map and a dark overlay banner at the bottom (20% height). Draws the ride stats (Date, Time Duration, Distance) using `Canvas`.
- **iOS Equivalent:** Render a `MKMapView` off-screen or take a snapshot using `MKMapSnapshotter`. Draw the route overlay using `MKPolyline`. Use CoreGraphics (`UIGraphicsImageRenderer`) to composite the banner and text (Date, Time Duration, Distance) onto the final `UIImage`.

### C. GPX File Export (`GPXExporter`)
- **Android Workflow:** Uses standard XML serialization to construct a `<gpx>` document (version 1.1). Formats dates in `yyyy-MM-dd'T'HH:mm:ss'Z'` UTC. Injects `lat`, `lon`, `ele`, and `time` into `<trkpt>` tags.
- **iOS Equivalent:** Use Swift's `XMLParser` or manual string interpolation (since it's a simple format) to generate the `.gpx` file. Use `ISO8601DateFormatter` to ensure strict UTC timestamps. Allow users to export using `UIActivityViewController`.

### D. Authentication (`AuthManager`)
- **Android Workflow:** Uses Jetpack Credential Manager (`GetGoogleIdOption`) for one-tap Google Sign-In, linked to Firebase Authentication.
- **iOS Equivalent:** Implement **Sign in with Apple** and **Google Sign-In for iOS**, linked to Firebase Auth. *Note: Apple App Store guidelines mandate "Sign in with Apple" if any other third-party social login (like Google) is provided.*

## 4. Architecture & Tech Stack Equivalents

| Concept / Android Tech | iOS Equivalent | Notes |
| :--- | :--- | :--- |
| **UI Framework:** Jetpack Compose | **SwiftUI** | 100% declarative UI based on State. |
| **Architecture:** MVVM | **MVVM or TCA** | ViewModels mapped to `@Observable` or `ObservableObject`. |
| **Reactivity:** StateFlow / Coroutines | **Combine / Swift Concurrency** | Use `@Published` and `Task {}` blocks. |
| **Background Tracking:** Foreground Service & WakeLock | **UIBackgroundModes (Location)** | Enable 'Location Updates' in background modes. No WakeLocks needed on iOS. Request `allowsBackgroundLocationUpdates = true` on `CLLocationManager`. |
| **Cloud Sync:** Firebase Firestore | **Firebase Firestore (Swift SDK)** | Keeps the exact same offline-first syncing logic. |
| **Maps:** Google Maps SDK | **MapKit (SwiftUI)** | MapKit is native, free, and highly optimized for SwiftUI. |
| **Charts (Vico):** Vico Compose | **Swift Charts** | Apple's native Swift Charts framework is perfect for the speed/elevation profiles. |
