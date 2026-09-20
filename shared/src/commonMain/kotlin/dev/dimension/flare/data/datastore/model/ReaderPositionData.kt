package dev.dimension.flare.data.datastore.model

import kotlinx.serialization.Serializable

@Serializable
public data class ReaderPositionData(
    val positions: List<ReaderTimelinePosition> = emptyList(),
)

@Serializable
public data class ReaderTimelinePosition(
    val timelineId: String,
    val itemKey: String,
    val scrollOffset: Int = 0,
)
