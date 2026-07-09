# TrackMe v1.2.0 — "Horizon" Release Notes & iOS Implementation Spec

**Release Name:** TrackMe v1.2.0 (Horizon)  
**Target Platform:** Android (Released) & iOS (Parity Spec)  
**Release Theme:** High-Density Ride History, Smart Cloud Synchronization Architecture & Global Multi-Language Localization

---

## Executive Summary

TrackMe **v1.2.0 ("Horizon")** delivers a complete UX overhaul of the **Ride History** experience, introduces intelligent background cloud synchronization decoupled from manual triggers, adds multi-language localization across 7 major languages, and documents critical live-sharing and safety beacon workflows.

This release note serves as the **Cross-Platform Parity Reference** so the iOS application can mirror these architectural and UI/UX standards.

---

## 1. Ride History Overhaul (UI/UX Specification)

### A. Compact & High-Density Card Layout
*   **Problem Solved:** Previous ride cards were overly tall, had excess padding, and displayed cluttered Google attribution logos on thumbnails.
*   **Android Implementation:**
    *   Reduced card padding and vertical gap between list items to `8.dp`.
    *   Optimized card height ratio to a streamlined, compact landscape layout (`72.dp` thumbnail height).
*   **iOS Implementation Guidelines (`SwiftUI` / `UIKit`):**
    *   Use a compact `HStack` inside `List` or `LazyVStack` with minimal padding (`6-8pt`).
    *   Standardize ride thumbnail dimensions to `80x60pt` with `cornerRadius(8)`.

### B. Lightweight Vector Route Thumbnails (`RoutePreviewThumbnail`)
*   **Problem Solved:** Loading remote Google Static Maps tiles for every item caused network bottlenecks, unnecessary API quota usage, and visible Google watermark clutter.
*   **Android Implementation:**
    *   Built a custom lightweight 2D Canvas vector renderer (`RoutePreviewThumbnail`).
    *   Calculates the geographic bounding box (`minLat`, `maxLat`, `minLng`, `maxLng`) of the ride's GPS track points and draws a normalized vector path directly onto the canvas.
*   **iOS Implementation Guidelines (`SwiftUI` `Path` / `CoreGraphics`):**
    *   Implement a local `Path` view that takes an array of `CLLocationCoordinate2D`.
    *   Normalize coordinates into the view's frame `CGRect` and render a smooth vector line (`strokeColor: .accentColor, lineWidth: 2.5`) without making external map API calls.

### C. Sticky Date-Grouped Headers (`stickyHeader`)
*   **Problem Solved:** Users lost context of when rides took place while scrolling long lists.
*   **Android Implementation:**
    *   Grouped rides by relative dates: **Today**, **Yesterday**, **This Week**, **This Month**, **Older**.
    *   Used `LazyColumn(stickyHeader)` so only the *currently active group header* pins to the top of the viewport during scrolling. Empty buckets are omitted.
*   **iOS Implementation Guidelines (`SwiftUI` `Section(header:)`):**
    *   Use `LazyVStack(pinnedViews: [.pinnedHeaderViews])` with `Section(header: ...)` so section headers stick to the top navigation bar dynamically.
    *   Hide any date bucket section where `rides.isEmpty == true`.

### D. Inline Interactive Filters
*   **Problem Solved:** Top-level redundant time pills ("All Time", "Past 7 Days") conflicted with date grouping.
*   **Android Implementation:**
    *   Removed redundant time-range pills.
    *   Added compact inline filter chips for **Sync Status** (`All`, `Synced`, `Unsynced`) and **Distance Filter** (`All`, `> 5 km`, `> 20 km`, `> 50 km`).

---

## 2. Smart Cloud Synchronization Architecture

