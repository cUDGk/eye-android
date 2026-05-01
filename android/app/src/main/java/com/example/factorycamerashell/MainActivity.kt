package com.example.factorycamerashell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FactoryCameraApp()
        }
    }
}

private enum class Screen {
    Capture,
    Review,
    Sending,
    Success,
    Error,
    Settings
}

@Composable
private fun FactoryCameraApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.Capture) }
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastError by remember { mutableStateOf<SendOutcome.Failure?>(null) }
    var lastDescription by remember { mutableStateOf("") }
    var lastResizeMax by remember { mutableIntStateOf(0) }
    var userContext by remember { mutableStateOf("") }
    var sourceWasGallery by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingImageUri?.let { uri ->
                capturedImageUri = uri
                capturedBitmap = loadPreviewBitmap(context, uri)
                screen = Screen.Review
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            capturedImageUri = uri
            capturedBitmap = loadPreviewBitmap(context, uri)
            screen = Screen.Review
        }
    }

    fun launchStandardCamera() {
        val uri = createCaptureUri(context)
        pendingImageUri = uri
        sourceWasGallery = false
        cameraLauncher.launch(uri)
    }

    fun launchGalleryPicker() {
        sourceWasGallery = true
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun resetCaptureState() {
        capturedBitmap = null
        capturedImageUri = null
        pendingImageUri = null
    }

    fun startSend(resizeMax: Int) {
        val uri = capturedImageUri ?: return
        lastResizeMax = resizeMax
        screen = Screen.Sending
        scope.launch {
            when (val outcome = TelegramSender.sendPhoto(context, uri, resizeMax, userContext)) {
                is SendOutcome.Success -> {
                    lastDescription = outcome.description
                    playSuccessSound(context)
                    vibrate(context, longArrayOf(0, 60, 40, 60))
                    screen = Screen.Success
                }
                is SendOutcome.Failure -> {
                    lastError = outcome
                    vibrate(context, longArrayOf(0, 250))
                    screen = Screen.Error
                }
            }
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = FactoryColors.Background
        ) {
            when (screen) {
                Screen.Capture -> CaptureScreen(
                    onCapture = { launchStandardCamera() },
                    onPickGallery = { launchGalleryPicker() },
                    onSettings = { screen = Screen.Settings }
                )

                Screen.Review -> ReviewScreen(
                    bitmap = capturedBitmap,
                    userContext = userContext,
                    onContextChange = { userContext = it },
                    onBack = {
                        resetCaptureState()
                        screen = Screen.Capture
                    },
                    onRetake = {
                        resetCaptureState()
                        launchStandardCamera()
                    },
                    onSendSmall = { startSend(1280) },
                    onSendFull = { startSend(0) }
                )

                Screen.Sending -> SendingScreen()

                Screen.Success -> SuccessScreen(
                    description = lastDescription,
                    retakeLabel = if (sourceWasGallery) "追加でインポート" else "追加で撮影",
                    onDismiss = {
                        resetCaptureState()
                        screen = Screen.Capture
                    },
                    onRetake = {
                        resetCaptureState()
                        if (sourceWasGallery) launchGalleryPicker() else launchStandardCamera()
                    }
                )

                Screen.Error -> ErrorScreen(
                    failure = lastError,
                    onRetry = { startSend(lastResizeMax) },
                    onBack = { screen = Screen.Review }
                )

                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Capture }
                )
            }
        }
    }
}

