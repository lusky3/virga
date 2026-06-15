package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the entity <-> domain mappers in `Mappers.kt`. The shapes mirror
 * each other field-for-field, so the key risks are (1) a field silently dropped or
 * crossed, and (2) the SyncTask.toEntity createdAt special-case. Tests assert full
 * field fidelity (round-trip where bidirectional) rather than spot-checking.
 */
class MappersTest {

    // --- SyncTaskEntity <-> SyncTask round-trip ---

    @Test fun `SyncTaskEntity toDomain copies every field`() {
        val entity = SyncTaskEntity(
            id = 5L,
            name = "Photos",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Backups/Photos",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = 30,
            scheduleDaysMask = 0b0010101,
            scheduleHour = 14,
            scheduleMinute = 45,
            filters = "+ *.jpg\n- *.tmp",
            minSize = "100k",
            maxSize = "500M",
            minAge = "1d",
            maxAge = "365d",
            bwLimitWifi = "10M",
            bwLimitMetered = "1M",
            transfers = 6,
            checkers = 12,
            bufferSize = "32M",
            deleteExtraneous = true,
            deleteSource = true,
            wifiOnly = false,
            requiresCharging = true,
            enabled = false,
            createdAtEpochMs = 1_700_000_000_000L,
            checksum = true,
            backupDir = "/backup",
            maxDelete = 50,
            extraConfig = "TrackRenames=true",
        )

        val domain = entity.toDomain()

        assertThat(domain).isEqualTo(
            SyncTask(
                id = 5L,
                name = "Photos",
                sourcePath = "/sdcard/DCIM",
                remoteName = "gdrive",
                remotePath = "Backups/Photos",
                direction = SyncDirection.UPLOAD,
                intervalMinutes = 30,
                scheduleDaysMask = 0b0010101,
                scheduleHour = 14,
                scheduleMinute = 45,
                filters = "+ *.jpg\n- *.tmp",
                minSize = "100k",
                maxSize = "500M",
                minAge = "1d",
                maxAge = "365d",
                bwLimitWifi = "10M",
                bwLimitMetered = "1M",
                transfers = 6,
                checkers = 12,
                bufferSize = "32M",
                deleteExtraneous = true,
                deleteSource = true,
                wifiOnly = false,
                requiresCharging = true,
                enabled = false,
                createdAtEpochMs = 1_700_000_000_000L,
                checksum = true,
                backupDir = "/backup",
                maxDelete = 50,
                extraConfig = "TrackRenames=true",
            ),
        )
    }

    @Test fun `size and age fields survive SyncTask round-trip through toEntity and back`() {
        val original = SyncTask(
            id = 20L,
            name = "SizeAge",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 123L,
            minSize = "10M",
            maxSize = "2G",
            minAge = "30d",
            maxAge = "1y",
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.minSize).isEqualTo("10M")
        assertThat(roundTripped.maxSize).isEqualTo("2G")
        assertThat(roundTripped.minAge).isEqualTo("30d")
        assertThat(roundTripped.maxAge).isEqualTo("1y")
    }

    @Test fun `deleteSource true survives SyncTask round-trip through toEntity and back`() {
        val original = SyncTask(
            id = 10L,
            name = "Move",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 555L,
            deleteSource = true,
        )

        assertThat(original.toEntity().toDomain().deleteSource).isTrue()
    }

    @Test fun `SyncTask round-trips through toEntity and back for an existing task`() {
        val original = SyncTask(
            id = 9L,
            name = "Docs",
            sourcePath = "/docs",
            remoteName = "s3",
            remotePath = "docs",
            direction = SyncDirection.BISYNC,
            intervalMinutes = null,
            createdAtEpochMs = 123L,
            backupDir = null,
            maxDelete = null,
        )

        val roundTripped = original.toEntity().toDomain()

        // id != 0 and createdAtEpochMs != 0 -> the timestamp must be preserved verbatim.
        assertThat(roundTripped).isEqualTo(original)
    }

