package com.kizitonwose.calendarview.model

import com.kizitonwose.calendarview.utils.next
import kotlinx.coroutines.Job
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

internal data class MonthConfig(
    internal val outDateStyle: OutDateStyle,
    internal val inDateStyle: InDateStyle,
    internal val maxRowCount: Int,
    internal val startMonth: YearMonth,
    internal val endMonth: YearMonth,
    internal val firstDayOfWeek: DayOfWeek,
    internal val weekdays: Array<DayOfWeek>,
    internal val hasBoundaries: Boolean,
    internal val job: Job
) {

    internal val months: List<CalendarMonth> = run {
        return@run if (hasBoundaries) {
            generateBoundedMonths(
                startMonth,
                endMonth,
                firstDayOfWeek,
                weekdays,
                maxRowCount,
                inDateStyle,
                outDateStyle,
                job
            )
        } else {
            generateUnboundedMonths(
                startMonth,
                endMonth,
                firstDayOfWeek,
                weekdays,
                maxRowCount,
                inDateStyle,
                outDateStyle,
                job
            )
        }
    }

    internal companion object {

        private val uninterruptedJob = Job()

        /**
         * A [YearMonth] will have multiple [CalendarMonth] instances if the [maxRowCount] is
         * less than 6. Each [CalendarMonth] will hold just enough [CalendarDay] instances(weekDays)
         * to fit in the [maxRowCount].
         */
        fun generateBoundedMonths(
            startMonth: YearMonth,
            endMonth: YearMonth,
            firstDayOfWeek: DayOfWeek,
            weekdays: Array<DayOfWeek>,
            maxRowCount: Int,
            inDateStyle: InDateStyle,
            outDateStyle: OutDateStyle,
            job: Job = uninterruptedJob
        ): List<CalendarMonth> {
            val months = mutableListOf<CalendarMonth>()
            var currentMonth = startMonth
            while (currentMonth <= endMonth && job.isActive) {
                val generateInDates = when (inDateStyle) {
                    InDateStyle.ALL_MONTHS -> true
                    InDateStyle.FIRST_MONTH -> currentMonth == startMonth
                    InDateStyle.NONE -> false
                }

                val weekDaysGroup =
                    generateWeekDays(
                        currentMonth,
                        firstDayOfWeek,
                        weekdays,
                        generateInDates,
                        outDateStyle
                    )

                // Group rows by maxRowCount into CalendarMonth classes.
                val calendarMonths = mutableListOf<CalendarMonth>()
                val numberOfSameMonth = weekDaysGroup.size roundDiv maxRowCount
                var indexInSameMonth = 0
                calendarMonths.addAll(weekDaysGroup.chunked(maxRowCount) { monthDays ->
                    // Use monthDays.toList() to create a copy of the ephemeral list.
                    CalendarMonth(
                        currentMonth,
                        monthDays.toList(),
                        indexInSameMonth++,
                        numberOfSameMonth
                    )
                })

                months.addAll(calendarMonths)
                if (currentMonth != endMonth) currentMonth = currentMonth.next else break
            }

            return months
        }

        internal fun generateUnboundedMonths(
            startMonth: YearMonth,
            endMonth: YearMonth,
            firstDayOfWeek: DayOfWeek,
            weekdays: Array<DayOfWeek>,
            maxRowCount: Int,
            inDateStyle: InDateStyle,
            outDateStyle: OutDateStyle,
            job: Job = uninterruptedJob
        ): List<CalendarMonth> {

            // Generate a flat list of all days in the given month range
            val allDays = mutableListOf<CalendarDay>()
            var currentMonth = startMonth
            while (currentMonth <= endMonth && job.isActive) {

                // If inDates are enabled with boundaries disabled,
                // we show them on the first month only.
                val generateInDates = when (inDateStyle) {
                    InDateStyle.FIRST_MONTH, InDateStyle.ALL_MONTHS -> currentMonth == startMonth
                    InDateStyle.NONE -> false
                }

                allDays.addAll(
                    // We don't generate outDates for any month, they are added manually down below.
                    // This is because if outDates are enabled with boundaries disabled, we show them
                    // on the last month only.
                    generateWeekDays(
                        currentMonth,
                        firstDayOfWeek,
                        weekdays,
                        generateInDates,
                        OutDateStyle.NONE
                    ).flatten()
                )
                if (currentMonth != endMonth) currentMonth = currentMonth.next else break
            }

            // Regroup data into number of weekdays. Use toList() to create a copy of the ephemeral list.
            val allDaysGroup = allDays.chunked(weekdays.size).toList()

            val calendarMonths = mutableListOf<CalendarMonth>()
            val calMonthsCount = allDaysGroup.size roundDiv maxRowCount
            allDaysGroup.chunked(maxRowCount) { ephemeralMonthWeeks ->
                val monthWeeks = ephemeralMonthWeeks.toMutableList()

                // Add the outDates for the last row if needed.
                if (monthWeeks.last().size < weekdays.size && outDateStyle == OutDateStyle.END_OF_ROW || outDateStyle == OutDateStyle.END_OF_GRID) {
                    val lastWeek = monthWeeks.last()
                    val lastDay = lastWeek.last()
                    val outDates = (1..7 - lastWeek.size)
                        .map {
                            CalendarDay(lastDay.date.plusDays(it.toLong()), DayOwner.NEXT_MONTH)
                        }
                        .filter { weekdays.contains(it.date.dayOfWeek) }
                        .take(weekdays.size - lastWeek.size)
                    monthWeeks[monthWeeks.lastIndex] = lastWeek + outDates
                }

                // Add the outDates needed to make the number of rows in this index match the desired maxRowCount.
                while (monthWeeks.size < maxRowCount && outDateStyle == OutDateStyle.END_OF_GRID ||
                    // This will be true when we add the first inDates and the last week row in the CalendarMonth is not filled up.
                    monthWeeks.size == maxRowCount && monthWeeks.last().size < weekdays.size && outDateStyle == OutDateStyle.END_OF_GRID
                ) {
                    // Since boundaries are disabled hence months will overflow, if we have maxRowCount
                    // set to 6 and the last index has only one row left with some missing dates in it,
                    // e.g the last row has only one day in it, if we attempt to fill the grid(up to maxRowCount)
                    // with outDates and the next month does not provide enough dates to fill the grid,
                    // we get more outDates from the following month.

                    /*  MON   TUE   WED   THU   FRI   SAT   SUN

                        30    31    01    02    03    04    05  => First outDates start here (month + 1)

                        06    07    08    09    10    11    12

                        13    14    15    16    17    18    19

                        20    21    22    23    24    25    26

                        27    28    29    30    01    02    03  => Second outDates start here (month + 2)

                        04    05    06    07    08    09    10  */

                    val lastDay = monthWeeks.last().last()

                    val nextRowDates = (1..7).map {
                        CalendarDay(lastDay.date.plusDays(it.toLong()), DayOwner.NEXT_MONTH)
                    }.filter { weekdays.contains(it.date.dayOfWeek) }

                    if (monthWeeks.last().size < weekdays.size) {
                        // Update the last week to 7 days instead of adding a new row.
                        // Handles the case when we've added all the first inDates and the
                        // last week row in the CalendarMonth is not filled up to 7 days.
                        monthWeeks[monthWeeks.lastIndex] =
                            (monthWeeks.last() + nextRowDates).take(weekdays.size)
                    } else {
                        monthWeeks.add(nextRowDates)
                    }
                }

                calendarMonths.add(
                    // numberOfSameMonth is the total number of all months and
                    // indexInSameMonth is basically this item's index in the entire month list.
                    CalendarMonth(startMonth, monthWeeks, calendarMonths.size, calMonthsCount)
                )
            }

            return calendarMonths
        }

        /**
         * Generates the necessary number of weeks for a [YearMonth].
         */
        internal fun generateWeekDays(
            yearMonth: YearMonth,
            firstDayOfWeek: DayOfWeek,
            weekdays: Array<DayOfWeek>,
            generateInDates: Boolean,
            outDateStyle: OutDateStyle
        ): List<List<CalendarDay>> {
            val year = yearMonth.year
            val month = yearMonth.monthValue

            val thisMonthDays = (1..yearMonth.lengthOfMonth())
                .map { CalendarDay(LocalDate.of(year, month, it), DayOwner.THIS_MONTH) }
                .filter { weekdays.contains(it.date.dayOfWeek) }

            val weekDaysGroup = if (generateInDates) {
                // Group days by week of month so we can add the in dates if necessary.
                val weekOfMonthField = WeekFields.of(firstDayOfWeek, 1).weekOfMonth()
                val groupByWeekOfMonth =
                    thisMonthDays.groupBy { it.date.get(weekOfMonthField) }.values.toMutableList()

                // Add in-dates if necessary
                val firstWeek = groupByWeekOfMonth.first()
                if (firstWeek.size < weekdays.size) {
                    val previousMonth = yearMonth.minusMonths(1)
                    val inDates = (1..previousMonth.lengthOfMonth()).toList()
                        .takeLast(7 - firstWeek.size).map {
                            CalendarDay(
                                LocalDate.of(previousMonth.year, previousMonth.month, it),
                                DayOwner.PREVIOUS_MONTH
                            )
                        }
                        .filter { weekdays.contains(it.date.dayOfWeek) }
                        .takeLast(weekdays.size - firstWeek.size)
                    groupByWeekOfMonth[0] = inDates + firstWeek
                }
                groupByWeekOfMonth
            } else {
                // Group days by 7, first day shown on the month will be day 1.
                // Use toMutableList() to create a copy of the ephemeral list.
                thisMonthDays.chunked(weekdays.size).toMutableList()
            }

            if (outDateStyle == OutDateStyle.END_OF_ROW || outDateStyle == OutDateStyle.END_OF_GRID) {
                // Add out-dates for the last row.
                if (weekDaysGroup.last().size < weekdays.size) {
                    val lastWeek = weekDaysGroup.last()
                    val lastDay = lastWeek.last()
                    val outDates = (1..7 - lastWeek.size)
                        .map {
                            CalendarDay(lastDay.date.plusDays(it.toLong()), DayOwner.NEXT_MONTH)
                        }
                        .filter { weekdays.contains(it.date.dayOfWeek) }
                        .take(weekdays.size - lastWeek.size)
                    weekDaysGroup[weekDaysGroup.lastIndex] = lastWeek + outDates
                }

                // Add more rows to form a 6 x 7 grid
                if (outDateStyle == OutDateStyle.END_OF_GRID) {
                    while (weekDaysGroup.size < 6) {
                        val lastDay = weekDaysGroup.last().last()
                        val nextRowDates = (1..7)
                            .map {
                                CalendarDay(lastDay.date.plusDays(it.toLong()), DayOwner.NEXT_MONTH)
                            }
                            .filter { weekdays.contains(it.date.dayOfWeek) }
                        weekDaysGroup.add(nextRowDates)
                    }
                }
            }

            return weekDaysGroup
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MonthConfig

        if (outDateStyle != other.outDateStyle) return false
        if (inDateStyle != other.inDateStyle) return false
        if (maxRowCount != other.maxRowCount) return false
        if (startMonth != other.startMonth) return false
        if (endMonth != other.endMonth) return false
        if (firstDayOfWeek != other.firstDayOfWeek) return false
        if (!weekdays.contentEquals(other.weekdays)) return false
        if (hasBoundaries != other.hasBoundaries) return false
        if (job != other.job) return false
        if (months != other.months) return false

        return true
    }

    override fun hashCode(): Int {
        var result = outDateStyle.hashCode()
        result = 31 * result + inDateStyle.hashCode()
        result = 31 * result + maxRowCount
        result = 31 * result + startMonth.hashCode()
        result = 31 * result + endMonth.hashCode()
        result = 31 * result + firstDayOfWeek.hashCode()
        result = 31 * result + weekdays.contentHashCode()
        result = 31 * result + hasBoundaries.hashCode()
        result = 31 * result + job.hashCode()
        result = 31 * result + months.hashCode()
        return result
    }
}

/**
 * We want the remainder to be added as the division result.
 * E.g: 5/2 should be 3.
 */
private infix fun Int.roundDiv(other: Int): Int {
    val div = this / other
    val rem = this % other
    // Add the last value dropped from div if rem is not zero
    return if (rem == 0) div else div + 1
}
