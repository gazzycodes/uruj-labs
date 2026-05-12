package com.uruj.service

import com.uruj.domain.RideSample
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Append-only newline-delimited JSON writer. One RideSample per line. Flushes after every
 * sample so a process kill loses at most the last in-flight write — typically the last
 * sub-second of data. We don't fsync (too expensive at 1 Hz on flash); rely on the OS
 * page cache, which is fine for JVM crashes but loses a few seconds on OS-level crashes.
 */
class NdjsonRideRecorder(file: File) {
    private val json = Json { encodeDefaults = false }
    private val writer: BufferedWriter = FileWriter(file, /* append = */ true).buffered()

    fun append(sample: RideSample) {
        writer.write(json.encodeToString(RideSample.serializer(), sample))
        writer.newLine()
        writer.flush()
    }

    fun close() {
        writer.flush()
        writer.close()
    }
}
