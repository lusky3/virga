package app.lusk.virga.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lusk.virga.core.database.entity.RemoteEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In-memory Room tests for [app.lusk.virga.core.database.dao.RemoteDao].
 *
 * Key constraint: [replaceAll] must carry over a pending [RemoteEntity.needsReauth] flag
 * so frequent refresh cycles cannot silently reset an auth-failure marker.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteDaoTest {

    private lateinit var db: VirgaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VirgaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun replaceAll_preserves_needsReauth_flag_across_refresh() = runTest {
        val dao = db.remoteDao()
        // Seed a remote with needsReauth=true (simulates worker flagging an auth failure).
        dao.upsert(RemoteEntity(name = "gdrive", type = "drive", needsReauth = true))

        // replaceAll simulates the refresh path: new entities arrive without the flag.
        dao.replaceAll(listOf(RemoteEntity(name = "gdrive", type = "drive", needsReauth = false)))

        val stored = dao.observeAll().first()
        assertThat(stored).hasSize(1)
        assertThat(stored[0].needsReauth).isTrue()
    }

    @Test
    fun replaceAll_does_not_set_flag_for_new_remote_without_prior_row() = runTest {
        val dao = db.remoteDao()
        // No prior row — new remote arrives clean.
        dao.replaceAll(listOf(RemoteEntity(name = "s3", type = "s3")))

        val stored = dao.observeAll().first()
        assertThat(stored[0].needsReauth).isFalse()
    }

    @Test
    fun setNeedsReauth_updates_existing_row() = runTest {
        val dao = db.remoteDao()
        dao.upsert(RemoteEntity(name = "gdrive", type = "drive"))

        dao.setNeedsReauth("gdrive", true)

        assertThat(dao.observeAll().first()[0].needsReauth).isTrue()
    }

    @Test
    fun setNeedsReauth_can_clear_the_flag() = runTest {
        val dao = db.remoteDao()
        dao.upsert(RemoteEntity(name = "gdrive", type = "drive", needsReauth = true))

        dao.setNeedsReauth("gdrive", false)

        assertThat(dao.observeAll().first()[0].needsReauth).isFalse()
    }

    @Test
    fun replaceAll_clears_removed_remotes_and_keeps_remaining_flag() = runTest {
        val dao = db.remoteDao()
        dao.upsert(RemoteEntity(name = "gdrive", type = "drive", needsReauth = true))
        dao.upsert(RemoteEntity(name = "s3", type = "s3", needsReauth = false))

        // Refresh: s3 is gone, gdrive arrives with needsReauth=false from rclone listing.
        dao.replaceAll(listOf(RemoteEntity(name = "gdrive", type = "drive", needsReauth = false)))

        val stored = dao.observeAll().first()
        assertThat(stored).hasSize(1)
        assertThat(stored[0].name).isEqualTo("gdrive")
        assertThat(stored[0].needsReauth).isTrue()
    }
}
