import org.ojalgo.optimisation.ExpressionsBasedModel
import org.ojalgo.optimisation.Variable
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

// declare model
val model = ExpressionsBasedModel()

val funcId = AtomicInteger(0)
val variableId = AtomicInteger(0)
fun variable() = Variable(variableId.incrementAndGet().toString().let { "Variable$it" }).apply(model::addVariable)
fun addExpression() = funcId.incrementAndGet().let { "Func$it"}.let { model.addExpression(it) }





data class Block(val dateTimeRange: ClosedRange<LocalDateTime>) {

    val timeRange = dateTimeRange.let { it.start.toLocalTime()..it.endInclusive.toLocalTime() }

    val cumulativeState = variable().lower(0).upper(1)

    val slots by lazy {
        Slot.all.filter { it.block == this }
    }

    companion object {

        // Operating blocks
        val all by lazy {
            generateSequence(operatingDates.start.atStartOfDay()) {
                it.plusMinutes(15).takeIf { it.plusMinutes(15) <= operatingDates.endInclusive.atTime(23,59) }
            }.map { Block(it..it.plusMinutes(15)) }
             .toList()
        }
    }
}


data class ScheduledClass(val id: Int,
                          val name: String,
                          val hoursLength: Double,
                          val repetitions: Int,
                          val repetitionGapDays: Int = 2) {

    val repetitionGapSlots = repetitionGapDays * 24 * 4

    val slotsNeeded = (hoursLength * 4).toInt()

    val slots by lazy {
        Slot.all.asSequence().filter { it.session == this }.toList()
    }

    val start get() = slots.asSequence().first { it.occupied.value.toInt() == 1 }.block.dateTimeRange.start
    val end get() = start.plusMinutes((hoursLength * 60.0).toLong())

    val daysOfWeek get() = (0..(repetitions-1)).asSequence().map { start.dayOfWeek.plus(it.toLong() * repetitionGapDays) }.sorted()

    fun addConstraints() {

        //block out exceptions
        slots.asSequence()
                .filter { os -> breaks.any { os.block.timeRange.start in it } || os.block.timeRange.start !in operatingDay }
                .forEach {
                    // b = 0, where b is occupation state of slot
                    // this means it should never be occupied
                    it.occupied.lower(0).upper(0)
                }

        //sum of all boolean states for this session must be 1
        addExpression().level(1).apply {
            slots.forEach {
                set(it.occupied, 1)
            }
        }

        //handle contiguous states
        slots.rollingRecurrences(slotsNeeded = slotsNeeded, gapSize = repetitionGapSlots, recurrencesNeeded = repetitions)
                .forEach { batch ->
                    val flattenedBatch = batch.flatMap { it }
                    val first = flattenedBatch.first()

                    addExpression().upper(0).apply {
                        flattenedBatch.asSequence().flatMap { it.block.slots.asSequence() }
                                .forEach {
                                    set(it.occupied, 1)
                                }

                        set(first.block.cumulativeState, -1)
                    }
                }

        //prevent scheduling past end of window
        slots.takeLast((repetitionGapSlots * (repetitions - 1)).let { if (it > 0) it - 1 else it }).forEach {
            it.occupied.lower(0).upper(0)
        }
    }

    companion object {
        val all by lazy { scheduledClasses }
    }
}



data class Slot(val block: Block, val session: ScheduledClass) {
    val occupied = variable().binary()

    companion object {

        val all by lazy {
            Block.all.asSequence().flatMap { b ->
                ScheduledClass.all.asSequence().map { Slot(b,it) }
            }.toList()
        }
    }
}


fun applyConstraints() {
    ScheduledClass.all.forEach { it.addConstraints() }
}

fun <T> List<T>.rollingBatches(batchSize: Int) = (0..size).asSequence().map { i ->
    subList(i, (i + batchSize).let { if (it > size) size else it })
}.filter { it.size == batchSize }

fun <T> List<T>.rollingRecurrences(slotsNeeded: Int, gapSize: Int, recurrencesNeeded: Int) =
        (0..size).asSequence().map { i ->
            (1..recurrencesNeeded).asSequence().map { (it - 1) * gapSize  }
                    .filter { it + i < size}
                    .map { r ->
                        subList(i + r, (i + r + slotsNeeded).let { if (it > size) size else it })
                    }.filter { it.size == slotsNeeded }
                    .toList()
        }.filter { it.size == recurrencesNeeded }