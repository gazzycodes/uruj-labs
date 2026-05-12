package com.uruj.domain

import kotlinx.serialization.Serializable

@Serializable
data class RideSession(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val label: String? = null,
    val samplesFileName: String,
)