    @Test fun `toEntity stamps createdAt for a brand-new task`() {
        val newTask = SyncTask(
            id = 0L,
            name = "New",
            sourcePath = "/a",
            remoteName = "r",
            remotePath = "b",
            direction = SyncDirection.DOWNLOAD,
            intervalMinutes = 15,
            createdAtEpochMs = 0L,
        )

        val entity = newTask.toEntity()

        // id == 0 && createdAt == 0 -> a creation timestamp is stamped (now).
        assertThat(entity.createdAtEpochMs).isGreaterThan(0L)
    }

    @Test fun `toEntity preserves a provided createdAt even for an unsaved task`() {
        // id == 0 but createdAt already set (e.g. a duplicated draft) -> keep the value.
        val draft = SyncTask(
            id = 0L,
            name = "Draft",
            sourcePath = "/a",
            remoteName = "r",
            remotePath = "b",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 777L,
        )

        assertThat(draft.toEntity().createdAtEpochMs).isEqualTo(777L)
    }

    @Test fun `toEntity keeps stored createdAt for an existing task created at epoch zero`() {
        // id != 0 -> never re-stamp, even if the stored value happens to be 0.
        val existing = SyncTask(
            id = 3L,
            name = "Existing",
            sourcePath = "/a",
            remoteName = "r",
            remotePath = "b",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 0L,
        )

        assertThat(existing.toEntity().createdAtEpochMs).isEqualTo(0L)
    }

    // --- SyncRunEntity toDomain ---

    @Test fun `SyncRunEntity toDomain copies every field including nullable end and log`() {
        val entity = SyncRunEntity(
            id = 2L,
            taskId = 7L,
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            status = SyncStatus.SUCCESS,
            filesTransferred = 12,
            bytesTransferred = 4096L,
            errorCount = 1,
            errorMessage = "partial",
            logPath = "/logs/2.txt",
        )

        val domain = entity.toDomain()

        assertThat(domain.id).isEqualTo(2L)
        assertThat(domain.taskId).isEqualTo(7L)
        assertThat(domain.startedAtEpochMs).isEqualTo(1000L)
        assertThat(domain.endedAtEpochMs).isEqualTo(2000L)
        assertThat(domain.status).isEqualTo(SyncStatus.SUCCESS)
        assertThat(domain.filesTransferred).isEqualTo(12)
        assertThat(domain.bytesTransferred).isEqualTo(4096L)
        assertThat(domain.errorCount).isEqualTo(1)
        assertThat(domain.errorMessage).isEqualTo("partial")
        assertThat(domain.logPath).isEqualTo("/logs/2.txt")
    }

    @Test fun `SyncRunEntity toDomain preserves nulls for an in-flight run`() {
        val entity = SyncRunEntity(
            id = 1L,
            taskId = 1L,
            startedAtEpochMs = 500L,
            status = SyncStatus.RUNNING,
        )

        val domain = entity.toDomain()

        assertThat(domain.endedAtEpochMs).isNull()
        assertThat(domain.errorMessage).isNull()
        assertThat(domain.logPath).isNull()
        assertThat(domain.status).isEqualTo(SyncStatus.RUNNING)
    }

    // --- ConflictEntity toDomain ---

    @Test fun `ConflictEntity toDomain copies every field`() {
        val entity = ConflictEntity(
            id = 4L,
            taskId = 8L,
            remoteName = "gdrive",
            basePath = "Docs/report.txt",
            variant1Path = "Docs/report.txt.conflict1",
            variant2Path = "Docs/report.txt.conflict2",
            variant1Size = 100L,
            variant2Size = 200L,
            detectedAtEpochMs = 9999L,
            resolved = true,
        )

        val domain = entity.toDomain()

        assertThat(domain.id).isEqualTo(4L)
        assertThat(domain.taskId).isEqualTo(8L)
        assertThat(domain.remoteName).isEqualTo("gdrive")
        assertThat(domain.basePath).isEqualTo("Docs/report.txt")
        assertThat(domain.variant1Path).isEqualTo("Docs/report.txt.conflict1")
        assertThat(domain.variant2Path).isEqualTo("Docs/report.txt.conflict2")
        assertThat(domain.variant1Size).isEqualTo(100L)
        assertThat(domain.variant2Size).isEqualTo(200L)
        assertThat(domain.detectedAtEpochMs).isEqualTo(9999L)
        assertThat(domain.resolved).isTrue()
    }
}
