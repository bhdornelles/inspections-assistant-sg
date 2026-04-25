package com.example.safeguardassistant

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.safeguardassistant.databinding.ActivityOcrTestBinding
import java.util.concurrent.Executors

/**
 * Ecrã de teste: captura de ecrã (MediaProjection) + ML Kit OCR, **sem toques**. Útil para ver
 * se o texto do questionário Safeguard é legível fora da a11y.
 */
class OcrTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrTestBinding
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownSec = 0
    private var countdownRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonOcrCountdown.setOnClickListener { startCountdownThenCapture() }
        binding.buttonOcrNow.setOnClickListener { runCaptureAndOcr() }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun startCountdownThenCapture() {
        if (!ScreenCaptureHolder.hasProjection()) {
            Toast.makeText(this, R.string.ocr_test_need_capture, Toast.LENGTH_LONG).show()
            return
        }
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        binding.buttonOcrCountdown.isEnabled = false
        binding.buttonOcrNow.isEnabled = false
        binding.textCountdown.visibility = View.VISIBLE
        countdownSec = 5

        val r = object : Runnable {
            override fun run() {
                if (countdownSec == 0) {
                    binding.textCountdown.visibility = View.GONE
                    binding.buttonOcrCountdown.isEnabled = true
                    binding.buttonOcrNow.isEnabled = true
                    runCaptureAndOcr()
                    return
                }
                binding.textCountdown.text = countdownSec.toString()
                countdownSec--
                mainHandler.postDelayed(this, 1000L)
            }
        }
        countdownRunnable = r
        mainHandler.post(r)
    }

    private fun runCaptureAndOcr() {
        if (!ScreenCaptureHolder.hasProjection()) {
            Toast.makeText(this, R.string.ocr_test_need_capture, Toast.LENGTH_LONG).show()
            return
        }
        val projection = ScreenCaptureHolder.getProjection()
        if (projection == null) {
            Toast.makeText(this, R.string.ocr_test_need_capture, Toast.LENGTH_LONG).show()
            return
        }
        val app = applicationContext
        binding.textOcrResult.text = getString(R.string.ocr_test_running)
        ioExecutor.execute {
            val bitmap = ScreenshotManager.captureScreenBitmap(
                app,
                projection,
                maxDisplayLongestSide = 0,
            )
            if (bitmap == null) {
                mainHandler.post {
                    binding.textOcrResult.text = getString(R.string.ocr_test_capture_failed)
                    Log.e(TAG, "bitmap null (timeout / projection)")
                }
                return@execute
            }
            val result = OcrTextExtractor.recognizeSync(bitmap)
            bitmap.recycle()
            result.fold(
                onSuccess = { (lines, full) ->
                    OcrTextExtractor.logLines(lines, full)
                    val blocks = OcrQuestionGrouper.groupQuestions(lines)
                    val blocksJson = try {
                        OcrQuestionGrouper.blocksToJsonString(blocks)
                    } catch (e: Exception) {
                        blocks.toString()
                    }
                    Log.i("QUESTION_BLOCKS", "groupQuestions →\n$blocksJson")

                    val ocrJson = try {
                        OcrTextExtractor.toJsonLogArray(lines).toString(2)
                    } catch (e: Exception) {
                        lines.joinToString("\n")
                    }
                    val human = buildString {
                        appendLine("lines=${lines.size} chars=${full.length}")
                        appendLine()
                        appendLine("--- QUESTION BLOCKS (JSON) ---")
                        appendLine(blocksJson)
                        appendLine()
                        appendLine("--- RAW OCR (JSON) ---")
                        appendLine(ocrJson)
                    }
                    mainHandler.post { binding.textOcrResult.text = human }
                },
                onFailure = { e ->
                    Log.e(TAG, "ML Kit OCR", e)
                    mainHandler.post {
                        binding.textOcrResult.text = getString(
                            R.string.ocr_test_error,
                            e.message ?: e.javaClass.simpleName,
                        )
                    }
                },
            )
        }
    }

    private companion object {
        private const val TAG = "OcrTest"
    }
}