@Composable
private fun CaptureScreen(
    onCapture: () -> Unit,
    onPickGallery: () -> Unit,
    onSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        FullScreenButton(
            text = "\u64ae\u5f71",
            onClick = onCapture,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        ThreeColorDivider()
        FullScreenButton(
            text = "\u753b\u50cf\u3092\u9078\u3076",
            onClick = onPickGallery,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        ThreeColorDivider()
        FullScreenButton(
            text = "\u8a2d\u5b9a",
            onClick = onSettings,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun ReviewScreen(
    bitmap: Bitmap?,
    userContext: String,
    onContextChange: (String) -> Unit,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onSendSmall: () -> Unit,
    onSendFull: () -> Unit
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FactoryColors.Background)
            .padding(start = 16.dp, top = 52.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .border(2.dp, FactoryColors.Border),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "\u64ae\u5f71\u753b\u50cf",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "NO IMAGE",
                    color = FactoryColors.TextMuted,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        OutlinedTextField(
            value = userContext,
            onValueChange = onContextChange,
            label = { Text("\u80cc\u666f\u60c5\u5831 (\u4efb\u610f)", color = FactoryColors.TextMuted) },
            placeholder = { Text("\u4f8b: \u3053\u3053\u306f\u6c34\u65cf\u9928\u3002\u6d77\u6708\u306e\u524d\u306b\u3044\u308b\u3002", color = FactoryColors.TextMuted.copy(alpha = 0.5f)) },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = FactoryColors.TextPrimary,
                unfocusedTextColor = FactoryColors.TextPrimary,
                focusedContainerColor = FactoryColors.Panel,
                unfocusedContainerColor = FactoryColors.Panel,
                focusedBorderColor = FactoryColors.LineBlue,
                unfocusedBorderColor = FactoryColors.Border
            )
        )

        FactoryButton(
            text = "\u53d6\u308a\u76f4\u3057",
            onClick = onRetake,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FactoryButtonWithSubtitle(
                title = "\u7e2e\u5c0f\u3057\u3066\u9001\u4fe1",
                subtitle = "\u9ad8\u901f / 1280px",
                onClick = onSendSmall,
                modifier = Modifier.weight(1f)
            )
            FactoryButtonWithSubtitle(
                title = "\u305d\u306e\u307e\u307e\u9001\u4fe1",
                subtitle = "\u9ad8\u753b\u8cea / \u539f\u5bf8",
                onClick = onSendFull,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FactoryButtonWithSubtitle(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FactoryColors.ActionButton,
            contentColor = FactoryColors.ButtonText
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = FactoryColors.TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    var autoFocus by remember { mutableStateOf(true) }
    var triggerMode by remember { mutableStateOf(false) }
    var saveOriginal by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(false) }
    var beep by remember { mutableStateOf(true) }
    var exposure by remember { mutableFloatStateOf(42f) }
    var gain by remember { mutableFloatStateOf(18f) }
    var brightness by remember { mutableFloatStateOf(50f) }
    var contrast by remember { mutableFloatStateOf(50f) }
    var sharpness by remember { mutableFloatStateOf(30f) }
    var resolutionIndex by remember { mutableIntStateOf(2) }
    var formatIndex by remember { mutableIntStateOf(0) }
    var whiteBalanceIndex by remember { mutableIntStateOf(0) }
    var rotationIndex by remember { mutableIntStateOf(0) }

    val resolutions = listOf("1280 x 720", "1920 x 1080", "\u6700\u9ad8\u753b\u8cea")
    val formats = listOf("JPEG", "PNG", "RAW")
    val whiteBalances = listOf("AUTO", "DAYLIGHT", "LED")
    val rotations = listOf("0", "90", "180", "270")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FactoryColors.Background)
            .padding(start = 12.dp, top = 48.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CAMERA SETTINGS",
                color = FactoryColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp
            )
            SettingReadout(label = "\u63a5\u7d9a\u30ab\u30e1\u30e9", value = "\u6a19\u6e96\u30ab\u30e1\u30e9")
            SettingReadout(label = "\u64ae\u5f71\u65b9\u5f0f", value = "\u6a19\u6e96\u30ab\u30e1\u30e9 / \u30d5\u30eb\u30b5\u30a4\u30ba\u4fdd\u5b58")
            SettingReadout(label = "\u30ab\u30e1\u30e9ID", value = "DEFAULT")
            SettingChoice(label = "\u89e3\u50cf\u5ea6", value = resolutions[resolutionIndex]) {
                resolutionIndex = (resolutionIndex + 1) % resolutions.size
            }
            SettingChoice(label = "\u4fdd\u5b58\u5f62\u5f0f", value = formats[formatIndex]) {
                formatIndex = (formatIndex + 1) % formats.size
            }
            SettingChoice(label = "\u30db\u30ef\u30a4\u30c8\u30d0\u30e9\u30f3\u30b9", value = whiteBalances[whiteBalanceIndex]) {
                whiteBalanceIndex = (whiteBalanceIndex + 1) % whiteBalances.size
            }
            SettingChoice(label = "\u56de\u8ee2", value = "${rotations[rotationIndex]} deg") {
                rotationIndex = (rotationIndex + 1) % rotations.size
            }
            SettingSwitch(label = "\u30aa\u30fc\u30c8\u30d5\u30a9\u30fc\u30ab\u30b9", checked = autoFocus, onCheckedChange = { autoFocus = it })
            SettingSwitch(label = "\u5916\u90e8\u30c8\u30ea\u30ac\u30fc", checked = triggerMode, onCheckedChange = { triggerMode = it })
            SettingSwitch(label = "\u539f\u753b\u50cf\u3092\u4fdd\u5b58", checked = saveOriginal, onCheckedChange = { saveOriginal = it })
            SettingSwitch(label = "\u30ac\u30a4\u30c9\u7dda\u8868\u793a", checked = showGrid, onCheckedChange = { showGrid = it })
            SettingSwitch(label = "\u64ae\u5f71\u97f3", checked = beep, onCheckedChange = { beep = it })
            SettingSlider(label = "\u9732\u5149", value = exposure, onValueChange = { exposure = it }, suffix = "ms")
            SettingSlider(label = "\u30b2\u30a4\u30f3", value = gain, onValueChange = { gain = it }, suffix = "dB")
            SettingSlider(label = "\u660e\u308b\u3055", value = brightness, onValueChange = { brightness = it }, suffix = "%")
            SettingSlider(label = "\u30b3\u30f3\u30c8\u30e9\u30b9\u30c8", value = contrast, onValueChange = { contrast = it }, suffix = "%")
            SettingSlider(label = "\u30b7\u30e3\u30fc\u30d7\u30cd\u30b9", value = sharpness, onValueChange = { sharpness = it }, suffix = "%")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallActionButton(text = "\u521d\u671f\u5316", onClick = {
                    autoFocus = true
                    triggerMode = false
                    saveOriginal = true
                    showGrid = false
                    beep = true
                    exposure = 42f
                    gain = 18f
                    brightness = 50f
                    contrast = 50f
                    sharpness = 30f
                    resolutionIndex = 2
                }, modifier = Modifier.weight(1f))
                SmallActionButton(text = "\u30c6\u30b9\u30c8\u64ae\u5f71", onClick = {}, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        FactoryButton(
            text = "\u623b\u308b",
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ThreeColorDivider() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(FactoryColors.LineBlue)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(FactoryColors.LineOrange)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(FactoryColors.LineBlue)
        )
    }
}

@Composable
private fun SettingReadout(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FactoryColors.Panel)
            .border(1.dp, FactoryColors.Border)
            .padding(12.dp)
    ) {
        Text(text = label, color = FactoryColors.TextMuted, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, color = FactoryColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingChoice(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FactoryColors.Panel)
            .border(1.dp, FactoryColors.Border)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = FactoryColors.TextMuted, fontSize = 12.sp)
            Text(text = value, color = FactoryColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        SmallActionButton(text = "\u5909\u66f4", onClick = onClick, modifier = Modifier.width(92.dp))
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FactoryColors.Panel)
            .border(1.dp, FactoryColors.Border)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = FactoryColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    suffix: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FactoryColors.Panel)
            .border(1.dp, FactoryColors.Border)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = FactoryColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(text = "${value.toInt()} $suffix", color = FactoryColors.TextMuted, fontSize = 14.sp)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..100f)
    }
}

