package `in`.shvms.trackme.domain.model

/**
 * [displayName] is a stable, non-localized identifier kept for logging and existing call sites.
 *
 * User-facing surfaces must render the persona through `AppStrings.personaLabel(persona)`, keyed by
 * [labelKey] — never [displayName], which is an English enum label that reads as "BikeDrive" /
 * "CarDrive" to users and is never translated.
 */
enum class RidePersona(val displayName: String, val labelKey: String) {
    AUTO("Auto", "personaAuto"),
    WALK("Walk", "personaWalk"),
    RUN("Run", "personaRun"),
    CYCLING("Cycling", "personaCycling"),
    BIKE_DRIVE("BikeDrive", "personaBikeDrive"),
    CAR_DRIVE("CarDrive", "personaCarDrive")
}
