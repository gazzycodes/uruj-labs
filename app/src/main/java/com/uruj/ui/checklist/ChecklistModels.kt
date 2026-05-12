package com.uruj.ui.checklist

enum class CheckId {
    LocationPermission,
    LocationServices,
    NotificationPermission,
    HealthConnectInstalled,
    HealthConnectPermission,
    HealthConnectRecentHr,
    BatteryOptimization,
    OpenTracksInstalled,
}

enum class CheckStatus { Pending, Pass, Warning, Fail }

data class CheckItem(
    val id: CheckId,
    val title: String,
    val description: String,
    val status: CheckStatus,
    val canFix: Boolean,
)

data class ChecklistState(
    val items: List<CheckItem> = emptyList(),
    val refreshing: Boolean = false,
) {
    /** A ride only blocks on Fails. Warnings (no recent HR, OpenTracks missing) are informational. */
    val canStartRide: Boolean
        get() = items.isNotEmpty() && items.none { it.status == CheckStatus.Fail }
}
