package com.votol.controller.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.votol.controller.model.BleState
import com.votol.controller.model.LogEntry
import com.votol.controller.model.VotolMonitorData

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) viewModel.startScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VotolTheme {
                VotolApp(
                    viewModel = viewModel,
                    onStartScan = { requestPermissionsAndScan() }
                )
            }
        }
    }

    private fun requestPermissionsAndScan() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        }
        permLauncher.launch(perms.toTypedArray())
    }
}

// ── Theme ────────────────────────────────────────────────────────────────────

@Composable
fun VotolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary    = Color(0xFF00E5FF),
            secondary  = Color(0xFF1DE9B6),
            background = Color(0xFF0A0E1A),
            surface    = Color(0xFF121828),
            error      = Color(0xFFFF5252),
            onPrimary  = Color(0xFF000000),
            onBackground = Color(0xFFE0E0E0),
            onSurface  = Color(0xFFE0E0E0)
        ),
        content = content
    )
}

// ── Root App ──────────────────────────────────────────────────────────────────

@Composable
fun VotolApp(viewModel: MainViewModel, onStartScan: () -> Unit) {
    val bleState by viewModel.bleState.collectAsState()
    val data by viewModel.monitorData.collectAsState()
    val devices by viewModel.scannedDevices.collectAsState()
    val log by viewModel.log.collectAsState()
    val faults by viewModel.faults.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VotolTopBar(bleState) },
        bottomBar = {
            if (bleState is BleState.Connected) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Gösterge", "Parametreler", "Hatalar", "Log").forEachIndexed { i, title ->
                        NavigationBarItem(
                            selected = selectedTab == i,
                            onClick = { selectedTab = i },
                            icon = {
                                Icon(
                                    when(i) {
                                        0 -> Icons.Default.Speed
                                        1 -> Icons.Default.Tune
                                        2 -> Icons.Default.Warning
                                        else -> Icons.Default.Timeline
                                    },
                                    contentDescription = title
                                )
                            },
                            label = { Text(title, fontSize = 10.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (bleState) {
                is BleState.Disconnected, is BleState.Error -> {
                    ScanScreen(
                        devices = devices,
                        bleState = bleState,
                        onScan = onStartScan,
                        onConnect = viewModel::connect
                    )
                }
                is BleState.Scanning, is BleState.Connecting -> {
                    ConnectingScreen(bleState, devices, viewModel::connect)
                }
                is BleState.Connected -> {
                    when (selectedTab) {
                        0 -> MonitorScreen(data, faults)
                        1 -> ParamScreen(viewModel)
                        2 -> FaultScreen(faults)
                        3 -> LogScreen(log, viewModel::clearLog)
                    }
                }
            }
        }
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VotolTopBar(bleState: BleState) {
    val (statusColor, statusText) = when (bleState) {
        is BleState.Connected    -> Color(0xFF00E5FF) to "● ${bleState.deviceName}"
        is BleState.Connecting   -> Color(0xFFFFD740) to "⟳ ${bleState.deviceName}"
        is BleState.Scanning     -> Color(0xFF1DE9B6) to "Taranıyor…"
        is BleState.Error        -> Color(0xFFFF5252) to "✕ ${bleState.message}"
        is BleState.Disconnected -> Color(0xFF616161) to "Bağlantı yok"
    }
    TopAppBar(
        title = {
            Column {
                Text("Votol Controller", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                Text(statusText, fontSize = 11.sp, color = statusColor)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D1220))
    )
}

// ── Scan Screen ───────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(
    devices: List<BluetoothDevice>,
    bleState: BleState,
    onScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // Hero icon
        Box(
            Modifier
                .size(100.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0xFF00E5FF33), Color.Transparent)),
                    RoundedCornerShape(50.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(56.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("Votol BLE Bağlantısı", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE0E0E0))
        Text("HC-05 / HM-10 modülünü açın", fontSize = 13.sp, color = Color(0xFF9E9E9E))

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onScan,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Default.Search, null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("BLE Tara", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        if (bleState is BleState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(bleState.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Bulunan Cihazlar", color = Color(0xFF9E9E9E), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    DeviceItem(device, onConnect)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(device: BluetoothDevice, onConnect: (BluetoothDevice) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onConnect(device) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2235)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BluetoothSearching, null, tint = Color(0xFF00E5FF))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name ?: "Bilinmeyen", fontWeight = FontWeight.SemiBold, color = Color(0xFFE0E0E0))
                Text(device.address, fontSize = 11.sp, color = Color(0xFF757575), fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF00E5FF))
        }
    }
}

// ── Connecting Screen ────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@Composable
fun ConnectingScreen(bleState: BleState, devices: List<BluetoothDevice>, onConnect: (BluetoothDevice) -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color(0xFF00E5FF))
        Spacer(Modifier.height(16.dp))
        val msg = when (bleState) {
            is BleState.Scanning -> "BLE taranıyor…"
            is BleState.Connecting -> "${bleState.deviceName} bağlanılıyor…"
            else -> ""
        }
        Text(msg, color = Color(0xFF9E9E9E))

        if (bleState is BleState.Scanning && devices.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            LazyColumn(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    DeviceItem(device, onConnect)
                }
            }
        }
    }
}

// ── Monitor Screen ────────────────────────────────────────────────────────────

