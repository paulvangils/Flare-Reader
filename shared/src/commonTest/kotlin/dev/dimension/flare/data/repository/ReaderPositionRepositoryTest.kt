package dev.dimension.flare.data.repository

import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.ReaderPositionData
import dev.dimension.flare.data.datastore.model.ReaderTimelinePosition
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
    private lateinit var appDataStore: AppDataStore
    private lateinit var repository: ReaderPositionRepository

    @BeforeTest
    fun setup() {
        root = createTestRootPath()
        val fileStorage = OkioFileStorage(createTestFileSystem(), root)
        appDataStore = AppDataStore(fileStorage)
        repository =
            ReaderPositionRepository(
                appDataStore = appDataStore,
            )
    }

    @AfterTest
    fun tearDown() {
        deleteTestRootPath(root)
    }

    @Test
    fun positionsAreStoredIndependentlyPerTimeline() =
        runTest {
            repository.savePosition("home", "post-10", 17)
            repository.savePosition("account", "post-3", 4)

            assertEquals("post-10", repository.getPosition("home")?.itemKey)
            assertEquals(17, repository.getPosition("home")?.scrollOffset)
            assertEquals("post-3", repository.getPosition("account")?.itemKey)
            assertEquals(4, repository.getPosition("account")?.scrollOffset)
        }

    @Test
    fun savingAgainReplacesOnlyThatTimelinePosition() =
        runTest {
            repository.savePosition("home", "post-10", 17)
            repository.savePosition("account", "post-3", 4)

            repository.savePosition("home", "post-12", 9)

            assertEquals("post-12", repository.getPosition("home")?.itemKey)
            assertEquals(9, repository.getPosition("home")?.scrollOffset)
            assertEquals("post-3", repository.getPosition("account")?.itemKey)
        }

    @Test
    fun unreliableV2ViewportPositionIsIgnoredAfterUpgrade() =
        runTest {
            appDataStore.readerPositionStore.updateData {
                ReaderPositionData(
                    version = 0,
                    positions =
                        listOf(
                            ReaderTimelinePosition(
                                timelineId = "home",
                                itemKey = "days-old-post",
                                scrollOffset = 11,
                            ),
                        ),
                )
            }

            assertNull(repository.getPosition("home"))

            repository.savePosition("home", "fresh-boundary", 3)
            assertEquals("fresh-boundary", repository.getPosition("home")?.itemKey)
        }

    @Test
    fun positionCanBeCleared() =
        runTest {
            repository.savePosition("home", "post-10", 17)
            repository.clearPosition("home")

            assertNull(repository.getPosition("home"))
        }
}
