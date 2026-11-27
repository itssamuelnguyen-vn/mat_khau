package com.samuelcruz.matkhau.ui.screens

import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.samuelcruz.matkhau.core.qr.QrDecoder
import com.samuelcruz.matkhau.core.totp.Totp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quét mã QR 2FA (otpauth://totp/...) bằng CameraX + ZXing.
 *
 * - Chỉ mở khi quyền CAMERA đã được cấp (người gọi xin quyền lúc bấm nút quét).
 * - Khung hình chỉ nằm trong RAM: lấy kênh độ sáng, giải mã, ghi đè bằng 0. Không lưu, không log.
 * - Preview dùng chế độ COMPATIBLE (TextureView) để nằm trong cửa sổ có FLAG_SECURE,
 *   nên không ai chụp/quay được hình mã QR (chứa secret) đang hiện trên màn hình.
 * - Rời màn hình (hủy, quét xong, vault tự khóa) => camera được tắt trong onDispose.
 */
@Composable
fun QrScanScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    var message by remember { mutableStateOf<String?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    BackHandler(onBack = onCancel)

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val finished = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            if (finished.get()) return@addListener
            val cameraProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                message = "Không mở được camera"
                return@addListener
            }
            provider = cameraProvider

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                // Luồng của executor: exception nào lọt ra cũng làm sập app, nên bắt hết (bỏ qua khung hình đó).
                val text = try {
                    if (finished.get()) null else decodeFrame(image)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (text != null) {
                    if (Totp.isValid(text)) {
                        if (finished.compareAndSet(false, true)) mainExecutor.execute { currentOnResult(text) }
                    } else {
                        mainExecutor.execute { message = "Mã QR này không phải mã 2FA (otpauth://totp hoặc secret Base32)" }
                    }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                message = "Không mở được camera sau của máy"
            }
        }, mainExecutor)

        onDispose {
            finished.set(true)
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Khung ngắm ở giữa
        Box(
            Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
        )

        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Đưa mã QR 2FA vào trong khung",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                message ?: "Ảnh chỉ được xử lý trong bộ nhớ, không lưu lại.",
                color = if (message != null) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Lấy kênh độ sáng (Y) của khung hình YUV_420_888 rồi giải mã; mảng tạm được ghi đè bằng 0 ngay sau đó. */
private fun decodeFrame(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    return try {
        QrDecoder.decode(data, plane.rowStride, image.width, image.height)
    } finally {
        data.fill(0)
    }
}
