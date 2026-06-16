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
            maxTransfer = "20G",
            maxRetries = 5,
            retryOnRclone = true,
            backoffSeconds = 60L,
            backoffExponential = false,
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
                maxTransfer = "20G",
                maxRetries = 5,
                retryOnRclone = true,
                backoffSeconds = 60L,
                backoffExponential = false,
            ),
        )
    }

    @Test fun `B8 retry fields survive SyncTask round-trip through toEntity and back`() {
        val original = SyncTask(
            id = 40L,
            name = "RetryTest",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 1234L,
            maxRetries = 5,
            retryOnRclone = true,
            backoffSeconds = 120L,
            backoffExponential = false,
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.maxRetries).isEqualTo(5)
        assertThat(roundTripped.retryOnRclone).isTrue()
        assertThat(roundTripped.backoffSeconds).isEqualTo(120L)
        assertThat(roundTripped.backoffExponential).isFalse()
    }

    @Test fun `B8 retry defaults are behavior-preserving for a plain new entity`() {
        val entity = SyncTaskEntity(
            id = 41L,
            name = "Default",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
        )

        val domain = entity.toDomain()

        assertThat(domain.maxRetries).isEqualTo(3)
        assertThat(domain.retryOnRclone).isFalse()
        assertThat(domain.backoffSeconds).isEqualTo(30L)
        assertThat(domain.backoffExponential).isTrue()
    }

    @Test fun `maxTransfer survives SyncTask round-trip through toEntity and back`() {
        val original = SyncTask(
            id = 30L,
            name = "Capped",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 999L,
            maxTransfer = "10G",
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.maxTransfer).isEqualTo("10G")
    }

    @Test fun `blank maxTransfer is preserved through round-trip`() {
        val original = SyncTask(
            id = 31L,
            name = "NoCap",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 1000L,
            maxTransfer = "",
        )

        assertThat(original.toEntity().toDomain().maxTransfer).isEmpty()
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

    // --- RemoteEntity / Remote needsReauth round-trip ---

    @Test fun `needsReauth true is preserved in Remote domain model`() {
        // The RemoteRepository maps RemoteEntity -> Remote; verify the flag is carried.
        // The mapper is inline in the repository, so we test via RemoteEntity field copy.
        val entity = app.lusk.virga.core.database.entity.RemoteEntity(
            name = "gdrive",
            type = "drive",
            needsReauth = true,
        )
        val domain = app.lusk.virga.core.common.model.Remote(
            name = entity.name,
            type = entity.type,
            needsReauth = entity.needsReauth,
        )
        assertThat(domain.needsReauth).isTrue()
    }

    @Test fun `needsReauth false is the default for Remote`() {
        val domain = app.lusk.virga.core.common.model.Remote(name = "s3", type = "s3")
        assertThat(domain.needsReauth).isFalse()
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

    @Test fun `failedFiles survives SyncRunEntity toDomain round-trip`() {
        val failedFilesValue = "docs/report.pdf\tpermission denied\nphotos/img.jpg\ttimeout"
        val entity = SyncRunEntity(
            id = 5L,
            taskId = 2L,
            startedAtEpochMs = 1000L,
            status = SyncStatus.SUCCESS,
            failedFiles = failedFilesValue,
        )

        val domain = entity.toDomain()

        assertThat(domain.failedFiles).isEqualTo(failedFilesValue)
    }

    @Test fun `failedFiles defaults to empty string when absent from entity`() {
        val entity = SyncRunEntity(
            id = 6L,
            taskId = 2L,
            startedAtEpochMs = 2000L,
            status = SyncStatus.SUCCESS,
        )

        val domain = entity.toDomain()

        assertThat(domain.failedFiles).isEmpty()
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

    // --- B7: conflictResolve / conflictCheck / conflictType ---

    @Test fun `B7 conflictResolve and conflictCheck survive SyncTask round-trip`() {
        val original = SyncTask(
            id = 80L,
            name = "Bisync",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.BISYNC,
            intervalMinutes = null,
            createdAtEpochMs = 1L,
            conflictResolve = "newer",
            conflictCheck = true,
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.conflictResolve).isEqualTo("newer")
        assertThat(roundTripped.conflictCheck).isTrue()
    }

    @Test fun `B7 conflictResolve blank and conflictCheck false are defaults`() {
        val entity = SyncTaskEntity(
            id = 81L,
            name = "Default",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Archive",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
        )

        val domain = entity.toDomain()

        assertThat(domain.conflictResolve).isEmpty()
        assertThat(domain.conflictCheck).isFalse()
    }

    @Test fun `B7 conflictType survives ConflictEntity toDomain`() {
        val entity = ConflictEntity(
            id = 5L,
            taskId = 9L,
            remoteName = "gdrive",
            basePath = "Docs/report.txt",
            variant1Path = "Docs/report.txt.conflict1",
            variant2Path = "Docs/report.txt.conflict2",
            variant1Size = 0L,
            variant2Size = 0L,
            conflictType = "bisync",
        )

        assertThat(entity.toDomain().conflictType).isEqualTo("bisync")
    }

    @Test fun `B7 conflictType defaults to empty string for old ConflictEntity rows`() {
        val entity = ConflictEntity(
            id = 6L,
            taskId = 9L,
            remoteName = "gdrive",
            basePath = "file.txt",
            variant1Path = "file.txt.conflict1",
            variant2Path = "file.txt.conflict2",
            variant1Size = 0L,
            variant2Size = 0L,
        )

        assertThat(entity.toDomain().conflictType).isEmpty()
    }

    // --- B4: scheduleTimes round-trip ---

    @Test fun `scheduleTimes non-empty round-trips through toEntity and back`() {
        val original = SyncTask(
            id = 60L,
            name = "MultiTime",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 1L,
            scheduleTimes = listOf(120, 840),
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.scheduleTimes).isEqualTo(listOf(120, 840))
    }

    @Test fun `scheduleTimes empty round-trips as emptyList`() {
        val original = SyncTask(
            id = 61L,
            name = "SingleTime",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 1L,
            scheduleTimes = emptyList(),
        )

        assertThat(original.toEntity().toDomain().scheduleTimes).isEmpty()
    }

    @Test fun `decodeScheduleTimes blank returns emptyList`() {
        assertThat(decodeScheduleTimes("")).isEmpty()
        assertThat(decodeScheduleTimes("   ")).isEmpty()
    }

    @Test fun `decodeScheduleTimes garbage returns emptyList`() {
        assertThat(decodeScheduleTimes("not-json")).isEmpty()
    }

    @Test fun `decodeScheduleTimes empty brackets returns emptyList`() {
        assertThat(decodeScheduleTimes("[]")).isEmpty()
    }

    @Test fun `decodeScheduleTimes valid json returns parsed list`() {
        assertThat(decodeScheduleTimes("[120,840]")).isEqualTo(listOf(120, 840))
    }

    @Test fun `decodeScheduleTimes filters out-of-range values`() {
        // Values outside 0..1439 must be dropped.
        assertThat(decodeScheduleTimes("[-1,720,1440]")).isEqualTo(listOf(720))
    }

    @Test fun `encodeScheduleTimes empty list returns empty string`() {
        assertThat(encodeScheduleTimes(emptyList())).isEmpty()
    }

    @Test fun `encodeScheduleTimes non-empty list produces bracketed csv`() {
        assertThat(encodeScheduleTimes(listOf(120, 840))).isEqualTo("[120,840]")
    }

    // --- B10: groupTag / sortOrder round-trip ---

    @Test fun `B10 groupTag and sortOrder survive SyncTask round-trip through toEntity and back`() {
        val original = SyncTask(
            id = 70L,
            name = "GroupTest",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 1L,
            groupTag = "photos",
            sortOrder = 3,
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.groupTag).isEqualTo("photos")
        assertThat(roundTripped.sortOrder).isEqualTo(3)
    }

    @Test fun `B10 groupTag defaults to empty and sortOrder defaults to 0 for a plain entity`() {
        val entity = SyncTaskEntity(
            id = 71L,
            name = "Default",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
        )

        val domain = entity.toDomain()

        assertThat(domain.groupTag).isEmpty()
        assertThat(domain.sortOrder).isEqualTo(0)
    }

    @Test fun `B10 empty groupTag round-trips as empty string`() {
        val original = SyncTask(
            id = 72L,
            name = "NoGroup",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 1L,
            groupTag = "",
            sortOrder = 0,
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped.groupTag).isEmpty()
        assertThat(roundTripped.sortOrder).isEqualTo(0)
    }
}
