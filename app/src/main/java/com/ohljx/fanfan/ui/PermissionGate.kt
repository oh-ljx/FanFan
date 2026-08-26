package com.ohljx.fanfan.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 按系统版本返回需要申请的媒体权限。 */
fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }

/** 是否已能读相册（含 Android 14+ 的“部分照片”授权）。 */
fun hasMediaAccess(context: Context): Boolean {
    val granted = requiredMediaPermissions().any { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
    if (granted) return true
    // Android 14+ 部分授权
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * 权限门：已授权则显示 [content]，否则显示授权引导页。
 * 从系统设置返回时会自动重新检查。
 */
@Composable
fun MediaPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaAccess(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasMediaAccess(context) }

    // 从系统设置页返回时重新检查
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasMediaAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("翻翻需要访问你的照片", fontSize = 20.sp, color = Color(0xFFEEF3EC))
        Text(
            "所有浏览和整理都在本机完成，不会上传任何内容。",
            fontSize = 13.sp,
            color = Color(0xFF8FA598),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = { launcher.launch(requiredMediaPermissions()) },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2D4A3E),
                contentColor = Color(0xFFF2F6F1),
            ),
            modifier = Modifier.padding(top = 28.dp),
        ) {
            Text("授权访问相册")
        }
        TextButton(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                context.startActivity(intent)
            },
        ) {
            Text("去系统设置开启", fontSize = 13.sp, color = Color(0xFF7CC7AB))
        }
    }
}
