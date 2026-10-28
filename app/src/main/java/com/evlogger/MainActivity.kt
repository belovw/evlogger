package com.evlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

class MainActivity : Activity() {
    private lateinit var spinner: Spinner
    private lateinit var info: TextView
    private lateinit var term: TextView
    private lateinit var cmdBox: EditText
    private lateinit var hdrBox: EditText
    private lateinit var stepBox: EditText
    private lateinit var dot: TextView
    private lateinit var dotLabel: TextView
    private lateinit var fromBox: EditText
    private lateinit var toBox: EditText
    private lateinit var didBox: EditText
    private var devices: List<BluetoothDevice> = emptyList()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var gpsLat = 0.0
    @Volatile private var gpsLon = 0.0
    @Volatile private var gpsSpeed = 0.0

    private val refresh = object : Runnable {
        override fun run() {
            val v = Elm.lastValues
            val now = System.currentTimeMillis()
            val (color, label) = when {
                Elm.connecting -> Pair(0xFFF9A825.toInt(), "подключаюсь")
                Elm.connected && Elm.logging && now - Elm.lastDataMs > 10000 -> Pair(0xFFEF6C00.toInt(), "адаптер есть, машина не отвечает")
                Elm.connected -> Pair(0xFF2E7D32.toInt(), "подключено")
                else -> Pair(0xFFC62828.toInt(), "нет связи")
            }
            dot.setTextColor(color); dotLabel.text = label; dotLabel.setTextColor(color)
            info.text = buildString {
                append("Версия приложения: ${Elm.appVersion}\n")
                append("Статус: ${Elm.status}\n")
                append("Запись: ${if (Elm.logging) "ИДЁТ" else "нет"}   CAN-монитор: ${if (Elm.monitoring) "ИДЁТ" else "нет"}\n")
                Elm.currentFile?.let { append("Файл: ${it.name}\n") }
                if (Elm.scanning || Elm.scanProgress.isNotEmpty()) append("Сканер: ${Elm.scanProgress}\n")
                v.forEach { (k, x) -> append("$k = ${if (x.isEmpty()) "—" else x}\n") }
                if (Elm.lastRaw.isNotEmpty()) append("ответ адаптера: ${Elm.lastRaw}\n")
                append("gps_speed = %.1f км/ч".format(gpsSpeed))
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Elm.appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply { text = "EV Logger"; textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dotLabel = TextView(this).apply { textSize = 12f; setPadding(0, 0, 12, 0) }
        dot = TextView(this).apply { text = "●"; textSize = 30f }
        header.addView(dotLabel); header.addView(dot)
        root.addView(header)
        spinner = Spinner(this); root.addView(spinner)
        btn("1. Подключиться к ELM327") { connect() }
        btn("2. Старт записи лога") { startLog() }
        btn("■ СТОП (запись, монитор, сканер)") { stopAll() }
        btn("CAN-монитор: старт") { startMonitor() }
        btn("Отправить файл (в чат / почту / мессенджер)") { shareFile() }

        root.addView(TextView(this).apply {
            text = "\nПоиск данных батареи (Mode 22, только ЧТЕНИЕ). Только на стоянке, зажигание включено, двигаться нельзя. Запрос идёт всем блокам, в файле видно, кто ответил."
            textSize = 12f
        })
        fun field(hintText: String, def: String) = EditText(this).apply {
            hint = hintText; setText(def); isSingleLine = true; root.addView(this)
        }
        hdrBox = field("Адрес запроса: 7DF = всем блокам (рекомендуется)", "7DF")
        fromBox = field("DID с (hex)", "0000")
        toBox = field("DID по (hex)", "FFFF")
        stepBox = field("Шаг перебора (1 = каждый DID, 16 = каждый 16-й)", "1")
        didBox = field("DID для мониторинга: 0100 0801-0820 ... (пусто = все найденные последним сканом)", "")
        btn("Опознать блоки (кто отвечает на Mode 22)") { startIdent() }
        btn("Найти адреса блоков (700-7FF)") { startFindEcus() }
        btn("Опознать найденные блоки (названия)") { startIdentEcus() }
        btn("Обзор всех блоков (диапазон и шаг из полей)") { startSurvey() }
        btn("Сканер Mode 22: старт") { startScan() }
        btn("Мониторинг DID: старт") { startWatch() }

        info = TextView(this).apply { textSize = 13f }
        root.addView(info)
        cmdBox = EditText(this).apply { hint = "Команда ELM327, например ATRV или 22F190"; isSingleLine = true }
        root.addView(cmdBox)
        btn("Отправить команду") { sendCmd() }
        btn("Скопировать текст терминала") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", term.text))
            toast("Скопировано")
        }
        term = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(term)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        askPermissions()
        loadDevices()
        startGps()
        ui.post(refresh)
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        val need = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        loadDevices(); startGps()
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        try {
            val ad: BluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            devices = ad.bondedDevices.toList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { "${it.name} (${it.address})" })
            val saved = getSharedPreferences("evlogger", Context.MODE_PRIVATE).getString("last_mac", null)
            val idx = devices.indexOfFirst { it.address == saved }
            if (idx >= 0) spinner.setSelection(idx)
        } catch (e: SecurityException) {
            toast("Нужно разрешение Bluetooth")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, object : LocationListener {
            override fun onLocationChanged(l: Location) {
                gpsLat = l.latitude; gpsLon = l.longitude; gpsSpeed = l.speed * 3.6
            }
        })
    }

    private fun connect() {
        val d = devices.getOrNull(spinner.selectedItemPosition) ?: return toast("Сначала спарь ELM327 в настройках Bluetooth телефона")
        Elm.status = "подключаюсь..."
        Elm.connecting = true
        Thread {
            try {
                Elm.connect(d)
                getSharedPreferences("evlogger", Context.MODE_PRIVATE).edit().putString("last_mac", d.address).apply()
            } catch (e: Exception) { Elm.status = "ошибка: ${e.message}" }
            Elm.connecting = false
        }.start()
    }

    private fun startLog() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (Elm.logging) return
        startForegroundService(Intent(this, KeepAliveService::class.java))
        val pids = Elm.loadPids(assets.open("pids.txt").bufferedReader().readText())
        val custom = java.io.File(getExternalFilesDir(null), "pids.txt")
        val list = if (custom.exists()) Elm.loadPids(custom.readText()) else pids
        Thread { Elm.logLoop(getExternalFilesDir(null)!!, list) { Triple(gpsLat, gpsLon, gpsSpeed) } }.start()
    }

