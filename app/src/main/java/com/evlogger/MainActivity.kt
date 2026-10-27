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
    private var devices: List<BluetoothDevice> = emptyList()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var gpsLat = 0.0
    @Volatile private var gpsLon = 0.0
    @Volatile private var gpsSpeed = 0.0

    private val refresh = object : Runnable {
        override fun run() {
            val v = Elm.lastValues
            info.text = buildString {
                append("Статус: ${Elm.status}\n")
                append("Запись: ${if (Elm.logging) "ИДЁТ" else "нет"}   CAN-монитор: ${if (Elm.monitoring) "ИДЁТ" else "нет"}\n")
                Elm.currentFile?.let { append("Файл: ${it.name}\n") }
                v.forEach { (k, x) -> append("$k = ${if (x.isEmpty()) "—" else x}\n") }
                if (Elm.lastRaw.isNotEmpty()) append("ответ адаптера: ${Elm.lastRaw}\n")
                append("gps_speed = %.1f км/ч".format(gpsSpeed))
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        spinner = Spinner(this); root.addView(spinner)
        btn("1. Подключиться к ELM327") { connect() }
        btn("2. Старт записи лога") { startLog() }
        btn("Стоп записи") { Elm.logging = false }
        btn("CAN-монитор: старт") { startMonitor() }
        btn("CAN-монитор: стоп") { Elm.monitoring = false }
        btn("Отправить файл (в чат / почту / мессенджер)") { shareFile() }

        info = TextView(this).apply { textSize = 13f }
        root.addView(info)
        cmdBox = EditText(this).apply { hint = "Команда ELM327, например ATRV или 22F190"; isSingleLine = true }
        root.addView(cmdBox)
        btn("Отправить команду") { sendCmd() }
        term = TextView(this).apply {
            textSize = 12f; movementMethod = ScrollingMovementMethod(); maxLines = 12
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
        Thread {
            try { Elm.connect(d) } catch (e: Exception) { Elm.status = "ошибка: ${e.message}" }
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
