package com.deltainventory.app.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import com.deltainventory.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 屏幕采集前台服务（自适应屏幕尺寸）。
 * - 仅在用户显式授权 MediaProjection 并主动启动后运行。
 * - 按当前真实屏幕宽高/density 创建 VirtualDisplay 与 ImageReader。
 * - 旋转/分辨率变化时自动重建。
 * - Image 立即转成 Bitmap 并关闭 Image（避免泄漏），通过 Binder 暴露给处理器。
 */
class CaptureService : Service() {

    companion object {
        const val TAG = "CaptureService"
        const val CHANNEL_ID = "di_capture"
        const val NOTIF_ID = 1
        const val ACTION_START = "di.action.START"
        const val ACTION_STOP = "di.action.STOP"
        const val ACTION_RECONFIGURE = "di.action.RECONFIGURE"
        const val ACTION_CAPTURE_STOPPED = "di.action.CAPTURE_STOPPED"
        // 工作分辨率：长边限制，显著降低全链路内存与拷贝；仍保留屏幕真实尺寸用于坐标换算
        const val WORK_MAX_DIM = 640
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var floatingBall: View? = null
    private var wm: WindowManager? = null
    private var isRunning = false
    private var isPaused = false
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var resultCode = 0
    private var resultData: Intent? = null

    // 屏幕自适应信息
    var currentScreenInfo = ScreenInfo(0, 0, 0, 0)
        private set
    var currentWorkSize = android.util.Size(0, 0)
        private set

    // 转 Bitmap 后的帧流（供识别流水线消费）
    val frameBitmapFlow = MutableSharedFlow<Bitmap>(extraBufferCapacity = 2)

    private val binder = CaptureBinder(this)
    override fun onBind(intent: Intent?): IBinder = binder

    fun isCapturing(): Boolean = isRunning && !isPaused
    fun pauseCapture() { isPaused = true }
    fun resumeCapture() { isPaused = false }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { Log.w(TAG, "系统停止采集"); stopCapture("system_stop") }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                resultData = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode == Activity.RESULT_OK && resultData != null) startProjection()
                else { Log.e(TAG, "授权失败"); stopSelf() }
            }
            ACTION_STOP -> stopCapture("user_stop")
            ACTION_RECONFIGURE -> handleConfigurationChanged()
        }
        return START_STICKY
    }

    /** 按当前真实屏幕尺寸（自适应）创建采集 */
    private fun startProjection() {
        projection?.unregisterCallback(callback)
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, resultData!!)
            .also { it.registerCallback(callback, Handler(Looper.getMainLooper())) }

        val metrics = currentMetrics()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val density = metrics.densityDpi
        val rotation = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        currentScreenInfo = ScreenInfo(screenW, screenH, density, rotation)

        // 工作分辨率：按屏幕比例把长边缩到 WORK_MAX_DIM，全链路都用小图
        val scale = WORK_MAX_DIM.toFloat() / maxOf(screenW, screenH)
        val workW = (screenW * scale).toInt().coerceAtLeast(1)
        val workH = (screenH * scale).toInt().coerceAtLeast(1)
        currentWorkSize = android.util.Size(workW, workH)

        // 后台线程处理帧，避免阻塞主线程
        captureThread?.quitSafely()
        captureThread = HandlerThread("DI_Capture", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = ImageReader.newInstance(workW, workH, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!isPaused) {
                        val bmp = imageToBitmap(image)
                        if (!frameBitmapFlow.tryEmit(bmp)) bmp.recycle() // 背压：丢弃
                    }
                } finally { image.close() }
            }, captureHandler)
        }

        virtualDisplay?.release()
        virtualDisplay = projection?.createVirtualDisplay(
            "DI_Capture", workW, workH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, captureHandler
        )
        if (floatingBall == null) addFloatingBall(getSystemService(WINDOW_SERVICE) as WindowManager)
        isRunning = true
        isPaused = false
        Log.i(TAG, "采集启动(自适应) ${screenW}x${screenH}(work=${workW}x${workH}) density=$density rotation=$rotation")
    }

    private fun currentMetrics(): DisplayMetrics {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    /** 配置变化（旋转/分辨率）：用保存的授权重建 */
    fun handleConfigurationChanged() {
        if (!isRunning || resultData == null) return
        Log.i(TAG, "配置变化，重建采集")
        startProjection()
    }

    /** Image(RAW) -> Bitmap，按屏幕尺寸自适应，完成后调用方负责 recycle */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * currentWorkSize.width
        val w = currentWorkSize.width + rowPadding / pixelStride
        val h = currentWorkSize.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (w != currentWorkSize.width)
            Bitmap.createBitmap(bitmap, 0, 0, currentWorkSize.width, h).also { bitmap.recycle() }
        else bitmap
    }

    private fun addFloatingBall(wm: WindowManager) {
        this.wm = wm
        val ball = View(this).apply { setBackgroundResource(R.drawable.floating_ball); alpha = 0.85f }
        // 悬浮球尺寸按 density 自适应
        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 200 }
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        ball.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (e.rawX - tx).toInt(); params.y = iy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(v, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(e.rawX - tx) < 10 && kotlin.math.abs(e.rawY - ty) < 10)
                        sendBroadcast(Intent("di.action.TOGGLE_SCAN").setPackage(packageName))
                    true
                }
                else -> false
            }
        }
        wm.addView(ball, params)
        floatingBall = ball
    }

    /** 限制悬浮球不超出屏幕（配置变化后调用） */
    fun clampFloatingBall() {
        val ball = floatingBall ?: return
        val p = ball.layoutParams as? WindowManager.LayoutParams ?: return
        p.x = p.x.coerceIn(0, (currentScreenInfo.width - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (currentScreenInfo.height - p.height).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(ball, p) }
    }

    private fun stopCapture(reason: String) {
        if (!isRunning) return
        isRunning = false; isPaused = false
        scope.launch {
            virtualDisplay?.release(); virtualDisplay = null
            imageReader?.setOnImageAvailableListener(null, null); imageReader?.close(); imageReader = null
            projection?.unregisterCallback(callback); projection?.stop(); projection = null
            withContext(Dispatchers.Main) { floatingBall?.let { runCatching { wm?.removeView(it) } }; floatingBall = null }
            sendBroadcast(Intent(ACTION_CAPTURE_STOPPED).setPackage(packageName).putExtra("reason", reason))
            stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleConfigurationChanged()
        clampFloatingBall()
    }

    override fun onDestroy() {
        stopCapture("destroy"); scope.cancel()
        captureThread?.quitSafely(); captureThread = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26)
            NotificationChannel(CHANNEL_ID, "屏幕采集", NotificationManager.IMPORTANCE_LOW)
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("DeltaInventory 盘点中")
            .setContentText("正在采集屏幕进行物品识别")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止",
                PendingIntent.getService(this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
            .build()
}
