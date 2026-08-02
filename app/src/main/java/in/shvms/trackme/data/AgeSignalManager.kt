package `in`.shvms.trackme.data

import android.app.Activity
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import `in`.shvms.trackme.analytics.AnalyticsManager
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AgeSignalCategory(val value: String) {
    UNKNOWN("unknown"),
    ADULT("adult"),
    MINOR("minor")
}

enum class AgeSignalDecision(val value: String) {
    ALLOWED("allowed"),
    BLOCKED("blocked")
}

data class AgeSignalOutcome(
    val category: AgeSignalCategory,
    val decision: AgeSignalDecision,
    val checkedAt: String
)

/**
 * One-shot Play Age Signals check. The persisted outcome is the compliance record of truth;
 * PostHog is only a consent-gated aggregate signal. Unknown/unavailable signals fail open.
 */
class AgeSignalManager(context: Context) {
    private companion object {
        const val PREFS = "trackme_prefs"
        const val CATEGORY_KEY = "age_signal_category"
        const val DECISION_KEY = "age_signal_decision"
        const val CHECKED_AT_KEY = "age_signal_checked_at"
        const val REQUEST_TIMEOUT_MS = 5_000L
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val manager: AgeSignalsManager = AgeSignalsManagerFactory.create(context.applicationContext)
    private val checkMutex = Mutex()
    private val _decision = MutableStateFlow(readDecision())

    fun decision(): StateFlow<AgeSignalDecision?> = _decision.asStateFlow()

    fun hasCheckedBefore(): Boolean = preferences.getString(DECISION_KEY, null) != null

    suspend fun checkAndPersist(activity: Activity): AgeSignalOutcome = checkMutex.withLock {
        readPersistedOutcome()?.let {
            _decision.value = it.decision
            return@withLock it
        }

        val category = requestCategory(activity)
        val decision = if (category == AgeSignalCategory.MINOR) {
            AgeSignalDecision.BLOCKED
        } else {
            AgeSignalDecision.ALLOWED
        }
        val outcome = AgeSignalOutcome(category, decision, Instant.now().toString())
        preferences.edit()
            .putString(CATEGORY_KEY, category.value)
            .putString(DECISION_KEY, decision.value)
            .putString(CHECKED_AT_KEY, outcome.checkedAt)
            .apply()
        _decision.value = decision
        AnalyticsManager.trackAgeSignalChecked(
            category = category.value,
            decision = decision.value
        )
        outcome
    }

    /** Pure mapping seam for tests and for keeping platform/API details out of the UI. */
    internal fun categoryForBounds(ageLower: Int?): AgeSignalCategory = ageSignalCategoryForBounds(ageLower)

    private suspend fun requestCategory(activity: Activity): AgeSignalCategory {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val accessResult = manager.requestAgeSignalsAccess(
                    AgeSignalsAccessRequest.builder().setActivity(activity).build()
                ).awaitTask()
                if (accessResult.ageSignalsStatus() != AgeSignalsStatus.SHARED) {
                    return@runCatching AgeSignalCategory.UNKNOWN
                }
                val result = manager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitTask()
                categoryForBounds(result.ageLower())
            }.getOrElse { AgeSignalCategory.UNKNOWN }
        } ?: AgeSignalCategory.UNKNOWN
    }

    private fun readDecision(): AgeSignalDecision? = when (preferences.getString(DECISION_KEY, null)) {
        AgeSignalDecision.ALLOWED.value -> AgeSignalDecision.ALLOWED
        AgeSignalDecision.BLOCKED.value -> AgeSignalDecision.BLOCKED
        else -> null
    }

    private fun readPersistedOutcome(): AgeSignalOutcome? {
        val category = preferences.getString(CATEGORY_KEY, null)?.let { value ->
            AgeSignalCategory.entries.firstOrNull { it.value == value }
        } ?: return null
        val decision = readDecision() ?: return null
        val checkedAt = preferences.getString(CHECKED_AT_KEY, null) ?: return null
        return AgeSignalOutcome(category, decision, checkedAt)
    }
}

internal fun ageSignalCategoryForBounds(ageLower: Int?): AgeSignalCategory = when {
    ageLower == null -> AgeSignalCategory.UNKNOWN
    ageLower >= 18 -> AgeSignalCategory.ADULT
    else -> AgeSignalCategory.MINOR
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
