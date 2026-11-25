package com.deltainventory.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.deltainventory.app.capture.CaptureService
import com.deltainventory.app.domain.ScanProcessor
import com.deltainventory.app.vision.IconMatcher
import com.deltainventory.app.domain.ScanViewModel
import com.deltainventory.app.ui.AppNav
import com.deltainventory.app.ui.Theme
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val vm: ScanViewModel by viewModels()
    private var processor: ScanProcessor? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            vm.onCaptureAuthorized()
            Toast.makeText(this, "正在识别物资…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授权屏幕采集", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        initProcessor()
        setContent {
            Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav(vm = vm)
                }
            }
        }
    }

    /** 从 assets/item-database.json 读取物品类型作为 class_id -> itemTypeId 映射 */
    private fun initProcessor() {
        try {
            val json = assets.open("item-database.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("items")
            val types = (0 until arr.length()).map { arr.getJSONObject(it).getString("itemId") }
            processor = ScanProcessor(this, vm)
            vm.processor = processor
            vm.requestCaptureAuth = { startScreenCapture() }
            vm.stopCapture = {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                processor?.unbind()
            }
            Log.i("MainActivity", "图标特征库: ${IconMatcher.get(this).count} 件, 物品类型: ${types.size}")
        } catch (e: Exception) {
            Log.e("MainActivity", "物品库加载失败: ${e.message}")
        }
    }

    private fun startScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** 屏幕旋转/分辨率变化：通知采集服务按新尺寸自适应重建 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_RECONFIGURE))
    }

    override fun onDestroy() {
        processor?.unbind()
        super.onDestroy()
    }
}