### A. Dual-Mode Synchronization Separation (`syncPeriodic` vs `syncAll`)
*   **Problem Solved:** The previous sync button downloaded all rides from Firestore every time, causing unnecessary data transfer and UI freezes.
*   **Android Implementation:**
    *   **Periodic / Lazy Sync (`syncPeriodic`):** Queries only the **top 10 most recent** Firestore ride documents (`limit(10)` ordered by `startTime DESC`). Deduplicates against local database IDs (`if (localIds.contains(doc.id)) continue`) so existing rides are skipped instantly.
    *   **Manual Full Sync (`syncAll`):** Triggered explicitly by the user on the Settings screen. Synchronizes all local unsynced rides upward and fetches all remote cloud rides downward.

### B. Automated Background Scheduler (`WorkManager` / iOS `BGTaskScheduler`)
*   **Android Implementation:**
    *   Configured Jetpack `WorkManager` (`SyncWorker`) to run daily periodic background jobs when network (`CONNECTED`) and battery constraints (`NOT_LOW`) are met.
    *   Upon successful completion, `SyncWorker` records the exact timestamp (`last_sync_time`) in shared preferences (`sync_prefs`).
*   **iOS Implementation Guidelines (`BGTaskScheduler`):**
    *   Register a `BGAppRefreshTask` or `BGProcessingTask` (`com.trackme.syncPeriodic`).
    *   Configure `requiresNetworkConnectivity = true` and `requiresExternalPower = false`.
    *   Execute `syncPeriodic(limit: 10)` in the background and save the completion `Date()` to `UserDefaults.standard` under key `"last_sync_time"`.

### C. Dedicated CloudSync Action Button & Unified Timestamp
*   **UI Change:** Replaced text button with a dedicated circular `CloudSync` vector icon button that spins/animates during active synchronization.
*   **Shared Timestamp:** The **Last Synced** card on the Settings screen reads directly from the shared `"last_sync_time"` key, displaying the exact same timestamp whether updated overnight by the background scheduler or manually by tapping the `CloudSync` button.

---

## 3. Global Multi-Language Localization

*   **Languages Supported:** English (`en`), Spanish (`es`), French (`fr`), German (`de`), Hindi (`hi`), Japanese (`ja`), Chinese (`zh`).
*   **Cross-Platform Requirement:**
    *   All UI labels, date bucket headers, unit formatting (`km` vs `mi`), and error dialogs must support localized strings.
    *   iOS should maintain an `AppStrings` or `Localizable.xcstrings` catalog matching these 7 supported locales.

---

## 4. Live Ride Sharing & Emergency SOS Beacon

*   **Live Ride Sharing:** Users can generate and share a secure, real-time tracking link directly from the Home Screen before or during an active ride.
*   **Emergency SOS Beacon:** Integrated safety broadcast feature allowing riders to trigger an immediate SOS alert with live GPS coordinates to pre-configured emergency contacts.

---

## Cross-Platform Parity Checklist for iOS Engineering

| Module | Feature | Specification Reference | Status |
| :--- | :--- | :--- | :--- |
| **Ride History** | High-Density Compact Cards | Thumbnail `80x60pt`, minimal vertical padding (`6-8pt`) | Pending iOS |
| **Ride History** | Vector Route Preview | Render route line using local `Path` coordinates (No Static Map API) | Pending iOS |
| **Ride History** | Pinned Section Headers | `LazyVStack(pinnedViews: [.pinnedHeaderViews])` for date buckets | Pending iOS |
| **Ride History** | Inline Filter Chips | Sync Status (`All/Synced/Unsynced`) + Distance threshold filter | Pending iOS |
| **Sync Engine** | `syncPeriodic` Deduplication | Fetch top 10 cloud rides; skip local DB hits (`continue`) | Pending iOS |
| **Sync Engine** | Background Auto-Sync | `BGTaskScheduler` daily periodic sync updating `last_sync_time` | Pending iOS |
| **Settings UI** | CloudSync Icon Button | Replace text sync button with circular animated cloud sync icon | Pending iOS |
| **Localization** | 7-Language Support | `en`, `es`, `fr`, `de`, `hi`, `ja`, `zh` localized string catalogs | Pending iOS |
