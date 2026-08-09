package `in`.shvms.trackme.ui.onboarding

import android.content.Context

/**
 * Whether the first-run walkthrough should be shown, and — separately — whether the old Start Ride
 * hint pill should still appear.
 *
 * A single `onboarding_completed` boolean cannot express this. It conflates two different people:
 * someone who finished the tour, and someone upgrading from 1.7.0 who was never offered one. Both
 * would read as "don't show the tour", but only the first has been taught the press-and-hold
 * gesture — so suppressing the pill for both strands exactly the users who still need it.
 */
enum class OnboardingState(val stored: String) {
    /** Fresh install that has not seen the walkthrough yet. */
    PENDING("pending"),

    /** Walkthrough finished or skipped forward. The pill is redundant and stays hidden. */
    DONE("done"),

    /** Upgraded from a build with no walkthrough. Never show it; leave the pill exactly as it was. */
    LEGACY("legacy");

    companion object {
        fun fromStored(stored: String?): OnboardingState? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * Decides the state the first time 1.7.1 runs, from signals that are true before any of the app's
 * own initialisation has had a chance to write to preferences.
 *
 * @param stored the persisted value, if this has already been resolved once
 * @param hasExistingPreferences whether `trackme_prefs` already held anything
 * @param wasUpdated whether the package has ever been updated (`firstInstallTime != lastUpdateTime`)
 *
 * Both signals are checked because each has a hole on its own. An upgrader who never opened
 * Settings can have empty preferences; a fresh install that arrives as an update — Play restoring
 * an app to a new device — reports as updated. Requiring *both* to look fresh keeps the tour away
 * from anyone who might already know the app, which is the safer way to be wrong.
 */
fun resolveOnboardingState(
    stored: String?,
    hasExistingPreferences: Boolean,
    wasUpdated: Boolean,
): OnboardingState {
    OnboardingState.fromStored(stored)?.let { return it }
    return if (hasExistingPreferences || wasUpdated) OnboardingState.LEGACY else OnboardingState.PENDING
}

/**
 * Whether the legacy Start Ride hint pill should render.
 *
 * Only for [OnboardingState.LEGACY]: a fresh install learns the gesture from the walkthrough, and
 * showing both would say the same thing twice.
 */
fun shouldShowStartRideHint(state: OnboardingState, hintAlreadySeen: Boolean): Boolean =
    state == OnboardingState.LEGACY && !hintAlreadySeen

/**
 * Reads and persists the state, once.
 *
 * **[resolve] must be the first thing `TrackMeApp.onCreate` does.** `SosStateCleanup.clearOnce`
 * runs near the top of that method and unconditionally commits a flag into the same preference
 * file — so anything checking "were there already preferences?" after it has run sees a non-empty
 * file on a brand-new install, classifies it [OnboardingState.LEGACY], and the walkthrough is
 * never shown to anyone. Resolving first is what makes the emptiness check mean what it says.
 */
object OnboardingGate {
    private const val PREFS = "trackme_prefs"
    private const val KEY = "onboarding_state"

    fun resolve(context: Context): OnboardingState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        if (stored != null) {
            OnboardingState.fromStored(stored)?.let { return it }
        }

        val hadPreferences = prefs.all.keys.any { it != KEY }
        val wasUpdated = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime != info.lastUpdateTime
        }.getOrDefault(false)

        val resolved = resolveOnboardingState(stored, hadPreferences, wasUpdated)
        // commit(), not apply(): the very next statements in onCreate write to this same file, and
        // a process death before an async flush would re-resolve against dirtied preferences.
        prefs.edit().putString(KEY, resolved.stored).commit()
        return resolved
    }

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, OnboardingState.DONE.stored).apply()
    }
}
