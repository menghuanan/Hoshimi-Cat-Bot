package top.bilibili.tasker

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PushStatisticsTest {
    @Test
    fun `daily push statistics should reset on date boundary and keep type breakdown`() {
        var currentDate = LocalDate.of(2026, 5, 20)
        val counter = DailyPushStatsCounter(todayProvider = { currentDate })

        counter.recordSuccess(PushStatisticType.DYNAMIC)
        counter.recordSuccess(PushStatisticType.DYNAMIC)
        counter.recordSuccess(PushStatisticType.LIVE)
        counter.recordFailure(PushStatisticType.LIVE_CLOSE)

        val firstSnapshot = counter.snapshot()

        currentDate = LocalDate.of(2026, 5, 21)
        counter.recordSuccess(PushStatisticType.LIVE_CLOSE)
        val secondSnapshot = counter.snapshot()

        assertEquals("2026-05-20", firstSnapshot.date)
        assertEquals(3, firstSnapshot.total)
        assertEquals(2, firstSnapshot.dynamic)
        assertEquals(1, firstSnapshot.live)
        assertEquals(0, firstSnapshot.liveClose)
        assertEquals(1, firstSnapshot.failed)
        assertEquals("2026-05-21", secondSnapshot.date)
        assertEquals(1, secondSnapshot.total)
        assertEquals(0, secondSnapshot.dynamic)
        assertEquals(0, secondSnapshot.live)
        assertEquals(1, secondSnapshot.liveClose)
        assertEquals(0, secondSnapshot.failed)
    }

    @Test
    fun `recent push deliveries should keep newest records and trim history to four entries`() {
        var currentDate = LocalDate.of(2026, 5, 20)
        var timestamp = 1_000L
        val counter = DailyPushStatsCounter(
            todayProvider = { currentDate },
            currentTimeMillisProvider = { timestamp },
        )

        repeat(4) { index ->
            timestamp = 1_000L + index
            counter.recordDelivery(
                type = PushStatisticType.DYNAMIC,
                success = true,
                summary = "record-$index",
                target = "onebot11:group:${index + 1}",
            )
        }

        currentDate = LocalDate.of(2026, 5, 21)
        repeat(3) { index ->
            val actualIndex = index + 4
            timestamp = 1_000L + actualIndex
            counter.recordDelivery(
                type = PushStatisticType.DYNAMIC,
                success = true,
                summary = "record-$actualIndex",
                target = "onebot11:group:${actualIndex + 1}",
            )
        }

        val snapshot = counter.snapshot()

        assertEquals(4, snapshot.recentRecords.size)
        assertEquals("record-6", snapshot.recentRecords.first().summary)
        assertEquals("onebot11:group:7", snapshot.recentRecords.first().target)
        assertEquals("record-3", snapshot.recentRecords.last().summary)
        assertEquals("onebot11:group:4", snapshot.recentRecords.last().target)
    }
}
