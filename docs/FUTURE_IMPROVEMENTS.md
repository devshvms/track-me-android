# TrackMe — Future Improvements & Parked Features

This document tracks technical improvements, security hardening, and product features that require coordination with external systems (such as backend servers) or are scheduled for future releases.

---

## 1. LiveShare API Token Authentication

### Description
Secure the Live Location Sharing connection between the mobile app and the live-tracking server (`https://trackme.shvms.in`).

### What Needs to Change
Currently, the mobile client communicates with the server without any authentication token or API key headers. The unique `sessionId` in the URL path is the only protection.
1. **Client-Side:** Prior to starting a tracking session, retrieving location push URLs, or stopping a session, the app must obtain a Firebase ID Token using:
   ```kotlin
   val user = FirebaseAuth.getInstance().currentUser
   user?.getIdToken(true)?.addOnCompleteListener { task ->
       if (task.isSuccessful) {
           val token = task.result.token
           // Set in Authorization header: "Bearer $token"
       }
   }
   ```
2. **Server-Side (Required for Alignment):** The backend server must intercept all `/api/track/*` requests, verify the bearer ID token against Firebase Auth Admin SDK, and ensure the `uid` of the token matches the owner of the `sessionId`.

### Status
*   **Parked:** Awaiting backend server updates to verify and enforce authorization tokens.