    private fun stopAll() {
        Elm.logging = false
        Elm.monitoring = false
        Elm.scanning = false
        toast("Остановлено")
    }

    private fun busy() = Elm.logging || Elm.monitoring || Elm.scanning

    private fun startIdent() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.identify(getExternalFilesDir(null)!!) }.start()
    }

    private fun startFindEcus() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.findEcus(getExternalFilesDir(null)!!) }.start()
    }

    private fun startIdentEcus() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.identifyEcus(getExternalFilesDir(null)!!) }.start()
    }

    private fun startSurvey() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        val a = fromBox.text.toString().trim().toIntOrNull(16)
        val b = toBox.text.toString().trim().toIntOrNull(16)
        if (a == null || b == null || a > b || b > 0xFFFF) return toast("Проверь диапазон (hex)")
        val st = stepBox.text.toString().trim().toIntOrNull() ?: 1
        if (st < 1 || st > 4096) return toast("Шаг от 1 до 4096")
        // в поле «Адрес запроса» можно перечислить блоки через пробел (например 7A0 7A4 7B5); 7DF или пусто = все
        val only = hdrBox.text.toString().uppercase().split(Regex("[\\s,]+")).filter { it.length == 3 && it != "7DF" }.toSet()
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.surveyEcus(getExternalFilesDir(null)!!, a, b, st, only) }.start()
    }

    private fun startScan() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        val h = hdrBox.text.toString().trim().uppercase()
        val a = fromBox.text.toString().trim().toIntOrNull(16)
        val b = toBox.text.toString().trim().toIntOrNull(16)
        if (h.toIntOrNull(16) == null || a == null || b == null || a > b || b > 0xFFFF) return toast("Проверь адрес и диапазон (hex)")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        val st = stepBox.text.toString().trim().toIntOrNull() ?: 1
        if (st < 1 || st > 4096) return toast("Шаг от 1 до 4096")
        Thread { Elm.scanDids(getExternalFilesDir(null)!!, h, a, b, st) }.start()
    }

    private fun startWatch() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        val h = hdrBox.text.toString().trim().uppercase()
        if (h.toIntOrNull(16) == null) return toast("Проверь адрес блока")
        val typed = ArrayList<String>()
        for (tok in didBox.text.toString().trim().uppercase().split(Regex("[\\s,;]+"))) {
            if (tok.isEmpty()) continue
            if (tok.contains("-")) {
                val p = tok.split("-")
                val x = p[0].toIntOrNull(16); val y = p.getOrNull(1)?.toIntOrNull(16)
                if (x != null && y != null && x <= y && y - x < 512) for (v in x..y) typed.add("%04X".format(v))
            } else if (tok.length == 4 && tok.toIntOrNull(16) != null) typed.add(tok)
        }
        val dir = getExternalFilesDir(null)!!
        val list = if (typed.isNotEmpty()) typed else Elm.found.toList().ifEmpty { Elm.loadFoundFromFile(dir) }
        if (list.isEmpty()) return toast("Нет DID: сначала скан или впиши вручную")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.watchDids(getExternalFilesDir(null)!!, h, list) }.start()
    }

    private fun startMonitor() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (Elm.logging) return toast("Останови запись лога")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.monitorLoop(getExternalFilesDir(null)!!) }.start()
    }

    private fun sendCmd() {
        val c = cmdBox.text.toString().trim()
        if (c.isEmpty()) return
        if (Elm.logging || Elm.monitoring) return toast("Останови запись/монитор")
        Thread {
            val r = Elm.send(c)
            ui.post { term.append("> $c\n$r\n") }
        }.start()
    }

    private fun shareFile() {
        val f = Elm.currentFile ?: getExternalFilesDir(null)?.listFiles()?.maxByOrNull { it.lastModified() }
            ?: return toast("Файлов пока нет")
        val uri = FileProvider.getUriForFile(this, "com.evlogger.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Отправить ${f.name}"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
