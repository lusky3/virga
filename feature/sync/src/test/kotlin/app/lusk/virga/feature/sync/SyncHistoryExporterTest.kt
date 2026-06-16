package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SyncHistoryExporterTest {

    private fun row(id: Long, taskName: String, status: SyncStatus = SyncStatus.SUCCESS) = SyncRunRow(
        run = SyncRun(
            id = id, taskId = 1L, status = status,
            startedAtEpochMs = 1_000_000L, endedAtEpochMs = 1_060_000L,
            filesTransferred = 5, bytesTransferred = 1024L,
            errorCount = 0, remoteName = "gdrive", direction = "UPLOAD", durationMs = 60_000L,
        ),
        taskName = taskName,
    )

    @Test fun `toCsv empty list returns header only`() {
        val csv = SyncHistoryExporter.toCsv(emptyList())
        assertThat(csv).startsWith("id,taskName,status")
        assertThat(csv.lines()).hasSize(1)
    }

    @Test fun `toCsv produces header plus one data row`() {
        val csv = SyncHistoryExporter.toCsv(listOf(row(1L, "Photos")))
        val lines = csv.lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).startsWith("id,taskName,status")
        assertThat(lines[1]).contains("Photos")
    }

    @Test fun `toCsv quotes field containing comma`() {
        val csv = SyncHistoryExporter.toCsv(listOf(row(1L, "Photos, Videos")))
        assertThat(csv).contains("\"Photos, Videos\"")
    }

    @Test fun `toCsv escapes embedded double-quote`() {
        val csv = SyncHistoryExporter.toCsv(listOf(row(1L, "Say \"hello\"")))
        assertThat(csv).contains("\"Say \"\"hello\"\"\"")
    }

    @Test fun `toJson empty list returns empty array`() {
        val json = SyncHistoryExporter.toJson(emptyList())
        assertThat(json.trim()).isEqualTo("[]")
    }

    @Test fun `toJson single row contains expected keys`() {
        val json = SyncHistoryExporter.toJson(listOf(row(42L, "Docs")))
        assertThat(json).contains("\"id\":42")
        assertThat(json).contains("\"taskName\":\"Docs\"")
        assertThat(json).contains("\"status\":\"SUCCESS\"")
    }

    @Test fun `toJson escapes double-quote in string field`() {
        val json = SyncHistoryExporter.toJson(listOf(row(1L, "Say \"hi\"")))
        assertThat(json).contains("Say \\\"hi\\\"")
    }
}