@Composable
private fun FullScreenButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FactoryColors.LargeButton,
            contentColor = FactoryColors.ButtonText
        )
    ) {
        Text(text = text, fontSize = 42.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FactoryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FactoryColors.ActionButton,
            contentColor = FactoryColors.ButtonText
        )
    ) {
        Text(text = text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SmallActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = FactoryColors.SecondaryButton,
            contentColor = FactoryColors.ButtonText
        )
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SendingScreen() {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FactoryColors.Background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(R.drawable.chair_loop)
                .build(),
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(220.dp)
                .height(293.dp)  // 220 * (1080/810) for 3:4 aspect
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "送信中…",
            color = FactoryColors.TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "TAILSCALE → PC → LLM",
            color = FactoryColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun SuccessScreen(
    description: String,
    retakeLabel: String,
    onDismiss: () -> Unit,
    onRetake: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FactoryColors.SuccessBackground)
            .padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BigCheckMark()
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "送信されました",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black.copy(alpha = 0.25f))
                .border(2.dp, Color.White)
                .padding(12.dp)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = description.ifBlank { "(コメント無し)" },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 22.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FactoryButton(
                text = "OK",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            FactoryButton(
                text = retakeLabel,
                onClick = onRetake,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BigCheckMark() {
    Box(
        modifier = Modifier
            .size(160.dp)
            .background(Color.White, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✓",
            color = FactoryColors.SuccessBackground,
            fontSize = 130.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun ErrorScreen(
    failure: SendOutcome.Failure?,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val copyText = failure?.toCopyableText() ?: "(no error info)"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FactoryColors.ErrorBackground)
            .padding(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "×",
                color = Color.White,
                fontSize = 110.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "ミスりましたよん",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black.copy(alpha = 0.35f))
                .border(2.dp, Color.White)
                .padding(12.dp)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (failure != null) {
                        Text(
                            text = failure.code,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = failure.titleJa,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = failure.titleEn,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "詳細 / Detail",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = failure.detail,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "考えられる原因",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = failure.hintJa,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Likely cause",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = failure.hintEn,
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    } else {
                        Text(
                            text = "(no error info)",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        SmallActionButton(
            text = "全部コピー / Copy all",
            onClick = {
                copyToClipboard(context, copyText)
                Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FactoryButton(
                text = "戻る",
                onClick = onBack,
                modifier = Modifier.weight(1f)
            )
            FactoryButton(
                text = "再送信",
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun playSuccessSound(context: Context) {
    runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.play()
    }
}

private fun vibrate(context: Context, pattern: LongArray) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("error_code", text))
}

private fun createCaptureUri(context: Context): Uri {
    val imageDir = File(context.cacheDir, "captures").apply { mkdirs() }
    val imageFile = File(imageDir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
    )
}

private fun loadPreviewBitmap(context: Context, uri: Uri): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }

    val maxPreviewSize = 2048
    var sampleSize = 1
    while (
        options.outWidth / sampleSize > maxPreviewSize ||
        options.outHeight / sampleSize > maxPreviewSize
    ) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    }
    return context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, decodeOptions)
    }
}

private object FactoryColors {
    val Background = Color(0xFF252525)
    val Panel = Color(0xFF303030)
    val Border = Color(0xFF666666)
    val LargeButton = Color(0xFF4A4A4A)
    val ActionButton = Color(0xFF4A4A4A)
    val SecondaryButton = Color(0xFF3F3F3F)
    val LineBlue = Color(0xFF1468B3)
    val LineOrange = Color(0xFFE07A18)
    val ButtonText = Color(0xFFF2F2F2)
    val TextPrimary = Color(0xFFE0E0E0)
    val TextMuted = Color(0xFFB0B0B0)
    val SuccessBackground = Color(0xFF128A2E)
    val ErrorBackground = Color(0xFFB02525)
}
