package app.lusk.virga.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [VirgaDatabase.MIGRATION_5_6] under a real SQLite engine: it creates a
 * v5 database, runs the migration, and lets Room validate the resulting schema
 * against the exported v6 schema (catches any DDL drift in the `app_stats`
 * CREATE TABLE). Also asserts the singleton seed row is present and zeroed.
 *
 * Runs as a Robolectric (JUnit4) unit test via the vintage engine; the exported
 * schema JSONs are wired in as test assets (see build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
class Migration5to6Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VirgaDatabase::class.java,
    )

    @Test
    fun migrate5To6_createsSeededAppStatsAndValidatesSchema() {
        helper.createDatabase(TEST_DB, 5).close()

        // runMigrationsAndValidate validates the post-migration schema matches the
        // entity-generated v6 schema — fails loudly on any column/type mismatch.
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            /* validateDroppedTables = */ true,
            VirgaDatabase.MIGRATION_5_6,
        )

        db.query("SELECT id, totalRuns, currentStreakDays, firstSyncEpochMs FROM app_stats").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)        // singleton id
            assertThat(c.getLong(1)).isEqualTo(0L)      // totalRuns seeded 0
            assertThat(c.getInt(2)).isEqualTo(0)        // currentStreakDays seeded 0
            assertThat(c.isNull(3)).isTrue()            // firstSyncEpochMs null
            assertThat(c.count).isEqualTo(1)            // exactly one seed row
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-5-6-test"
    }
}
