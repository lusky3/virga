package app.lusk.virga.feature.sync

import java.text.DateFormat
import java.util.Date

/** Pure functions to format a list of [SyncRunRow]s as CSV or JSON. No Android deps — fully unit-testable. */
object SyncHistoryExporter {

    fun toCsv(rows: List<SyncRunRow>): String {
        val header = "id,taskName,status,startedAt,endedAt,filesTransferred,bytesTransferred,errorCount,errorMessage,remoteName,direction,durationMs"
        val body = rows.joinToString("\n") { row ->
            val r = row.run
            val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(r.startedAtEpochMs))
            val ended = r.endedAtEpochMs?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: ""
            listOf(
                r.id.toString(),
                row.taskName,
                r.status.name,
                started,
                ended,
                r.filesTransferred.toString(),
                r.bytesTransferred.toString(),
                r.errorCount.toString(),
                r.errorMessage ?: "",
                r.remoteName,
                r.direction,
                r.durationMs.toString(),
            ).joinToString(",") { field -> csvEscape(field) }
        }
        return if (rows.isEmpty()) header else "$header\n$body"
    }

    fun toJson(rows: List<SyncRunRow>): String {
        val items = rows.joinToString(",\n  ") { row ->
            val r = row.run
            buildString {
                append("{")
                append("\"id\":${r.id},")
                append("\"taskName\":${jsonString(row.taskName)},")
                append("\"status\":\"${r.status.name}\",")
                append("\"startedAtEpochMs\":${r.startedAtEpochMs},")
                append("\"endedAtEpochMs\":${r.endedAtEpochMs ?: "null"},")
                append("\"filesTransferred\":${r.filesTransferred},")
                append("\"bytesTransferred\":${r.bytesTransferred},")
                append("\"errorCount\":${r.errorCount},")
                append("\"errorMessage\":${r.errorMessage?.let { jsonString(it) } ?: "null"},")
                append("\"remoteName\":${jsonString(r.remoteName)},")
                append("\"direction\":${jsonString(r.direction)},")
                append("\"durationMs\":${r.durationMs}")
                append("}")
            }
        }
        return if (rows.isEmpty()) "[]" else "[\n  $items\n]"
    }

    private fun csvEscape(field: String): String {
        val needsQuoting = field.contains(',') || field.contains('"') || field.contains('\n')
        if (!needsQuoting) return field
        return "\"${field.replace("\"", "\"\"")}\""
    }

    private fun jsonString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
