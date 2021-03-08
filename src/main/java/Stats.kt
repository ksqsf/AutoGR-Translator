import java.time.Duration
import java.time.Instant
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import java.util.Timer

class Timer {
    companion object {
        val startTime = mutableMapOf<String, Instant>()
        val elapseTime = mutableMapOf<String, Duration>()
        val totalTime = mutableMapOf<String, Duration>()

        fun start(tag: String) {
            startTime[tag] = Instant.now()
        }

        fun end(tag: String) {
            elapseTime[tag] = Duration.between(startTime[tag]!!, Instant.now())
            totalTime.putIfAbsent(tag, Duration.ZERO)
            totalTime[tag] = totalTime[tag]!! + elapseTime[tag]!!
        }

        fun report() {
            for ((tag, total) in totalTime) {
                println("$tag: ${total.toMillis()} ms")
            }
        }
    }
}
