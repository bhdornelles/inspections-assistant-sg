package com.example.safeguardassistant

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * ML Kit (latim) em linha — **só leitura**, sem automação. Chamadas a [recognizeSync] fora
 * do thread principal (p.ex. [java.util.concurrent.Executor]).
 */
object OcrTextExtractor {

    data class OcrTextLine(
        val text: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        fun toJsonObject(): JSONObject =
            JSONObject()
                .put("text", text)
                .put("x", x)
                .put("y", y)
                .put("width", width)
                .put("height", height)
    }

    private const val TAG = "OcrTextExtractor"
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Devolve (linhas com *bounding box*, texto completo em [Text.getText]).
     * Não fazer *recycle* de [bitmap] aqui — o chamador.
     * Bloqueia até a tarefa ML Kit terminar.
     */
    fun recognizeSync(bitmap: Bitmap): Result<Pair<List<OcrTextLine>, String>> = runCatching {
        val input = InputImage.fromBitmap(bitmap, 0)
        val result = Tasks.await(recognizer.process(input))
        val lines = buildList {
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val t = line.text?.trim().orEmpty()
                    if (t.isEmpty()) continue
                    val box = line.boundingBox
                    if (box == null) {
                        add(OcrTextLine(t, 0, 0, 0, 0))
                    } else {
                        add(
                            OcrTextLine(
                                text = t,
                                x = box.left,
                                y = box.top,
                                width = box.width().coerceAtLeast(0),
                                height = box.height().coerceAtLeast(0),
                            ),
                        )
                    }
                }
            }
        }
        val full = result.text.orEmpty()
        Pair(lines, full)
    }

    fun toJsonLogArray(lines: List<OcrTextLine>): JSONArray = JSONArray().apply {
        for (l in lines) {
            put(l.toJsonObject())
        }
    }

    fun logLines(lines: List<OcrTextLine>, fullText: String) {
        Log.i(TAG, "OCR fullText (${fullText.length} chars) preview: ${fullText.lineSequence().take(5).joinToString(" | ")}")
        for (l in lines) {
            Log.i(TAG, l.toString())
        }
        try {
            val arr = toJsonLogArray(lines)
            Log.i(TAG, "OCR_JSON\n${arr.toString(2)}")
        } catch (e: Exception) {
            Log.w(TAG, "OCR json log", e)
        }
    }
}
