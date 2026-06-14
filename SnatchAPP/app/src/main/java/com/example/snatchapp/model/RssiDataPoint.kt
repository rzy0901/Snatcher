package com.example.snatchapp.model

data class RssiDataPoint(
    val timestamp: Long,
    val rssi: Int,
    val phoneDirection: Float?,
    val userPosition: Pair<Float, Float>?,
    val stepCount: Int?,
    val macAddress: String,
    val targetDirection: Float = -1f
)