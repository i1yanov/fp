package net.family.multiki.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** «Что дальше?» after a tale ends: countdown and the title that will start. */
data class NextUpState(
    val visible: Boolean = false,
    val secondsLeft: Int = 0,
    val random: Boolean = false,
    val sectionId: String = "",
    val itemId: String = "",
    val title: String = "",
)

/** Sleep timer: pause at [endAt] (epoch ms), or after the current tale. */
data class SleepState(
    val endAt: Long = 0L,
    val afterTale: Boolean = false,
) {
    val active: Boolean get() = endAt > 0 || afterTale
}

/** Commands from the screens to the background audio service. */
interface AudioCommands {
    fun nextNow()
    fun stopNext()
    fun chooseNext(random: Boolean)
    fun setSleep(minutes: Int?, afterTale: Boolean)
}

/** Shared between [AudioService] and the UI (same process). */
object AudioExtras {
    val nextUp = MutableStateFlow(NextUpState())
    val sleep = MutableStateFlow(SleepState())
    @Volatile var commands: AudioCommands? = null

    val nextUpFlow: StateFlow<NextUpState> get() = nextUp
    val sleepFlow: StateFlow<SleepState> get() = sleep
}
