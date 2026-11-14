package com.expiryeye.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.expiryeye.domain.ReceiptCandidate
import com.expiryeye.domain.ReceiptTextParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

@Composable
fun ReceiptScannerScreen(onClose: () -> Unit, onConfirm: (List<ReceiptCandidate>) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    var candidates by remember { mutableStateOf<List<ReceiptCandidate>?>(null) }
    var selected by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var priceDrafts by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val controller = remember(context) { LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
    } }
    DisposableEffect(recognizer, executor) { onDispose { recognizer.close(); executor.shutdown() } }
    DisposableEffect(hasPermission, controller, owner) {
        if (hasPermission) { controller.bindToLifecycle(owner); onDispose { controller.unbind() } } else onDispose { }
    }

    fun processCapture(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); busy = false; error = "The camera returned an empty image. Try again."; return }
        recognizer.process(InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { result ->
                val parsed = ReceiptTextParser.parse(result.text)
                candidates = parsed
                selected = parsed.map { true }
                priceDrafts = parsed.map { it.price?.toString().orEmpty() }
                if (parsed.isEmpty()) error = "No likely product lines found. You can try another photo or add items manually."
            }
            .addOnFailureListener { error = "Receipt text could not be read. Try a clearer, well-lit photo." }
            .addOnCompleteListener { proxy.close(); busy = false }
    }

    if (candidates == null) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101713))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
                Text("Scan a receipt", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                if (hasPermission) AndroidView(factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } }, modifier = Modifier.fillMaxSize())
                else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Camera access is used to read the receipt on this device. Inventory data and receipt images are not uploaded.", color = Color.White)
                    Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Enable camera") }
                }
            }
            Text("OCR suggestions may be wrong. Review every line. Receipts do not provide food expiration dates.", color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            error?.let { Text(it, color = Color(0xFFFFD3C9), modifier = Modifier.padding(horizontal = 20.dp)) }
            Button(onClick = {
                if (!hasPermission) { permissionRequest.launch(Manifest.permission.CAMERA); return@Button }
                busy = true; error = null
                controller.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) { processCapture(image) }
                    override fun onError(exception: ImageCaptureException) { busy = false; error = exception.message ?: "Photo capture failed. Try again." }
                })
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.CameraAlt, null)
                Spacer(Modifier.width(8.dp)); Text(if (busy) "Reading receipt..." else "Capture receipt")
            }
        }
    } else {
        val lines = candidates.orEmpty()
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { candidates = null; error = null }) { Icon(Icons.Rounded.ArrowBack, "Back") }; Text("Review receipt lines", style = MaterialTheme.typography.titleLarge) }
                Text("${lines.size} suggestions. Edit or untick anything OCR got wrong. Dates are not extracted.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (lines.isEmpty()) item { Text("No lines were recognized. Try another photo or enter products manually.") }
            itemsIndexed(lines) { index, line ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected.getOrElse(index) { true }, onCheckedChange = { value -> selected = selected.toMutableList().also { it[index] = value } })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(line.name, { name -> candidates = lines.toMutableList().also { it[index] = it[index].copy(name = name) } }, label = { Text("Product") }, singleLine = true)
                            OutlinedTextField(line.quantity.toString(), { quantity -> candidates = lines.toMutableList().also { it[index] = it[index].copy(quantity = quantity.toDoubleOrNull() ?: 0.0) } }, label = { Text("Quantity") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                            OutlinedTextField(priceDrafts.getOrElse(index) { "" }, { price -> priceDrafts = priceDrafts.toMutableList().also { it[index] = price }; candidates = lines.toMutableList().also { it[index] = it[index].copy(price = price.toDoubleOrNull()) } }, label = { Text("Price (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        }
                    }
                }
            }
            item { Button(onClick = { onConfirm(lines.filterIndexed { index, candidate -> selected.getOrElse(index) { true } && candidate.name.isNotBlank() }) }, enabled = lines.indices.any { i -> selected.getOrElse(i) { true } && lines[i].name.isNotBlank() && lines[i].quantity.isFinite() && lines[i].quantity > 0 && (priceDrafts.getOrElse(i) { "" }.isBlank() || priceDrafts[i].toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true) }, modifier = Modifier.fillMaxWidth()) { Text("Confirm selected products") } }
            item { TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
        }
    }
}
