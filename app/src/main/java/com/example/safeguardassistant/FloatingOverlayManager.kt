package com.example.safeguardassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * FAB + painel Material + **mensagens de resultado** no próprio overlay (Toast não aparece por cima de outras apps).
 */
class FloatingOverlayManager(appContext: Context) {

    private val appContext = appContext.applicationContext
    private val themedContext = ContextThemeWrapper(appContext, R.style.Theme_SafeguardAssistant)

    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var fabContainer: FrameLayout? = null
    private var panelContainer: View? = null
    private var feedbackBanner: TextView? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var feedbackParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decisionExecutor = Executors.newSingleThreadExecutor()
    private var hideBannerRunnable: Runnable? = null

    @SuppressLint("InflateParams")
    fun show() {
        if (fabContainer != null) return

        val size = dp(56)
        val fab = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_floating_riosul)
            background = ContextCompat.getDrawable(themedContext, R.drawable.floating_fab_background)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6))
            minimumWidth = size
            minimumHeight = size
            isClickable = false
            isFocusable = false
            contentDescription = themedContext.getString(R.string.floating_button_cd)
        }
        val fabFrame = FrameLayout(themedContext).apply {
            val lp = FrameLayout.LayoutParams(size, size)
            addView(fab, lp)
            isClickable = true
            isFocusable = true
            setOnClickListener { togglePanel() }
        }
        fabParams = fabOverlayLayoutParams().apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(20)
            y = 0
        }
        try {
            windowManager.addView(fabFrame, fabParams)
            fabContainer = fabFrame
        } catch (e: Exception) {
            Log.e(TAG, "add FAB overlay", e)
        }
    }

    fun hide() {
        dismissFeedbackBanner()
        panelContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelContainer = null
        panelParams = null

        fabContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        fabContainer = null
        fabParams = null
    }

    private fun togglePanel() {
        if (panelContainer != null) {
            dismissPanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panelContainer != null) return

        val sheet = LayoutInflater.from(themedContext).inflate(R.layout.overlay_profile_sheet, null)
        val panelStepFamily = sheet.findViewById<LinearLayout>(R.id.panelStepFamily)
        val panelStepFiSituation = sheet.findViewById<LinearLayout>(R.id.panelStepFiSituation)
        val textSheetSubtitle = sheet.findViewById<TextView>(R.id.textSheetSubtitle)

        fun showFamilyStep() {
            panelStepFamily.visibility = View.VISIBLE
            panelStepFiSituation.visibility = View.GONE
            textSheetSubtitle.setText(R.string.overlay_step1_subtitle)
        }

        fun showFiSituationStep() {
            panelStepFamily.visibility = View.GONE
            panelStepFiSituation.visibility = View.VISIBLE
            textSheetSubtitle.setText(R.string.overlay_step2_subtitle)
        }

        showFamilyStep()

        sheet.findViewById<MaterialButton>(R.id.btnFamilyFi).setOnClickListener { showFiSituationStep() }
        sheet.findViewById<MaterialButton>(R.id.btnFamilyDf).setOnClickListener {
            onProfileSelected(requireNotNull(resolveInspectionProfile(InspectionFamily.DF, null)))
        }
        sheet.findViewById<MaterialButton>(R.id.btnFamilyE26).setOnClickListener {
            onProfileSelected(requireNotNull(resolveInspectionProfile(InspectionFamily.E26RNN, null)))
        }
        sheet.findViewById<MaterialButton>(R.id.btnBackFi).setOnClickListener { showFamilyStep() }
        sheet.findViewById<MaterialButton>(R.id.btnFiOccupied).apply {
            text = InspectionProfile.FI_OCCUPIED.displayLabel
            setOnClickListener {
                onProfileSelected(
                    requireNotNull(resolveInspectionProfile(InspectionFamily.FI, FiSituation.OCCUPIED)),
                )
            }
        }
        sheet.findViewById<MaterialButton>(R.id.btnFiVacant).apply {
            text = InspectionProfile.FI_VACANT.displayLabel
            setOnClickListener {
                onProfileSelected(
                    requireNotNull(resolveInspectionProfile(InspectionFamily.FI, FiSituation.VACANT)),
                )
            }
        }
        sheet.findViewById<MaterialButton>(R.id.btnCloseSheet).setOnClickListener { dismissPanel() }

        panelParams = panelOverlayLayoutParams().apply {
            width = dp(312)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
            dimAmount = 0.5f
        }
        try {
            windowManager.addView(sheet, panelParams)
            panelContainer = sheet
        } catch (e: Exception) {
            Log.e(TAG, "add panel overlay", e)
        }
    }

    private fun dismissPanel() {
        panelContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelContainer = null
        panelParams = null
    }

    private fun dismissFeedbackBanner() {
        hideBannerRunnable?.let { mainHandler.removeCallbacks(it) }
        hideBannerRunnable = null
        feedbackBanner?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        feedbackBanner = null
        feedbackParams = null
    }

    /**
     * Mensagem visível por cima de qualquer app (ao contrário do Toast com applicationContext).
     */
    private fun showFeedbackBanner(message: String, durationMs: Long = 4000L) {
        dismissFeedbackBanner()
        val tv = TextView(themedContext).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            maxLines = 8
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val displayMetrics = appContext.resources.displayMetrics
        val maxW = (displayMetrics.widthPixels * 0.92f).toInt()
        feedbackParams = feedbackOverlayLayoutParams().apply {
            width = maxW
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = -dp(96)
        }
        try {
            windowManager.addView(tv, feedbackParams)
            feedbackBanner = tv
            hideBannerRunnable = Runnable { dismissFeedbackBanner() }
            mainHandler.postDelayed(hideBannerRunnable!!, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "feedback banner", e)
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun onProfileSelected(profile: InspectionProfile) {
        AppState.setProfile(profile)
        dismissPanel()
        showFeedbackBanner(appContext.getString(R.string.feedback_processing), durationMs = 2500L)

        if (profile == InspectionProfile.FI_OCCUPIED && !ScreenCaptureHolder.hasProjection()) {
            // Evita falso alerta: a MediaProjection pode demorar a ficar disponível depois do "Autorizar captura".
            mainHandler.postDelayed({
                if (!ScreenCaptureHolder.hasProjection()) {
                    mainHandler.postDelayed({
                        if (!ScreenCaptureHolder.hasProjection()) {
                            showFeedbackBanner(appContext.getString(R.string.feedback_screen_capture_hint), 6500L)
                        }
                    }, 900L)
                }
            }, 700L)
        }

        val service = MyAccessibilityService.getInstance()
        if (service == null) {
            showFeedbackBanner(appContext.getString(R.string.accessibility_service_off))
            Toast.makeText(appContext, R.string.accessibility_service_off, Toast.LENGTH_LONG).show()
            return
        }

        val texts = try {
            MyAccessibilityService.readVisibleTextsOrEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "readVisibleTextsOrEmpty", e)
            emptyList()
        }

        Log.d(TAG, "visibleTexts count=${texts.size} sample=${texts.take(5)}")

        if (texts.isEmpty()) {
            showFeedbackBanner(appContext.getString(R.string.feedback_no_texts), durationMs = 6000L)
            return
        }

        if (!FormFillOrchestrator.tryStartSession()) {
            showFeedbackBanner(appContext.getString(R.string.feedback_orchestration_already_running), 5000L)
            return
        }

        FormFillOrchestrator.startOnExecutor(
            appContext = appContext,
            profile = profile,
            service = service,
            executor = decisionExecutor,
            mainHandler = mainHandler,
            onFeedbackMainThread = { message, durationMs ->
                showFeedbackBanner(message, durationMs)
            },
        )
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun fabOverlayLayoutParams(): WindowManager.LayoutParams {
        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun panelOverlayLayoutParams(): WindowManager.LayoutParams {
        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** Banner sem escurecer o ecrã inteiro (só o cartão do painel usa DIM). */
    private fun feedbackOverlayLayoutParams(): WindowManager.LayoutParams {
        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatingOverlay"

        @Volatile
        private var instance: FloatingOverlayManager? = null

        fun get(appContext: Context): FloatingOverlayManager {
            return instance ?: synchronized(this) {
                instance ?: FloatingOverlayManager(appContext).also { instance = it }
            }
        }
    }
}
