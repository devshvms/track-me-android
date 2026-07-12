package `in`.shvms.trackme.domain.model

enum class RidePersona(val displayName: String, val emoji: String) {
    AUTO("Auto", "✨"),
    WALK("Walk", "🚶"),
    RUN("Run", "🏃"),
    CYCLING("Cycling", "🚴"),
    BIKE_DRIVE("BikeDrive", "🏍️"),
    CAR_DRIVE("CarDrive", "🚗")
}
