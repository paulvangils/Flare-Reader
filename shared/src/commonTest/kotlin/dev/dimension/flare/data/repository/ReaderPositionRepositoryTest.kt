package dev.dimension.flare.data.repository

import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.deleteTestRootPath
import kotlinx.coroutines.test.runTest
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderPositionRepositoryTest {
    private lateinit var root: Path
    private lateinit var repository: ReaderPositionRepository

    @BeforeTest
    fun setup() {
        root = createTestRootPath()
        repository =
            ReaderPositionRepository(
                appDataStore =
                    AppDataStore(
                        OkioFileStorage(createTestFileSystem(), root),
                    ),
            )
    }

    @AfterTest
    fun tearDown() {
        deleteTestRootPath(root)
    }

    @Test
    fun storesLatestVisiblePositionPerTimeline() =
        runTest {
            assertNull(repository.getPosition("home"))

            repository.savePosition("home", "post-10", 17)
            repository.savePosition("other", "post-3", 4)
            repository.savePosition("home", "post-11", 6)

            assertEquals("post-11", repository.getPosition("home")?.itemKey)
            assertEquals(6, repository.getPosition("home")?.scrollOffset)
            assertEquals("post-3", repository.getPosition("other")?.itemKey)
        }
}
