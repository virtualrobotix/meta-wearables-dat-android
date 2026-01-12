/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton debug logger that writes to app's internal storage
 * With rate limiting to avoid flooding logs
 */
object DebugLogger {
    private const val TAG = "DebugLogger"
    private const val LOG_FILE_NAME = "debug.log"
    private const val MAX_LOG_LINES = 200 // Keep only last N lines
    private const val RATE_LIMIT_MS = 500L // Min interval between same-location logs
    
    private var logFile: File? = null
    private var isInitialized = false
    private val lastLogTimes = mutableMapOf<String, Long>()
    private var logCount = 0
    
    /**
     * Initialize the logger with app context
     */
    fun init(context: Context) {
        if (!isInitialized) {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            isInitialized = true
            Log.d(TAG, "Debug logger initialized at: ${logFile?.absolutePath}")
        }
    }
    
    /**
     * Log a debug message with rate limiting
     */
    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any> = emptyMap()
    ) {
        if (!isInitialized || logFile == null) {
            Log.w(TAG, "Logger not initialized, skipping log: $message")
            return
        }
        
        // Rate limiting - skip if same location logged too recently
        val now = System.currentTimeMillis()
        val lastTime = lastLogTimes[location] ?: 0L
        if (now - lastTime < RATE_LIMIT_MS) {
            return // Skip this log
        }
        lastLogTimes[location] = now
        
        try {
            val dataJson = data.entries.joinToString(",") { "\"${it.key}\":${formatValue(it.value)}" }
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(now))
            val logLine = """{"t":"$timeStr","h":"$hypothesisId","loc":"$location","msg":"$message","d":{$dataJson}}""" + "\n"
            
            logFile?.appendText(logLine)
            logCount++
            
            // Trim log file if too large
            if (logCount > MAX_LOG_LINES * 2) {
                trimLogFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log: ${e.message}")
        }
    }
    
    /**
     * Keep only the last MAX_LOG_LINES lines
     */
    private fun trimLogFile() {
        try {
            val lines = logFile?.readLines() ?: return
            if (lines.size > MAX_LOG_LINES) {
                val trimmed = lines.takeLast(MAX_LOG_LINES)
                logFile?.writeText(trimmed.joinToString("\n") + "\n")
                logCount = trimmed.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trim log: ${e.message}")
        }
    }
    
    private fun formatValue(value: Any): String {
        return when (value) {
            is String -> "\"${value.replace("\"", "'")}\""
            is Boolean, is Number -> value.toString()
            null -> "null"
            else -> "\"${value.toString().replace("\"", "'")}\""
        }
    }
    
    /**
     * Read all logs
     */
    fun readLogs(): String {
        return try {
            logFile?.takeIf { it.exists() }?.readText() ?: "No log file"
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
    
    /**
     * Read only last N lines for sharing
     */
    fun readLastLogs(maxLines: Int = 50): String {
        return try {
            val lines = logFile?.takeIf { it.exists() }?.readLines() ?: return "No log file"
            val lastLines = lines.takeLast(maxLines)
            if (lines.size > maxLines) {
                "... (${lines.size - maxLines} righe precedenti omesse)\n" + lastLines.joinToString("\n")
            } else {
                lastLines.joinToString("\n")
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
    
    /**
     * Get log stats
     */
    fun getLogStats(): String {
        return try {
            val lines = logFile?.takeIf { it.exists() }?.readLines() ?: return "No log"
            "Totale: ${lines.size} righe"
        } catch (e: Exception) {
            "Error"
        }
    }
    
    /**
     * Clear logs
     */
    fun clearLogs() {
        try {
            logFile?.writeText("")
            logCount = 0
            lastLogTimes.clear()
            Log.d(TAG, "Logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs: ${e.message}")
        }
    }
    
    /**
     * Get log file path
     */
    fun getLogFilePath(): String {
        return logFile?.absolutePath ?: "Not initialized"
    }
}
