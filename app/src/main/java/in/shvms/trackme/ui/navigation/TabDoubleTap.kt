package `in`.shvms.trackme.ui.navigation

/**
 * TASK-226: recognises a double-tap on a bottom-navigation item.
 *
 * Deliberately *retrospective*. The obvious implementation waits out a timeout on every tap to see
 * whether a second one arrives, which taxes the common single tap with a delay it never had -- and
 * a single tap is what almost every tap is. Here the first tap acts immediately and unchanged; the
 * second one, if it comes inside the window, does the extra work on top. Nothing is ever deferred.
 *
 * Held as a pure value so the rule is testable without a NavController.
 */
data class TabDoubleTapDetector(
    private val windowMillis: Long,
    private val lastRoute: String? = null,
    private val lastTapAtMillis: Long = 0L,
) {
    data class Result(val detector: TabDoubleTapDetector, val isDoubleTap: Boolean)

    fun tap(route: String, nowMillis: Long): Result {
        val isDoubleTap = route == lastRoute &&
            lastTapAtMillis > 0L &&
            nowMillis - lastTapAtMillis <= windowMillis
        return Result(
            detector = copy(
                lastRoute = route,
                // A recognised pair is consumed: three taps in a second are one double-tap and one
                // single, not two double-taps.
                lastTapAtMillis = if (isDoubleTap) 0L else nowMillis,
            ),
            isDoubleTap = isDoubleTap,
        )
    }
}
