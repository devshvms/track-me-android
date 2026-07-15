# TrackMe Telemetry & Data Persistence Pipeline Architecture

This document serves as the canonical reference for how location telemetry, sensor fusion, activity auto-pause, **GPS signal loss handling**, and post-ride 4D data compression operate and modify data across the TrackMe application lifecycle.

> **Living Document Notice**: Keep this document updated whenever modifications are made to `TrackingService.kt`, `MotionSensorManager.kt`, `AdaptiveAutoPauseEngine.kt`, `GPSProcessor.kt`, or any Room SQLite database entity models.

---

## 1. Data Models & Entity Architecture (`AppDatabase`)

TrackMe uses two primary Room SQLite entities to persist ride sessions and point-by-point telemetry:

### A. Point-Level Telemetry (`GPSPointEntity`)
Defined in [`in.shvms.trackme.data.local.entity.GPSPointEntity`](file:///Users/shvms/anti-gravity/track-me-android/app/src/main/java/in/shvms/trackme/data/local/entity/GPSPointEntity.kt), each GPS fix is stored as an immutable row in the `gps_points` table:

```kotlin
@Entity(tableName = "gps_points")
data class GPSPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rideId: Long,        // Foreign key linking to RideEntity
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,        // Stored as 0f when stationary or auto-paused
    val timestamp: Long,     // Epoch timestamp in milliseconds (location.time)
    val isPaused: Boolean    // true when stationary, auto-paused, or manual-paused
)
```

### B. Ride Session Summary (`RideEntity`)
Defined in [`in.shvms.trackme.data.local.entity.RideEntity`](file:///Users/shvms/anti-gravity/track-me-android/app/src/main/java/in/shvms/trackme/data/local/entity/RideEntity.kt), each recording session is stored in the `rides` table:

```kotlin
@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val sourceInfo: String = "Android Device",
    ...
    @Embedded
    val postRideCalculation: PostRideCalculation? = null
)
```
Where `@Embedded val postRideCalculation: PostRideCalculation` stores `maxSpeed`, `distance`, `avgSpeed`, `pauseDuration`, `maxAcceleration`, and `rawPointCount` directly inside the `rides` row.

---

## 2. Phase 1: Ride Initialization (Live Recording Start)

When the user taps **Start Ride** in `HomeScreen.kt`:
1. `TrackingService.startForegroundService()` is invoked.
2. A new `RideEntity` row is inserted into SQLite (`rideDao.insertRide(...)`) with the current timestamp as `startTime`.
3. Hardware sensor listeners (`MotionSensorManager`) begin sampling the phone's physical linear accelerometer (`Sensor.TYPE_LINEAR_ACCELERATION`) at 50Hz to compute real-time physical stillness.
4. Android `LocationManager` / `FusedLocationProviderClient` callbacks start delivering location updates to `TrackingService.locationCallback`.

---

## 3. Phase 2: Live Telemetry Filtering, Sensor Fusion & GPS Signal Loss Handling

As every location fix arrives (`onLocationChanged`), the point flows through a 4-stage filtering and persistence pipeline in [`TrackingService.kt`](file:///Users/shvms/anti-gravity/track-me-android/app/src/main/java/in/shvms/trackme/service/TrackingService.kt):

```
[Raw Location Fix]
        │
        ▼
┌────────────────────────────────────────────────────────┐
│ 1. Accuracy & Glitch Check                             │
│    Discard if location.accuracy > 22.0m                │
└──────────────────────────────────┬─────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────┐
│ 2. Hardware IMU Sensor Fusion & Deadzone               │
│    Check motionSensorManager.isDeviceStationary()      │
│    Force speed=0f if still OR displacement < 4.0m      │
└──────────────────────────────────┬─────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────┐
│ 3. Activity Auto-Pause & Hysteresis Evaluation         │
│    Evaluate speed against Drive/Bike/Run thresholds    │
└──────────────────────────────────┬─────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────┐
│ 4. Live SQLite Insertion (`gps_points`)                │
│    Insert GPSPointEntity with accurate timestamp       │
└────────────────────────────────────────────────────────┘
```

### A. Accuracy Filter
* Fixes with horizontal `accuracy > 22.0f` meters are rejected to prevent multipath jumps from polluting the database.

### B. Hardware IMU Sensor Fusion & Deadzone
* `MotionSensorManager.isDeviceStationary()` checks if physical acceleration magnitude $E = \sqrt{a_x^2 + a_y^2 + a_z^2} < 0.18\text{ m/s}^2$.
* If stationary on a desk or vehicle, indoor GPS coordinate drift is overridden: `effectiveSpeed = 0f`, distance accumulation is suppressed, and `isPaused = true`.

### C. GPS Signal Loss Scenario (Live Tracking Behavior)
* **What constitutes GPS Signal Loss**: When the device enters a tunnel, underground parking, or deep urban canyon where no valid GPS fixes are received for $> 15\text{ seconds}$.
* **During Signal Loss**: No new `GPSPointEntity` rows are written while the signal is absent. The UI displays an active warning banner indicating the duration of signal loss.
* **Upon Signal Reacquisition**:
  * When the first valid GPS fix arrives after a gap $> 15\text{ seconds}$, `TrackingService` compares it against `lastLocation`.
  * Even if the GPS position jumped while disconnected, if the device remained physically stationary or paused during the outage (`isHardwareStill || (currentlyPaused && distance < 8.0f)`), the point is clamped to `0f` speed and marked `isPaused = true`, preventing false distance spikes.
  * Because each saved `GPSPointEntity` records its true OS timestamp (`location.time`), the exact duration of the GPS signal loss is intrinsically preserved in SQLite as a timestamp jump ($t_i - t_{i-1} > 15,000\text{ ms}$).

---

## 4. Phase 3: Post-Ride Processing & Atomic Database Overwrite

When the user taps **Stop & Save Ride**, `TrackingService.finalizeRide` calls [`DefaultGPSProcessor.processRide(...)`](file:///Users/shvms/anti-gravity/track-me-android/app/src/main/java/in/shvms/trackme/domain/processor/GPSProcessor.kt). This reads all recorded `GPSPointEntity` rows from SQLite and runs a 5-step refinement:

```
[Raw SQLite Points]
        │
        ▼
┌────────────────────────────────────────────────────────┐
│ Step A: Kinematic Outlier Removal                      │
│         Discard points requiring acceleration > 2.0G   │
└──────────────────────────────────┬─────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────┐
│ Step B: Altitude & Speed Smoothing                     │
│         5-point Simple Moving Average (SMA)            │
└──────────────────────────────────┬─────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────┐
│ Step C & D: Signal Gap Chunking & 4D RDP Compression   │
│             Partition at gaps >15s before compression  │
└──────────────────────────────────┬─────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────┐
│ Step E: Atomic SQLite Overwrite                        │
│         Replace raw points & write PostRideCalculation │
└────────────────────────────────────────────────────────┘
```

### Step A: Kinematic Outlier Removal
* Computes acceleration between successive points: $a = \frac{\Delta v}{\Delta t}$.
* Any point requiring an acceleration $> 2.0G$ ($> 19.6\text{ m/s}^2$) is discarded. Records `maxAcceleration` and `rawPointCount`.

### Step B: Altitude & Speed Smoothing
* Applies a 5-point moving average across `altitude` and `speed` to remove GPS sensor noise.

### Step C & D: GPS Signal Loss Gap Preservation & 4D RDP Compression
* **Critical Signal Loss Chunking**: Before running compression, `GPSProcessor` splits the point stream into distinct chunks wherever consecutive points have a time gap $> 15,000\text{ ms}` (`maxSpanMs = 15_000L`).
* **Why**: Running point reduction across a signal gap would interpolate straight-line points across a tunnel or hide the dropout. Partitioning guarantees that **every true GPS signal loss ($>15\text{s}$) is preserved verbatim as an exact gap in the finalized database**.
* **4D Douglas-Peucker**: Within each continuous chunk, redundant straight-line points are compressed using a 4D distance metric ($\text{lat}, \text{lng}, \text{altitude}, \text{speed}$) with $\epsilon = 2.0$.

### Step E: Atomic SQLite Finalization
1. Calculates high-precision `totalDistance`, `finalMaxSpeed`, unpaused `avgSpeed`, and `pauseDurationMs`.
2. Deletes raw points (`rideDao.deletePointsForRide(rideId)`).
3. Inserts the cleaned & compressed points (`rideDao.insertGPSPoints(compressedPoints)`).
4. Updates `RideEntity` with `@Embedded postRideCalculation`.

---

## 5. Phase 4: Visualizing GPS Signal Loss & Pauses in UI (`RideDetailScreen`)

When viewing a saved ride in [`RideDetailScreen.kt`](file:///Users/shvms/anti-gravity/track-me-android/app/src/main/java/in/shvms/trackme/ui/history/RideDetailScreen.kt):
1. **Telemetry Chart (GPS Signal Loss Stripes)**:
   * The chart iterates through `plotData`. Whenever two consecutive points have a gap $> 15,000\text{ ms}$, the canvas draws **vertical dashed red stripes** across that interval to clearly indicate where GPS signal was lost during the ride.
2. **Paused Segments**:
   * Points marked `isPaused = true` are rendered as dotted lines on the map path and telemetry charts.
3. **Finish Marker**:
   * A single checkered white-and-black finish flag icon marks the final point of the ride.
