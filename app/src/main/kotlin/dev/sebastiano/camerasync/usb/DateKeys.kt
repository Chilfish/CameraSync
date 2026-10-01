package dev.sebastiano.camerasync.usb

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared thread-safe formatter for the "yyyy-MM-dd" capture-date key used to bucket photos (BY_DATE
 * grouping). Replaces per-photo `SimpleDateFormat` allocations — `SimpleDateFormat` is neither
 * thread-safe nor cheap to construct, and it was being created for every comparison (R25).
 */
private val DATE_KEY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Formats [epochMillis] as the "yyyy-MM-dd" key in the device time zone. */
internal fun dateKey(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DATE_KEY_FORMATTER)
