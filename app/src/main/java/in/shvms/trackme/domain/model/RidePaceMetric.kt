package `in`.shvms.trackme.domain.model

/**
 * Whether a persona's effort is read as pace or as speed.
 *
 * On foot, pace is the metric: "5:30 /km" is how a walk or a run is described, planned and
 * compared, and km/h is not. On wheels it inverts — nobody describes a drive in minutes per
 * kilometre.
 *
 * This lived as an inline `selectedPersona == RidePersona.WALK` check in the tracking HUD, which
 * meant two things went wrong at once: running showed speed even though it is the persona that
 * cares most about pace, and ride detail showed speed for everything because it never had the
 * check at all. One property, read everywhere, is what stops the surfaces disagreeing about the
 * same ride.
 *
 * [RidePersona.AUTO] resolves to speed: it covers whatever the classifier has not decided yet, and
 * a number the app is unsure about is better shown in the unit that stays sensible across all of
 * them.
 */
val RidePersona.usesPace: Boolean
    get() = this == RidePersona.WALK || this == RidePersona.RUN
