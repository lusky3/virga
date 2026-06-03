package app.lusk.virga.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lusk.virga.core.database.dao.AppStatsDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In-memory Room tests for [AppStatsDao] — the additive counters, the monotonic
 * MAX records, and the streak state machine (first run / same day / consecutive /
 * gap / out-of-order). Runs under Robolectric (JUnit4) via the vintage engine.
 */
@RunWith(RobolectricTestRunner::class)
class AppStatsDaoTest {

    private lateinit var db: VirgaDatabase
    private lateinit var dao: AppStatsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VirgaDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.appStatsDao()
    }

    @After
    fun tearDown() = db.close()

    /** Records a successful run on [day] (epoch-day), 1 file / [bytes] bytes / [durationMs]. */
    private suspend fun success(day: Long, bytes: Long = 100, durationMs: Long = 1_000, up: Long = 100) =
        dao.record(
            successDelta = 1, failDelta = 0, files = 1, bytes = bytes,
            up = up, down = 0, twoWay = 0, durationMs = durationMs,
            nowEpochMs = day * 86_400_000L, syncDayEpochDay = day, isSuccess = true,
        )

    /** Records a failed run on [day]. */
    private suspend fun failure(day: Long) =
        dao.record(
            successDelta = 0, failDelta = 1, files = 0, bytes = 0,
            up = 0, down = 0, twoWay = 0, durationMs = 500,
            nowEpochMs = day * 86_400_000L, syncDayEpochDay = day, isSuccess = false,
        )

    @Test
    fun firstSync_anchors_to_first_successful_run_not_a_failed_one() = runTest {
        failure(day = 100)
        // A failed first attempt must NOT set the "Syncing since" anchor…
        assertThat(dao.getOnce()!!.firstSyncEpochMs).isNull()
        assertThat(dao.getOnce()!!.failedRuns).isEqualTo(1)

        success(day = 101)
        // …the first SUCCESSFUL run does, and a later failure can't overwrite it.
        assertThat(dao.getOnce()!!.firstSyncEpochMs).isEqualTo(101 * 86_400_000L)
        failure(day = 102)
        assertThat(dao.getOnce()!!.firstSyncEpochMs).isEqualTo(101 * 86_400_000L)
    }

    @Test
    fun additive_counters_accumulate_and_records_take_max() = runTest {
        success(day = 100, bytes = 500, durationMs = 2_000, up = 500)
        success(day = 101, bytes = 200, durationMs = 9_000, up = 200)

        val s = dao.getOnce()!!
        assertThat(s.totalRuns).isEqualTo(2)
        assertThat(s.successfulRuns).isEqualTo(2)
        assertThat(s.totalBytesTransferred).isEqualTo(700)
        assertThat(s.bytesUploaded).isEqualTo(700)
        assertThat(s.largestRunBytes).isEqualTo(500)   // MAX(500, 200)
        assertThat(s.longestRunMillis).isEqualTo(9_000) // MAX(2000, 9000)
        assertThat(s.firstSyncEpochMs).isEqualTo(100 * 86_400_000L) // set-once
    }

    @Test
    fun streak_firstRun_isOne() = runTest {
        success(day = 100)
        val s = dao.getOnce()!!
        assertThat(s.currentStreakDays).isEqualTo(1)
        assertThat(s.longestStreakDays).isEqualTo(1)
    }

    @Test
    fun streak_sameDay_doesNotDoubleCount() = runTest {
        success(day = 100)
        success(day = 100)
        assertThat(dao.getOnce()!!.currentStreakDays).isEqualTo(1)
    }

    @Test
    fun streak_consecutiveDays_increment() = runTest {
        success(day = 100)
        success(day = 101)
        success(day = 102)
        val s = dao.getOnce()!!
        assertThat(s.currentStreakDays).isEqualTo(3)
        assertThat(s.longestStreakDays).isEqualTo(3)
    }

    @Test
    fun streak_gap_resetsButKeepsLongest() = runTest {
        success(day = 100)
        success(day = 101) // streak 2, longest 2
        success(day = 105) // gap → streak resets to 1
        val s = dao.getOnce()!!
        assertThat(s.currentStreakDays).isEqualTo(1)
        assertThat(s.longestStreakDays).isEqualTo(2)
    }

    @Test
    fun streak_outOfOrderOlderRun_doesNotCorrupt() = runTest {
        success(day = 100)
        success(day = 101) // streak 2, last day 101
        success(day = 99)  // older/out-of-order → leave streak + lastDay intact
        val s = dao.getOnce()!!
        assertThat(s.currentStreakDays).isEqualTo(2)
        assertThat(s.longestStreakDays).isEqualTo(2)
    }

    @Test
    fun reset_clearsRow_andNextRecordReseeds() = runTest {
        success(day = 100, bytes = 999)
        dao.clear()
        assertThat(dao.getOnce()).isNull()

        success(day = 200, bytes = 5)
        val s = dao.getOnce()!!
        assertThat(s.totalRuns).isEqualTo(1)
        assertThat(s.totalBytesTransferred).isEqualTo(5)
        assertThat(s.currentStreakDays).isEqualTo(1)
    }
}
