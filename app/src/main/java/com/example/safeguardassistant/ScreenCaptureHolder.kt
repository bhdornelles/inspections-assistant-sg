package com.example.safeguardassistant

import android.media.projection.MediaProjection
import java.util.concurrent.atomic.AtomicReference

/**
 * Resultado de [android.app.Activity.startActivityForResult] para MediaProjection.
 * O utilizador deve autorizar na MainActivity antes do autofill com visão.
 */
object ScreenCaptureHolder {

    private val projectionRef = AtomicReference<MediaProjection?>(null)

    fun setProjection(projection: MediaProjection?) {
        projectionRef.getAndSet(projection)?.stop()
    }

    fun getProjection(): MediaProjection? = projectionRef.get()

    fun hasProjection(): Boolean = projectionRef.get() != null

    fun clear() {
        setProjection(null)
    }
}
