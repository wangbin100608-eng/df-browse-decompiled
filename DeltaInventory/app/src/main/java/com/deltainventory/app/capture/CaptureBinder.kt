package com.deltainventory.app.capture

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import android.os.Binder

/**
 * CaptureService 的本地Binder，向 Activity/ViewModel 暴露帧流与屏幕信息。
 */
class CaptureBinder(private val service: CaptureService) : Binder() {
    val frames: Flow<Bitmap> get() = service.frameBitmapFlow
    val screenInfo: ScreenInfo get() = service.currentScreenInfo
    fun isRunning(): Boolean = service.isCapturing()
    fun pause() = service.pauseCapture()
    fun resume() = service.resumeCapture()
}

data class ScreenInfo(val width: Int, val height: Int, val density: Int, val rotation: Int)
