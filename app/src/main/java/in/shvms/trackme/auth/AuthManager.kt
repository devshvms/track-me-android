package `in`.shvms.trackme.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import `in`.shvms.trackme.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthManager {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    // Fire-and-forget scope for non-blocking side effects (D3 welcome email).
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
        }
    }

    suspend fun signInWithGoogle(activityContext: Context): Result<FirebaseUser> {
        try {
            val credentialManager = CredentialManager.create(activityContext)
            val webClientId = activityContext.getString(R.string.default_web_client_id)
            if (webClientId == "YOUR_WEB_CLIENT_ID_HERE") {
                return Result.failure(Exception("Please replace YOUR_WEB_CLIENT_ID_HERE in strings.xml with your actual Firebase Web Client ID"))
            }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(activityContext, request)
            return handleSignInResult(result)
        } catch (e: Exception) {
            Log.e("AuthManager", "Google Sign In Failed", e)
            return Result.failure(e)
        }
    }

    private suspend fun handleSignInResult(result: GetCredentialResponse): Result<FirebaseUser> {
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                
                authResult.user?.uid?.let { uid ->
                    `in`.shvms.trackme.analytics.AnalyticsManager.identifyUser(uid)
                    if (authResult.additionalUserInfo?.isNewUser == true) {
                        `in`.shvms.trackme.analytics.AnalyticsManager.trackUserSignedUp()
                        // D3: welcome email after the first successful sign-up.
                        // Fire-and-forget — a missed email must not block sign-in.
                        ioScope.launch {
                            `in`.shvms.trackme.data.remote.NotificationManager
                                .sendTransactional(`in`.shvms.trackme.data.remote.NotificationManager.EmailType.WELCOME)
                        }
                    } else {
                        `in`.shvms.trackme.analytics.AnalyticsManager.trackUserLoggedIn()
                    }
                }
                
                return Result.success(authResult.user!!)
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return Result.failure(Exception("Invalid credential type"))
    }

    fun signOut() {
        auth.signOut()
    }

    fun isSignInCancellation(error: Throwable?): Boolean {
        val name = error?.javaClass?.simpleName ?: return false
        return name.contains("Cancellation", ignoreCase = true) ||
            error.message?.contains("cancel", ignoreCase = true) == true
    }

    suspend fun deleteAccount(): Result<Unit> {
        return try {
            val user = auth.currentUser ?: throw Exception("Not signed in")
            // D3: send the delete_account email while the token is still valid —
            // it is revoked once the account is deleted. Best-effort; never blocks.
            runCatching {
                `in`.shvms.trackme.data.remote.NotificationManager
                    .sendTransactional(`in`.shvms.trackme.data.remote.NotificationManager.EmailType.DELETE_ACCOUNT)
            }
            user.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthManager", "Delete Account Failed", e)
            Result.failure(e)
        }
    }
}
