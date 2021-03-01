import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun quote(str: String): String {
    return str.replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\"", "\\\"")
}

fun parseDateString(dateStr: String): Long {
    val date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    return date.toEpochSecond(LocalTime.MIDNIGHT, ZoneOffset.UTC)
}

/**
 * Parse a datetime string of format:
 * 2016-12-30 14:30:00
 * and return the offset seconds from UNIX epoch
 */
fun parseDateTimeString(dateTimeStr: String): Long {
    try {
        val dateTime = LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return dateTime.toEpochSecond(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        return parseDateString(dateTimeStr)
    }
}
