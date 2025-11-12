package com.kotopogoda.uploader.core.data.model

data class DeletionStats(
    val totalCount: Int,
    val totalSizeBytes: Long,
    val chunkCount: Int = 1
)

fun DeletionStats.freedSizeFormatted(): String {
    val kilo = 1024.0
    if (totalSizeBytes <= 0) return "~0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(totalSizeBytes.toDouble()) / Math.log10(kilo)).toInt().coerceIn(0, units.lastIndex)
    val value = totalSizeBytes / Math.pow(kilo, digitGroups.toDouble())
    return "~${"%.0f".format(value)} ${units[digitGroups]}"
}
