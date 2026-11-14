package com.expiryeye.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun BarcodeScannerScreen(onClose: () -> Unit, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permission = granted; permissionDenied = !granted }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var manualBarcode by remember { mutableStateOf("") }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_ITF).build()) }
    val cameraController = remember(context) { LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    } }
    DisposableEffect(scanner, executor) { onDispose { scanner.close(); executor.shutdown() } }

    DisposableEffect(permission, cameraController, lifecycleOwner) {
        if (!permission) {
            onDispose { }
        } else {
            cameraController.setImageAnalysisAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null || handled.get()) { proxy.close(); return@setImageAnalysisAnalyzer }
                scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                        if (value != null && handled.compareAndSet(false, true)) ContextCompat.getMainExecutor(context).execute { onBarcode(value) }
                    }
                    .addOnFailureListener { cameraError = "Couldn't read that barcode. Try holding the camera steady." }
                    .addOnCompleteListener { proxy.close() }
            }
            try { cameraController.bindToLifecycle(lifecycleOwner) }
            catch (error: Exception) { cameraError = error.message ?: "Camera could not start." }
            onDispose {
                cameraController.clearImageAnalysisAnalyzer()
                cameraController.unbind()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101713))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Scan a food barcode", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
            if (permission) {
                AndroidView(factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; controller = cameraController } }, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth(.84f).height(170.dp).background(Color.Transparent, RoundedCornerShape(22.dp)))
                Icon(Icons.Rounded.CenterFocusStrong, null, tint = Color(0xFFBFE8D2), modifier = Modifier.size(200.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("ExpiryEye uses the camera only to read a product barcode. The scan stays on your device.", color = Color.White)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text(if (permissionDenied) "Allow camera access" else "Enable camera") }
                }
            }
        }
        Text(cameraError ?: "Center the barcode inside the frame. Expiry dates are never read from a barcode.", color = Color.White, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(manualBarcode, { manualBarcode = it.filter { ch -> ch in '0'..'9' }.take(14) }, modifier = Modifier.weight(1f), label = { Text("Enter barcode manually") }, singleLine = true)
            Button(onClick = { onBarcode(manualBarcode) }, enabled = manualBarcode.length in 8..14) { Text("Use code") }
        }
    }
}