@Composable
fun MonitorScreen(data: VotolMonitorData?, faults: List<String>) {
    if (data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF00E5FF))
                Spacer(Modifier.height(12.dp))
                Text("Veri bekleniyor…", color = Color(0xFF9E9E9E))
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Fault banner
        if (data.hasFault) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1010)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF5252))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            faults.joinToString(" • "),
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Durum rozeti
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(data.controllerState.label, Color(0xFF00E5FF))
                StatusBadge("Vites: ${data.gearLabel}", Color(0xFF1DE9B6))
                if (data.isRegen) StatusBadge("Regen", Color(0xFFFFD740))
                if (data.isBrake) StatusBadge("Fren", Color(0xFFFF5252))
            }
        }

        // Ana metrikler - büyük
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigMetricCard("Voltaj", "%.1f".format(data.voltage), "V", Color(0xFF00E5FF), Modifier.weight(1f))
                BigMetricCard("Akım",   "%.1f".format(data.current), "A", Color(0xFF1DE9B6), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigMetricCard("Güç",    "%.0f".format(data.power),   "W", Color(0xFFFFD740), Modifier.weight(1f))
                BigMetricCard("RPM",    "${data.rpm}",                "",  Color(0xFFFF9100), Modifier.weight(1f))
            }
        }

        // Sıcaklık
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallMetricCard("Kontrolcü Sıcaklığı", "${data.controllerTemp}°C",
                    tempColor(data.controllerTemp), Modifier.weight(1f))
                SmallMetricCard("Harici Sıcaklık", "${data.externalTemp}°C",
                    tempColor(data.externalTemp), Modifier.weight(1f))
            }
        }
    }
}

fun tempColor(temp: Int): Color = when {
    temp > 80 -> Color(0xFFFF5252)
    temp > 60 -> Color(0xFFFFD740)
    else      -> Color(0xFF1DE9B6)
}

@Composable
fun BigMetricCard(label: String, value: String, unit: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121828)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, fontSize = 11.sp, color = Color(0xFF9E9E9E))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = accent)
                if (unit.isNotEmpty()) {
                    Text(unit, fontSize = 14.sp, color = accent.copy(0.7f),
                        modifier = Modifier.padding(bottom = 4.dp, start = 2.dp))
                }
            }
        }
    }
}

@Composable
fun SmallMetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121828)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Thermostat, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, fontSize = 10.sp, color = Color(0xFF757575))
                Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

// ── Param Screen ──────────────────────────────────────────────────────────────

@Composable
fun ParamScreen(viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Parametre Sayfaları", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE0E0E0))
        Spacer(Modifier.height(8.dp))
        Text(
            "Parametre okuma/yazma, Votol'un seri protokolüne bağlıdır. " +
            "Aşağıdaki butonlar örnek komutlar gönderir.",
            fontSize = 13.sp, color = Color(0xFF9E9E9E)
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            Triple(0x50.toByte(), "Sayfa 0x50 — Temel Motor Parametreleri") { viewModel.bleManager.sendParamRead(0x50) },
            Triple(0x51.toByte(), "Sayfa 0x51 — Hız Limitleri")             { viewModel.bleManager.sendParamRead(0x51) },
            Triple(0x52.toByte(), "Sayfa 0x52 — Akım Ayarları")             { viewModel.bleManager.sendParamRead(0x52) },
            Triple(0x53.toByte(), "Sayfa 0x53 — Hall & Faz")                { viewModel.bleManager.sendParamRead(0x53) },
        ).forEach { (_, label, action) ->
            Button(
                onClick = action,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2235)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(label, color = Color(0xFF00E5FF), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A10)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFFFFD740).copy(0.4f))
        ) {
            Row(Modifier.padding(12.dp)) {
                Icon(Icons.Default.Warning, null, tint = Color(0xFFFFD740), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Parametre yazma işlemleri kontrolcü ayarlarını kalıcı değiştirebilir. " +
                    "Sadece değerlerini bildiğiniz parametreleri yazın.",
                    fontSize = 12.sp, color = Color(0xFFFFD740)
                )
            }
        }
    }
}

// ── Fault Screen ──────────────────────────────────────────────────────────────

@Composable
fun FaultScreen(faults: List<String>) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Hata Kodları", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE0E0E0))
        Spacer(Modifier.height(16.dp))
        if (faults.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF1DE9B6), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Hata yok", fontSize = 18.sp, color = Color(0xFF1DE9B6))
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(faults) { fault ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1010)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(0.4f))
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = Color(0xFFFF5252))
                            Spacer(Modifier.width(12.dp))
                            Text(fault, color = Color(0xFFFF5252), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── Log Screen ────────────────────────────────────────────────────────────────

@Composable
fun LogScreen(log: List<LogEntry>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Veri Logu", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E0E0), modifier = Modifier.weight(1f))
            Text("${log.size} kayıt", fontSize = 12.sp, color = Color(0xFF9E9E9E))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClear) {
                Icon(Icons.Default.DeleteSweep, null, tint = Color(0xFFFF5252))
            }
        }
        Spacer(Modifier.height(8.dp))

        // Tablo başlığı
        Row(Modifier.fillMaxWidth().background(Color(0xFF1A2235), RoundedCornerShape(8.dp)).padding(8.dp)) {
            listOf("Voltaj","Akım","RPM","Güç","Sıcaklık").forEach { h ->
                Text(h, fontSize = 10.sp, color = Color(0xFF9E9E9E), fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(log.reversed()) { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF121828), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    listOf(
                        "%.1f V".format(entry.voltage),
                        "%.1f A".format(entry.current),
                        "${entry.rpm}",
                        "%.0f W".format(entry.power),
                        "${entry.controllerTemp}°C"
                    ).forEach { v ->
                        Text(v, fontSize = 11.sp, color = Color(0xFFE0E0E0),
                            modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
