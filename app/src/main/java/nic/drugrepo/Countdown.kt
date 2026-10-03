package nic.drugrepo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Countdown {
    fun start(
        scope: CoroutineScope,
        seconds: Long,
        onTick: (Long) -> Unit,
        onFinished: () -> Unit
    ): Job {
        return scope.launch {
            var remaining = seconds
            while (remaining > 0) {
                onTick(remaining)
                delay(1000)
                remaining--
            }
            onFinished()
        }
    }
}
