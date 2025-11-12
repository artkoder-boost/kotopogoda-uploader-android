package com.kotopogoda.uploader.core.data.model

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletionStatsTest {

    @Test
    fun `форматирование 1024 bytes возвращает ~1 KB`() {
        val stats = DeletionStats(
            totalCount = 1,
            totalSizeBytes = 1024L
        )

        val formatted = stats.freedSizeFormatted()

        assertEquals("~1 KB", formatted)
    }

    @Test
    fun `форматирование 1048576 bytes возвращает ~1 MB`() {
        val stats = DeletionStats(
            totalCount = 1,
            totalSizeBytes = 1048576L
        )

        val formatted = stats.freedSizeFormatted()

        assertEquals("~1 MB", formatted)
    }

    @Test
    fun `форматирование 0 bytes возвращает ~0 B`() {
        val stats = DeletionStats(
            totalCount = 0,
            totalSizeBytes = 0L
        )

        val formatted = stats.freedSizeFormatted()

        assertEquals("~0 B", formatted)
    }

    @Test
    fun `форматирование отрицательного значения возвращает ~0 B`() {
        val stats = DeletionStats(
            totalCount = 0,
            totalSizeBytes = -100L
        )

        val formatted = stats.freedSizeFormatted()

        assertEquals("~0 B", formatted)
    }

    @Test
    fun `форматирование больших значений GB`() {
        val stats = DeletionStats(
            totalCount = 100,
            totalSizeBytes = 2147483648L // 2 GB
        )

        val formatted = stats.freedSizeFormatted()

        assertEquals("~2 GB", formatted)
    }

    @Test
    fun `форматирование значений в bytes`() {
        val stats = DeletionStats(
            totalCount = 10,
            totalSizeBytes = 512L
        )

        val formatted = stats.freedSizeFormatted()

        assertEquals("~512 B", formatted)
    }

    @Test
    fun `создание с параметром по умолчанию chunkCount`() {
        val stats = DeletionStats(
            totalCount = 5,
            totalSizeBytes = 1024L
        )

        assertEquals(1, stats.chunkCount)
    }

    @Test
    fun `создание с явным chunkCount`() {
        val stats = DeletionStats(
            totalCount = 10,
            totalSizeBytes = 2048L,
            chunkCount = 3
        )

        assertEquals(3, stats.chunkCount)
    }
}
