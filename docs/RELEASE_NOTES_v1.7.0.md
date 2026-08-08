# TrackMe Android v1.7.0 Release Notes 🚵‍♂️🔒

**Release Date:** August 9, 2026  
**Version:** 1.7.0 (Build 24)

---

## 🌟 End-to-End Encrypted (E2EE) Group Rides

TrackMe v1.7.0 introduces **E2EE Group Rides**, bringing privacy-first, zero-trust real-time location sharing and presence to group cycling, running, and outdoor adventures.

### Key Highlights

1. **Zero-Trust Client-Side Encryption (AES-GCM-256):**
   - All group location coordinates and presence heartbeats are encrypted on-device before transmission.
   - The 128-bit symmetrical key `#k=...` lives exclusively in the URI fragment and is never transmitted to backend servers or logged in telemetry.

2. **Real-Time Group Presence & Roster:**
   - Members see real-time presence indicators (Active, Paused, Left) and live distance metrics on the map and HUD.
   - Low-latency encrypted relay powered by Redis TTL-backed serverless endpoints.

3. **Leader Controls:**
   - Session leaders can set and update ride destination and start time for all participants.
   - Leader authority to remove members or safely conclude group sessions.

4. **Seamless Universal Join Links:**
   - Shareable invite links (`https://trackme.shvms.in/g/<code>#k=<key>`) instantly open in TrackMe via Android Digital Asset Links with custom-intent fallbacks.

5. **Location Tracking Hardening:**
   - Dual-provider location architecture fallback (Google Play Services `FusedLocationProviderClient` with native Android `LocationManager` fallback).
   - Resolves location availability on legacy/non-GMS devices.
