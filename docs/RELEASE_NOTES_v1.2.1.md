# TrackMe v1.2.1 — "Ascent" Release Notes & iOS Implementation Spec

**Release Name:** TrackMe v1.2.1 (Ascent)  
**Target Platform:** Android (Released) & iOS (Parity Spec)  
**Release Theme:** Smart In-App Update Notifications, CI/CD Resilience & Security Hardening  

---

## Executive Summary

TrackMe **v1.2.1 ("Ascent")** introduces an intelligent **In-App Auto-Update System** that informs users on older versions whenever a new update or feature release is available, hardens CI/CD pipelines against malformed credential secrets, and secures API configurations.

This document serves as the official Release Notes and **iOS Parity Specification** for multi-platform development teams.

---

## 1. In-App Auto-Update Notification System

### A. Dual-Source Version Checking Architecture (`AppUpdateChecker`)
*   **Problem Solved:** Users running older versions installed via direct APK or early releases were unaware of new feature drops, UX improvements, and security updates.
*   **Android Implementation:**
    *   **Primary Source — Firestore Configuration (`config/app_release`)**: Asynchronously queries a centralized Firestore document on launch to check `latestVersionCode`, `latestVersionName`, `releaseNotes`, `updateUrl`, and `isForceUpdate`.
    *   **Fallback Source — GitHub Releases API**: If offline or unconfigured in Firestore, checks `https://api.github.com/repos/devshvms/track-me-android/releases/latest` to parse semantic release tags (`vX.Y.Z`).
    *   **Smart Dismissal Logic**: Users who tap **"Later"** are remembered via `SharedPreferences` (`dismissed_version_code` & `dismissed_timestamp`), silencing duplicate prompts for 24 hours unless `isForceUpdate = true`.
*   **iOS Implementation Guidelines (`SwiftUI` / `URLSession`):**
    *   Implement an `AppUpdateManager` class checking either `config/app_release` in Firestore or the Apple iTunes Lookup API (`https://itunes.apple.com/lookup?bundleId=in.shvms.track-me-ios`).
    *   Compare `CFBundleShortVersionString` against the remote version.

### B. Material 3 Release Notes Popup (`AppUpdateDialog`)
*   **Android Implementation:**
    *   Custom elevated dialog surface with rounded corners (`RoundedCornerShape(24.dp)`).
    *   Displays a rocket badge icon, version tag badge (`TrackMe v1.2.1`), and scrollable release notes container.
    *   **Primary Action ("Update Now")**: Immediately launches the Google Play Store or release download URL.
    *   **Secondary Action ("Later")**: Dismisses the popup (hidden automatically if `isForceUpdate == true`).
*   **iOS Implementation Guidelines:**
    *   Use a SwiftUI `.sheet()` or `.alert()` with a scrollable `ScrollView` containing the release changelog and two action buttons (`Update Now` and `Later`).

### C. Manual "Check for Updates" Action
*   **Android Implementation:**
    *   Added a prominent **"Check for Updates"** action button directly next to the app version string at the bottom of the **Settings Screen**.
    *   Provides immediate toast/visual feedback (`Checking for updates...`).

---

## 2. CI/CD Reliability & Security Hardening

*   **Robust Google Services JSON Validation**: Updated `.github/workflows/play_store_publish.yml` to automatically validate `app/google-services.json` syntax (`jq .`) and inject a valid fallback Firebase schema if CI secrets are uninitialized during test builds.
*   **Secret Scanning Protection**: Replaced synthetic placeholder API keys with non-matching strings (`dummy_api_key_for_ci_build_validation`) to prevent GitHub Secret Scanning false positives.
*   **API Key Rotation Guidelines**: Standardized procedure for restricting Google Maps and Firebase Identity Toolkit API keys (`local.properties` & GitHub Actions Secrets).

---

## 3. Versioning & Build Artifacts

| Attribute | Value |
| :--- | :--- |
| **Version Name** | `1.2.1` |
| **Version Code** | `8` |
| **Minimum SDK** | `Android 7.0 (API 24)` |
| **Target SDK** | `Android 15 (API 36)` |
