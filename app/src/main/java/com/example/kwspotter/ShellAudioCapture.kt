package com.example.kwspotter

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Этот класс запускается не внутри приложения, а отдельной командой
 * `app_process` от имени системной оболочки (uid shell). У оболочки есть доступ
 * к голосу собеседника, которого нет у обычного приложения.
 *
 * На стандартный вывод пишется только сырой PCM: 16 кГц, моно, 16 бит.
 * Сначала одна служебная строка `KWSPCM1`, чтобы приложение поняло, что захват
 * начался. Любой текст ошибки начинается с `KWSERR:`.
 */
class ShellAudioCapture {

    companion object {
        private const val TAG = "ShellAudioCapture"
        private const val OUT_RATE = 16000

        @JvmStatic
        fun main(args: Array<String>) {
            val out = FileOutputStream(FileDescriptor.out)
            try {
                val capture = openCapture()
                out.write("KWSPCM1\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                pumpSingle(capture, out)
            } catch (t: Throwable) {
                Log.e(TAG, "Capture failed", t)
                val message = (t.message ?: t.javaClass.simpleName).replace('\n', ' ')
                out.write("KWSERR:$message\n".toByteArray(Charsets.UTF_8))
                out.flush()
            } finally {
                out.close()
            }
        }

        private fun openCapture(): Capture {
            // Только собеседник. Свой микрофон не открываем: иначе свои слова
            // «полиция» и «фсб» тоже поднимают шторку.
            tryOpen(MediaRecorder.AudioSource.VOICE_DOWNLINK, OUT_RATE, AudioFormat.CHANNEL_IN_MONO)
                ?.let { return single(it, AudioFormat.CHANNEL_IN_MONO, OUT_RATE, remoteOnly = false) }
            tryOpen(MediaRecorder.AudioSource.VOICE_DOWNLINK, 8000, AudioFormat.CHANNEL_IN_MONO)
                ?.let { return single(it, AudioFormat.CHANNEL_IN_MONO, 8000, remoteOnly = false) }

            // Стереозвонок: левый канал — микрофон, правый — собеседник.
            tryOpen(MediaRecorder.AudioSource.VOICE_CALL, OUT_RATE, AudioFormat.CHANNEL_IN_STEREO)
                ?.let { return single(it, AudioFormat.CHANNEL_IN_STEREO, OUT_RATE, remoteOnly = true) }
            tryOpen(MediaRecorder.AudioSource.VOICE_CALL, 8000, AudioFormat.CHANNEL_IN_STEREO)
                ?.let { return single(it, AudioFormat.CHANNEL_IN_STEREO, 8000, remoteOnly = true) }

            tryOpen(MediaRecorder.AudioSource.VOICE_CALL, OUT_RATE, AudioFormat.CHANNEL_IN_MONO)
                ?.let { return single(it, AudioFormat.CHANNEL_IN_MONO, OUT_RATE, remoteOnly = false) }
            tryOpen(MediaRecorder.AudioSource.VOICE_CALL, 8000, AudioFormat.CHANNEL_IN_MONO)
                ?.let { return single(it, AudioFormat.CHANNEL_IN_MONO, 8000, remoteOnly = false) }
            throw IllegalStateException("Телефон не отдал голос собеседника")
        }

        private fun single(
            record: AudioRecord,
            channels: Int,
            rate: Int,
            remoteOnly: Boolean
        ): Capture {
            Log.i(TAG, "Remote capture rate=$rate channels=$channels right=$remoteOnly")
            return Capture(record, channels, rate, remoteOnly)
        }

        private fun tryOpen(source: Int, rate: Int, channels: Int): AudioRecord? {
            val min = AudioRecord.getMinBufferSize(rate, channels, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val record = AudioRecord(
                source,
                rate,
                channels,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return record
        }

        private fun pumpSingle(capture: Capture, out: FileOutputStream) {
            val record = capture.record
            record.startRecording()
            val frameBytes = if (capture.channels == AudioFormat.CHANNEL_IN_STEREO) 4 else 2
            val input = ByteArray(frameBytes * 160)
            val output = ByteArray(2 * 160 * if (capture.rate == 8000) 2 else 1)
            try {
                while (true) {
                    val read = record.read(input, 0, input.size)
                    if (read <= 0) break
                    val frames = read / frameBytes
                    var outIndex = 0
                    for (frame in 0 until frames) {
                        val sample = when {
                            capture.remoteOnly -> littleEndian(input, frame * 4 + 2).toShort()
                            capture.channels == AudioFormat.CHANNEL_IN_STEREO -> {
                                val left = littleEndian(input, frame * 4)
                                val right = littleEndian(input, frame * 4 + 2)
                                ((left + right) / 2).toShort()
                            }
                            else -> littleEndian(input, frame * 2).toShort()
                        }
                        outIndex = writeSample(output, outIndex, sample)
                        if (capture.rate == 8000) outIndex = writeSample(output, outIndex, sample)
                    }
                    out.write(output, 0, outIndex)
                    out.flush()
                }
            } finally {
                record.stop()
                record.release()
            }
        }

        private fun littleEndian(buffer: ByteArray, offset: Int): Int {
            val low = buffer[offset].toInt() and 0xff
            val high = buffer[offset + 1].toInt() shl 8
            return high or low
        }

        private fun writeSample(buffer: ByteArray, offset: Int, sample: Short): Int {
            buffer[offset] = sample.toByte()
            buffer[offset + 1] = (sample.toInt() shr 8).toByte()
            return offset + 2
        }
    }

    private class Capture(
        val record: AudioRecord,
        val channels: Int,
        val rate: Int,
        val remoteOnly: Boolean
    )
}
