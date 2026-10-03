package nic.drugrepo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CountdownTest {
    @Test
    fun countdownTicksDownAndFinishes() = runBlocking {
        val ticks = mutableListOf<Long>()
        var finished = false
        val job = Countdown.start(
            scope = this,
            seconds = 3,
            onTick = { ticks.add(it) },
            onFinished = { finished = true }
        )
        job.join()
        assertEquals(listOf(3L, 2L, 1L), ticks)
        assertEquals(true, finished)
    }
}
