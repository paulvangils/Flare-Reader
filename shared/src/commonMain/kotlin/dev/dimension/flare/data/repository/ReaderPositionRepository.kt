package dev.dimension.flare.data.repository

import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.ReaderTimelinePosition
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

public interface ReaderPositionStore {
    public suspend fun getPosition(timelineId: String): ReaderTimelinePosition?

    public suspend fun savePosition(
        timelineId: String,
        itemKey: String,
        scrollOffset: Int,
    )

    public suspend fun clearPosition(timelineId: String)
}

@Single
public class ReaderPositionRepository internal constructor(
    private val appDataStore: AppDataStore,
) : ReaderPositionStore {
    override suspend fun getPosition(timelineId: String): ReaderTimelinePosition? {
        val data = appDataStore.readerPositionStore.data.first()
        if (data.version != CURRENT_READER_POSITION_VERSION) {
            return null
        }
        return data.positions.firstOrNull { it.timelineId == timelineId }
    }

    override suspend fun savePosition(
        timelineId: String,
        itemKey: String,
        scrollOffset: Int,
    ) {
        appDataStore.readerPositionStore.updateData { data ->
            val updated =
                ReaderTimelinePosition(
                    timelineId = timelineId,
                    itemKey = itemKey,
                    scrollOffset = scrollOffset.coerceAtLeast(0),
                )
            data.copy(
                version = CURRENT_READER_POSITION_VERSION,
                positions =
                    data.positions
                        .filterNot { it.timelineId == timelineId } + updated,
            )
        }
    }

    override suspend fun clearPosition(timelineId: String) {
        appDataStore.readerPositionStore.updateData { data ->
            data.copy(
                positions = data.positions.filterNot { it.timelineId == timelineId },
            )
        }
    }
}


private const val CURRENT_READER_POSITION_VERSION = 2
