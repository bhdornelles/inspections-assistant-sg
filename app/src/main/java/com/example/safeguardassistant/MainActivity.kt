package com.example.safeguardassistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.safeguardassistant.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateStatusUi()
        maybeStartOverlay()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        launchScreenCapturePermissionIntent()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            Toast.makeText(this, R.string.screen_capture_denied, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, MediaProjectionCaptureService::class.java).apply {
                action = MediaProjectionCaptureService.ACTION_START
                putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MediaProjectionCaptureService.EXTRA_DATA, result.data)
            },
        )
        Toast.makeText(this, R.string.screen_capture_granted, Toast.LENGTH_SHORT).show()
        // O serviço cria/grava a MediaProjection de forma assíncrona; revalida o status.
        updateStatusUi()
        mainHandler.postDelayed({ updateStatusUi() }, 700L)
        mainHandler.postDelayed({ updateStatusUi() }, 1700L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonGrantOverlay.setOnClickListener { requestOverlayPermission() }
        binding.buttonGrantScreenCapture.setOnClickListener { requestScreenCapturePermissionFlow() }
        binding.buttonRevokeScreenCapture.setOnClickListener { revokeScreenCapture() }
        binding.buttonOpenAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.buttonOpenOcrTest.setOnClickListener {
            startActivity(Intent(this, OcrTestActivity::class.java))
        }
        binding.buttonLogin.setOnClickListener { onLoginClick() }
        binding.buttonRegister.setOnClickListener { onRegisterClick() }
        binding.buttonLogout.setOnClickListener {
            AuthStore.clear(this)
            updateAuthStatus()
            Toast.makeText(this, R.string.auth_logout_ok, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
        updateAuthStatus()
        maybeStartOverlay()
    }

    private fun onLoginClick() {
        val email = binding.editEmail.text?.toString().orEmpty().trim()
        val password = binding.editPassword.text?.toString().orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_credentials, Toast.LENGTH_SHORT).show()
            return
        }
        val app = applicationContext
        ioExecutor.execute {
            val result = AuthApi.login(app, email, password)
            mainHandler.post {
                Toast.makeText(
                    this@MainActivity,
                    result.message,
                    if (result.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                if (result.ok) {
                    binding.editPassword.text = null
                }
                updateAuthStatus()
            }
        }
    }

    private fun onRegisterClick() {
        val email = binding.editEmail.text?.toString().orEmpty().trim()
        val password = binding.editPassword.text?.toString().orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_credentials, Toast.LENGTH_SHORT).show()
            return
        }
        val app = applicationContext
        ioExecutor.execute {
            val result = AuthApi.register(app, email, password)
            mainHandler.post {
                Toast.makeText(
                    this@MainActivity,
                    result.message,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun updateAuthStatus() {
        val token = AuthStore.getToken(this)
        val email = AuthStore.getEmail(this)
        val logged = !token.isNullOrBlank() && !email.isNullOrBlank()
        binding.layoutAuthSignedIn.isVisible = logged
        binding.layoutAuthSignedOut.isVisible = !logged
        if (logged) {
            binding.textAuthLoggedInEmail.text = getString(R.string.auth_logged_as, email)
        }
    }

    private fun updateStatusUi() {
        val overlayOn = Settings.canDrawOverlays(this)
        binding.textOverlayStatus.text = getString(
            R.string.overlay_status,
            getString(if (overlayOn) R.string.status_on else R.string.status_off),
        )
        val a11yOn = isAccessibilityServiceEnabled()
        binding.textAccessibilityStatus.text = getString(
            R.string.accessibility_status,
            getString(if (a11yOn) R.string.status_on else R.string.status_off),
        )
        val captureOn = ScreenCaptureHolder.hasProjection()
        binding.textScreenCaptureStatus.text = getString(
            R.string.screen_capture_status,
            getString(if (captureOn) R.string.status_on else R.string.status_off),
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, MyAccessibilityService::class.java).flattenToString()
        val setting = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = SimpleStringSplitter(':')
        splitter.setString(setting)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /** Minimal colon-splitter (android.text.TextUtils.SimpleStringSplitter is hidden on some APIs). */
    private class SimpleStringSplitter(private val delimiter: Char) {
        private var string: String = ""
        private var pos = 0

        fun setString(s: String) {
            string = s
            pos = 0
        }

        fun hasNext(): Boolean = pos < string.length

        fun next(): String {
            val end = string.indexOf(delimiter, pos)
            val token = if (end == -1) {
                val t = string.substring(pos)
                pos = string.length
                t
            } else {
                val t = string.substring(pos, end)
                pos = end + 1
                t
            }
            return token
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, R.string.overlay_not_needed_api, Toast.LENGTH_SHORT).show()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_already_granted, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestScreenCapturePermissionFlow() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!ok) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        launchScreenCapturePermissionIntent()
    }

    private fun launchScreenCapturePermissionIntent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun revokeScreenCapture() {
        startService(
            Intent(this, MediaProjectionCaptureService::class.java).apply {
                action = MediaProjectionCaptureService.ACTION_STOP
            },
        )
        ScreenCaptureHolder.clear()
        Toast.makeText(this, R.string.screen_capture_cleared, Toast.LENGTH_SHORT).show()
        updateStatusUi()
    }

    private fun maybeStartOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            FloatingOverlayManager.get(this).hide()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            FloatingOverlayManager.get(this).hide()
            return
        }
        FloatingOverlayManager.get(this).show()
    }

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }
}
