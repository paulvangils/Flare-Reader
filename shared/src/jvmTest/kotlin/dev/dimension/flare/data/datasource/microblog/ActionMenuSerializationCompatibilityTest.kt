package dev.dimension.flare.data.datasource.microblog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionMenuSerializationCompatibilityTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun itemFieldOrderRemainsCompatibleWithReaderV35Cache() {
        val descriptor = ActionMenu.Item.serializer().descriptor
        val fieldNames =
            (0 until descriptor.elementsCount).map(descriptor::getElementName)

        assertEquals(
            listOf(
                "updateKey",
                "icon",
                "text",
                "count",
                "color",
                "clickEvent",
                "actionFamily",
                "enabled",
            ),
            fieldNames,
        )
    }
}
