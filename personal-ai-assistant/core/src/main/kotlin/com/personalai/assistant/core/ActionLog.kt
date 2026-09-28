package com.personalai.assistant.core

enum class ActionStatus { SUCCEEDED, FAILED, DECLINED_BY_USER, REJECTED }

/** One entry in the user-visible activity history (spec §26). */
data class ActionLogEntry(
    val timestampMillis: Long,
    val toolName: String,
    val summary: String,
    val status: ActionStatus,
    val result: String,
)

fun interface ActionLogger {
    suspend fun record(entry: ActionLogEntry)
}
