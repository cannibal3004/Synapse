package com.aiassistant.domain.usecase

import java.util.*
import java.util.regex.Pattern

object CronScheduler {

    fun nextRun(cronExpression: String, afterMs: Long): Long {
        val parts = cronExpression.trim().split(Regex("\\s+"))
        if (parts.size != 5) {
            throw IllegalArgumentException("Invalid cron expression: $cronExpression")
        }

        val minuteExpr = parts[0]
        val hourExpr = parts[1]
        val dayExpr = parts[2]
        val monthExpr = parts[3]
        val dowExpr = parts[4]

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = afterMs
        calendar.add(Calendar.MINUTE, 1)

        val maxIterations = 60 * 24 * 7 * 52
        for (i in 0 until maxIterations) {
            if (matchesCron(calendar, minuteExpr, hourExpr, dayExpr, monthExpr, dowExpr)) {
                return calendar.timeInMillis
            }
            calendar.add(Calendar.MINUTE, 1)
        }

        throw RuntimeException("Could not find next run time within one year")
    }

    private fun matchesCron(
        calendar: Calendar,
        minuteExpr: String,
        hourExpr: String,
        dayExpr: String,
        monthExpr: String,
        dowExpr: String
    ): Boolean {
        return matchesField(calendar.get(Calendar.MINUTE), minuteExpr, 0, 59) &&
                matchesField(calendar.get(Calendar.HOUR_OF_DAY), hourExpr, 0, 23) &&
                matchesDay(calendar, dayExpr, dowExpr) &&
                matchesField(calendar.get(Calendar.MONTH) + 1, monthExpr, 1, 12)
    }

    private fun matchesDay(calendar: Calendar, dayExpr: String, dowExpr: String): Boolean {
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        val dayMatch = matchesField(dayOfMonth, dayExpr, 1, 31)
        val dowMatch = matchesField(dayOfWeek, dowExpr, 1, 7)

        return if (dayExpr == "*" && dowExpr == "*") {
            true
        } else if (dayExpr == "*") {
            dowMatch
        } else if (dowExpr == "*") {
            dayMatch
        } else {
            dayMatch || dowMatch
        }
    }

    private fun matchesField(value: Int, expr: String, min: Int, max: Int): Boolean {
        when {
            expr == "*" -> return true
            expr.contains("/") -> {
                val parts = expr.split("/")
                val start = if (parts[0] == "*") min else parts[0].toInt()
                val step = parts[1].toInt()
                return value in start..max && (value - start) % step == 0
            }
            expr.contains(",") -> {
                val values = expr.split(",").map { it.trim().toInt() }
                return value in values
            }
            expr.contains("-") -> {
                val parts = expr.split("-")
                val start = parts[0].toInt()
                val end = parts[1].toInt()
                return value in start..end
            }
            else -> {
                return value == expr.toInt()
            }
        }
        return false
    }
}
