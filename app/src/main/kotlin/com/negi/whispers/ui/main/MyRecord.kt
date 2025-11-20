/*
 * ================================================================
 *  IshizukiTech LLC — Whisper Integration Framework
 *  ------------------------------------------------
 *  File: MyRecord.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * ================================================================
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the “Software”), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 * ================================================================
 */

package com.negi.whispers.ui.main

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents a single recording entry displayed in the UI list.
 *
 * ## Purpose
 * A [MyRecord] encapsulates one unit of log information in the Whisper app —
 * either a user-created recording or a transient system message (e.g., status,
 * warnings, or transcription results).
 *
 * ## Properties
 * @property logs         Textual log shown in the UI (includes messages, transcription, etc.)
 * @property absolutePath Absolute path to the WAV file on disk (may be empty for transient messages)
 *
 * ## Serialization
 * - Uses a custom [MyRecordSerializer] for backward compatibility.
 * - Ignores unknown fields and prevents deserialization crashes.
 * - Gracefully handles null, blank, or missing values.
 */
@Serializable(with = MyRecordSerializer::class)
data class MyRecord(
    var logs: String,
    val absolutePath: String
)

/**
 * Custom serializer for [MyRecord].
 *
 * ## Rationale
 * Some legacy JSON entries may omit fields or contain null values.
 * This serializer guarantees stability across schema revisions
 * by decoding defensively and providing sane defaults.
 *
 * ## Encoding
 * Encodes exactly two fields:
 * ```
 * {
 *   "logs": "...",
 *   "absolutePath": "..."
 * }
 * ```
 *
 * ## Decoding
 * - Unknown or extra fields are ignored.
 * - Missing or blank `logs` → defaults to "(empty)".
 * - Missing or blank `absolutePath` → defaults to "".
 */
object MyRecordSerializer : KSerializer<MyRecord> {

    /** Defines the class structure for serialization. */
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MyRecord") {
        element<String>("logs")
        element<String>("absolutePath")
    }

    /** Encodes a [MyRecord] instance into structured JSON. */
    override fun serialize(encoder: Encoder, value: MyRecord) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeStringElement(descriptor, 0, value.logs)
        composite.encodeStringElement(descriptor, 1, value.absolutePath)
        composite.endStructure(descriptor)
    }

    /** Decodes structured JSON into [MyRecord], with full backward compatibility. */
    override fun deserialize(decoder: Decoder): MyRecord {
        val dec = decoder.beginStructure(descriptor)
        var logs = ""
        var path = ""

        loop@ while (true) {
            when (val index = dec.decodeElementIndex(descriptor)) {
                0 -> logs = dec.decodeStringElement(descriptor, 0)
                1 -> path = dec.decodeStringElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break@loop
                else -> {
                    // Skip unknown elements defensively
                    runCatching {
                        dec.decodeSerializableElement(descriptor, index, String.serializer())
                    }
                }
            }
        }
        dec.endStructure(descriptor)

        // Normalize empty or missing values
        return MyRecord(
            logs = logs.ifBlank { "(empty)" },
            absolutePath = path.ifBlank { "" }
        )
    }
}
