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
    private lateinit var probeBtn: Button
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
    private lateinit var bannerView: TextView
    private lateinit var tripBtn: Button
    private lateinit var chargeBtn: Button
    private var alertOpen = false
    private var rangeOpen = false
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var gpsLat = 0.0
    @Volatile private var gpsLon = 0.0
    @Volatile private var gpsSpeed = 0.0
    @Volatile private var gpsAlt = 0.0 // высота над уровнем моря по GPS, метры (для оценки продольного наклона дороги)
    @Volatile private var gpsMs = 0L

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
            val b = Homework.banner
            if (b.isEmpty()) bannerView.visibility = android.view.View.GONE else {
                val left = maxOf(0L, (Homework.stepEndMs - now) / 1000)
                bannerView.text = "▶ $b\nдо конца этапа: %d:%02d   Задание: %d%%".format(left / 60, left % 60, Homework.progressPct)
                bannerView.visibility = android.view.View.VISIBLE
            }
            if (GenSession.alertKind.isNotEmpty() && !alertOpen) showGenAlert()
            if (GenSession.rangeAsk.isNotEmpty() && !rangeOpen) showRangeDialog()
            if (TripTest.result.isNotEmpty() && !alertOpen) showTripResult()
            if (ChargeSession.result.isNotEmpty() && !alertOpen) {
                val t = ChargeSession.result; ChargeSession.result = ""; alertOpen = true
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Зарядка записана").setMessage(t).setCancelable(false).setPositiveButton("OK") { _, _ -> alertOpen = false }.show()
            }
            chargeBtn.text = if (ChargeSession.running) "🔌 КОНЕЦ зарядки (нажми, когда отключил пистолет)" else "🔌 Зарядка от станции: СТАРТ"
            tripBtn.text = if (TripTest.running) "🏁 ФИНИШ заезда (нажми в конечной точке)" else "🏁 Контрольный заезд: СТАРТ"
            if (gpsMs != 0L && now - gpsMs > 5000) gpsSpeed = 0.0   // GPS замёрз: не тянуть старую скорость в логи
            info.text = buildString {
                append("Версия приложения: ${Elm.appVersion}\n")
                if (GenSession.status.isNotEmpty()) append("${GenSession.status}\n")
                if (TripTest.status.isNotEmpty()) append("${TripTest.status}\n")
                if (ChargeSession.status.isNotEmpty()) append("${ChargeSession.status}\n")
                if (Elm.logging) append("${Ledger.status()}\n")
                if (GenSession.precision.isNotEmpty()) append("${GenSession.precision}\n")
                if (Probe.on && Elm.logging && Probe.status.isNotEmpty()) append("${Probe.status}\n")
                append("Статус: ${Elm.status}\n")
                append("Запись: ${if (Elm.logging) "ИДЁТ" else "нет"}   CAN-монитор: ${if (Elm.monitoring) "ИДЁТ" else "нет"}\n")
                Elm.currentFile?.let { append("Файл: ${it.name}\n") }
                if (Elm.scanning || Elm.scanProgress.isNotEmpty()) append("Сканер: ${Elm.scanProgress}\n")
                val age = (now - Elm.lastDataMs) / 1000
                if (v.isNotEmpty() && Elm.logging && age <= 6) v.forEach { (k, x) -> append("$k = ${if (x.isEmpty()) "—" else x}\n") }
                else if (v.isNotEmpty()) append("Показания машины не обновляются${if (Homework.running) " (идёт задание, читаются другие адреса)" else if (!Elm.logging) " (запись не идёт)" else ""}. Последние были ${if (Elm.lastDataMs == 0L) "—" else "$age с назад"}.\n")
                if (Elm.lastRaw.isNotEmpty()) append("ответ адаптера: ${Elm.lastRaw}\n")
                append("gps_speed = %.1f км/ч   gps_alt = %.0f м".format(gpsSpeed, gpsAlt))
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe.init(this)
        Ledger.init(this)
        Elm.appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        var target: LinearLayout = root
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { onClick() }
            target.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF607D8B.toInt()); setPadding(4, 28, 0, 4); target.addView(this)
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
        bannerView = TextView(this).apply {
            textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF000000.toInt()); setBackgroundColor(0xFFFFD54F.toInt())
            setPadding(24, 24, 24, 24); visibility = android.view.View.GONE
        }
        root.addView(bannerView)

        // --- Подключение и состояние (всегда наверху) ---
        spinner = Spinner(this); root.addView(spinner)
        btn("Подключиться к ELM327 (запись и разведка пойдут сами)") { connect() }
        info = TextView(this).apply { textSize = 13f; setPadding(4, 16, 4, 8) }
        root.addView(info)
        btn("■ СТОП (запись, разведка, задания)") { stopAll() }

        // --- Основное ---
        title("ЗАРЯДКА И ЗАПРАВКА")
        chargeBtn = btn("🔌 Зарядка от станции: СТАРТ") { ChargeSession.toggle(this, getExternalFilesDir(null)!!, { Triple(gpsLat, gpsLon, gpsSpeed) }) { toast(it) } }
        btn("⛽ Заправка (ввод литров)") { refuelDialog() }

        title("ЗАЕЗД")
        tripBtn = btn("🏁 Контрольный заезд: СТАРТ") { TripTest.toggle(this, getExternalFilesDir(null)!!, { Triple(gpsLat, gpsLon, gpsSpeed) }) { toast(it) } }
        btn("Итоги заездов / ввод литров") { tripSummary() }

        title("ДАННЫЕ")
        btn("★ ДОМАШНЕЕ ЗАДАНИЕ") { Homework.start(this, { Triple(gpsLat, gpsLon, gpsSpeed) }) { toast(it) } }
        probeBtn = btn("") { Probe.enable(!Probe.on); probeBtn.text = probeLabel() }
        probeBtn.text = probeLabel()
        btn("📤 Отправить всё и очистить") { sendAll() }

        title("ПРИЛОЖЕНИЕ")
        btn("🔋 Разрешить работу в фоне (если запись слетает после сворачивания)") { requestIgnoreBatteryOptimizations() }
        btn("Обновить приложение") { updateApp() }

        // --- Инструменты (свёрнуто) ---
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        val toolsBtn = btn("⚙ Инструменты разработчика ▸") { }
        toolsBtn.setOnClickListener {
            val open = tools.visibility != android.view.View.VISIBLE
            tools.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            toolsBtn.text = if (open) "⚙ Инструменты разработчика ▾" else "⚙ Инструменты разработчика ▸"
        }
        root.addView(tools)
        target = tools
        tools.addView(TextView(this).apply { text = "\nОтправка на GitHub (приватный репозиторий!). Пусто = отправка zip через «Поделиться»."; textSize = 12f })
        val sp = getSharedPreferences("evlogger", Context.MODE_PRIVATE)
        val ghRepo = EditText(this).apply { hint = "Репозиторий: владелец/имя, например belovw/evlogger-data"; setText(sp.getString("gh_repo", "")); isSingleLine = true }
        val ghToken = EditText(this).apply {
            hint = if (sp.getString("gh_token", "").isNullOrBlank()) "Токен GitHub (Contents: read/write только на этот репозиторий)" else "Токен сохранён (введи новый, чтобы заменить)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true
        }
        tools.addView(ghRepo); tools.addView(ghToken)
        target = tools
        btn("Сохранить настройки GitHub") {
            val e = sp.edit().putString("gh_repo", ghRepo.text.toString().trim())
            if (ghToken.text.isNotBlank()) { e.putString("gh_token", ghToken.text.toString().trim()); ghToken.setText("") }
            e.apply(); toast("Сохранено")
        }
        btn("Отправить zip через «Поделиться» (запасной путь)") { shareAll() }
        btn("Запись лога: старт (обычно не нужна, стартует сама)") { startLog() }
        btn("CAN-монитор: старт") { startMonitor() }
        tools.addView(TextView(this).apply {
            text = "\nПоиск данных (Mode 22, только ЧТЕНИЕ). Только на стоянке, зажигание включено. Запрос идёт всем блокам, в файле видно, кто ответил."
            textSize = 12f
        })
        fun field(hintText: String, def: String) = EditText(this).apply {
            hint = hintText; setText(def); isSingleLine = true; tools.addView(this)
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
        cmdBox = EditText(this).apply { hint = "Команда ELM327, например ATRV или 22F190"; isSingleLine = true }
        tools.addView(cmdBox)
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
        tools.addView(term)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        GenSession.refreshPrecision(this, getExternalFilesDir(null)!!)
        askPermissions()
        requestIgnoreBatteryOptimizations()
        loadDevices()
        startGps()
        ui.post(refresh)
    }

    /** Чтобы Android не прибивал фоновый поток записи при сворачивании приложения (KeepAliveService
     *  формально foreground, но системная оптимизация батареи всё равно может душить процесс —
     *  особенно на урезанных прошивках автомагнитол). Показывает системный диалог "не оптимизировать
     *  батарею для этого приложения"; если производитель диалог игнорирует — своей настройки у него
     *  может не быть вовсе, тогда нужно искать "автозапуск"/"фоновая работа" в системных настройках ОС. */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Уже разрешено: приложение не подчиняется оптимизации батареи")
            return
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            toast("Не удалось открыть системный диалог: ${e.message}. Ищи вручную в настройках ОС: автозапуск / фоновая работа / защищённые приложения.")
        }
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

    override fun onResume() {
        super.onResume()
        if (!Elm.connected) loadDevices()
        val pend = pendingDelete
        if (pend.isNotEmpty() && !alertOpen) {
            pendingDelete = emptyList()
            android.app.AlertDialog.Builder(this).setTitle("Отправка прошла?")
                .setMessage("Удалить с телефона ${pend.size} отправленных файлов (логи, сканы и т.п.)?\nСлужебные файлы (сессии генерации, база разведки) и текущий лог остаются.")
                .setPositiveButton("Да, удалить") { _, _ -> var n = 0; pend.forEach { if (it.delete()) n++ }; toast("Удалено файлов: $n") }
                .setNegativeButton("Нет, оставить", null).show()
        }
    }

    private fun refuelDialog() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        fun f(h: String) = EditText(this).apply { hint = h; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; lay.addView(this) }
        lay.addView(TextView(this).apply { text = Ledger.status() + "\nЗаправка закрывает интервал: считается расход и баланс батареи."; textSize = 13f })
        val l = f("Залито, л (обязательно)"); val pr = f("Цена за литр, ₸ (необязательно)")
        val odo = f("Одометр, км (необязательно)"); val gas = f("Запас хода по бензину после заправки, км (необязательно)")
        val click = android.widget.CheckBox(this).apply { text = "Заправлено ровно до первого щелчка"; isChecked = true; lay.addView(this) }
        android.app.AlertDialog.Builder(this).setTitle("Заправка").setView(android.widget.ScrollView(this).apply { addView(lay) })
            .setPositiveButton("Записать") { _, _ ->
                fun n(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull() ?: Double.NaN
                val lit = n(l)
                if (lit.isNaN() || lit <= 0) return@setPositiveButton toast("Введи литры")
                val res = Ledger.refuel(getExternalFilesDir(null)!!, lit, n(pr).let { if (it.isNaN()) 0.0 else it }, n(odo), n(gas), click.isChecked)
                android.app.AlertDialog.Builder(this).setTitle("Итоги интервала").setMessage(res).setPositiveButton("OK", null).show()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun showTripResult() {
        val text = TripTest.result; TripTest.result = ""; alertOpen = true
        android.app.AlertDialog.Builder(this).setTitle("Заезд записан").setMessage(text)
            .setPositiveButton("OK") { _, _ -> alertOpen = false }.setCancelable(false).show()
    }

    private fun tripSummary() {
        val dir = getExternalFilesDir(null)!!
        val id = TripTest.lastId(dir)
        val et = EditText(this).apply { hint = "Залито до щелчка, л"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val b = android.app.AlertDialog.Builder(this).setTitle("Итоги заездов").setMessage(TripTest.summary(dir))
        if (id > 0) b.setView(et).setPositiveButton("Рассчитать с литрами") { _, _ ->
            val l = et.text.toString().replace(',', '.').toDoubleOrNull()
            if (l == null) toast("Введи литры") else android.app.AlertDialog.Builder(this).setTitle("Заезд №$id").setMessage(TripTest.setLiters(dir, id, l)).setPositiveButton("OK", null).show()
        }
        b.setNegativeButton("Закрыть", null).show()
    }

    private var pendingDelete: List<java.io.File> = emptyList()

    /** Служебные файлы: нужны приложению для работы, отправляются копией, но не удаляются. */
    private fun isKeep(f: java.io.File) = f.name.startsWith("gen_") && f.name.endsWith(".csv") && !f.name.contains("_old_") ||
        f.name == "probe_found.csv" || f.name.startsWith("trip_tests") || f.name.startsWith("charge_sessions") || f.name.startsWith("refuels") || f.name == "probe_const.csv" || f.name == "probe_progress.txt" || f.name == "pids.txt"

    private fun sendAll() {
        if (Elm.loggingActive) return toast("Файл лога ещё дописывается/закрывается — подожди пару секунд после «Стоп» и попробуй снова")
        val pr = getSharedPreferences("evlogger", Context.MODE_PRIVATE)
        val repo = pr.getString("gh_repo", "") ?: ""; val token = pr.getString("gh_token", "") ?: ""
        if (repo.isBlank() || token.isBlank()) return shareAll()
        val dir = getExternalFilesDir(null)!!
        val cur = Elm.currentFile
        val all = (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".apk") && !it.name.startsWith("evlogger_all_") }
        if (all.isEmpty()) return toast("Файлов пока нет")
        toast("Загружаю ${all.size} файлов на GitHub…")
        Thread {
            val r = Uploader.upload(all, repo, token) { Elm.scanProgress = it }
            Elm.scanProgress = ""
            var del = 0
            r.ok.forEach { f -> if (!isKeep(f) && !(Elm.logging && f == cur) && f.delete()) del++ }
            runOnUiThread {
                android.app.AlertDialog.Builder(this).setTitle(if (r.errors.isEmpty()) "Загружено" else "Загружено с ошибками")
                    .setMessage("На GitHub (${r.folder}): ${r.ok.size} из ${all.size}. Удалено с телефона: $del." +
                        (if (r.errors.isEmpty()) "" else "\n\nОшибки:\n" + r.errors.take(6).joinToString("\n")))
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun shareAll() {
        if (Elm.loggingActive) return toast("Файл лога ещё дописывается/закрывается — подожди пару секунд после «Стоп» и попробуй снова")
        val dir = getExternalFilesDir(null)!!
        dir.listFiles { x -> x.name.startsWith("evlogger_all_") }?.forEach { it.delete() }
        val cur = Elm.currentFile
        val all = (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".apk") && !it.name.endsWith(".zip") || (it.isFile && it.name.startsWith("gen_report_")) }
        if (all.isEmpty()) return toast("Файлов пока нет")
        val z = java.io.File(dir, "evlogger_all_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.zip")
        java.util.zip.ZipOutputStream(z.outputStream()).use { zo ->
            all.forEach { f -> zo.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(zo) }; zo.closeEntry() }
            zo.putNextEntry(java.util.zip.ZipEntry("state.txt"))
            zo.write(("version ${Elm.appVersion}\n${Probe.stateText()}\n${GenSession.precision}\n").toByteArray()); zo.closeEntry()
        }
        // удаляем только то, что не служебное и не пишется прямо сейчас
        pendingDelete = all.filter { !isKeep(it) && !(Elm.logging && it == cur) }
        val uri = FileProvider.getUriForFile(this, "com.evlogger.files", z)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Отправить всё (${all.size} файлов)"))
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        fun placeholder(msg: String) {
            devices = emptyList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(msg))
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            placeholder("⚠ Нет разрешения «Устройства поблизости» (Bluetooth). Выдай в настройках приложения")
            askPermissions(); return
        }
        try {
            val ad: BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (ad == null) return placeholder("⚠ Bluetooth недоступен на этом устройстве")
            if (!ad.isEnabled) return placeholder("⚠ Bluetooth выключен. Включи и вернись в приложение")
            devices = ad.bondedDevices.toList()
            if (devices.isEmpty()) return placeholder("⚠ Нет спаренных устройств. Спарь ELM327 (V-LINK) в настройках Bluetooth")
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { "${it.name} (${it.address})" })
            val saved = getSharedPreferences("evlogger", Context.MODE_PRIVATE).getString("last_mac", null)
            val idx = devices.indexOfFirst { it.address == saved }
            if (idx >= 0) {
                spinner.setSelection(idx)
                // автоподключение к последнему использованному ELM327, без нажатия кнопки —
                // чтобы не терять время (особенно важно на холодном старте ДВС)
                if (!Elm.connected && !Elm.connecting) connect()
            }
        } catch (e: SecurityException) {
            placeholder("⚠ Нет разрешения Bluetooth")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, object : LocationListener {
            override fun onLocationChanged(l: Location) {
                gpsLat = l.latitude; gpsLon = l.longitude; gpsSpeed = l.speed * 3.6; gpsMs = System.currentTimeMillis()
                if (l.hasAltitude()) gpsAlt = l.altitude
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
                if (Elm.connected && !Elm.logging) runOnUiThread {
                    startForegroundService(Intent(this, KeepAliveService::class.java))
                    Elm.startAutoLog(this, getExternalFilesDir(null)!!, { Triple(gpsLat, gpsLon, gpsSpeed) }, { gpsAlt })
                }
            } catch (e: Exception) { Elm.status = "ошибка: ${e.message}" }
            Elm.connecting = false
        }.start()
    }

    private fun startLog() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (Elm.logging) { Elm.autoLog = false; return toast("Запись уже идёт") }
        startForegroundService(Intent(this, KeepAliveService::class.java))
        val pids = Elm.loadPids(assets.open("pids.txt").bufferedReader().readText())
        val custom = java.io.File(getExternalFilesDir(null), "pids.txt")
        val list = if (custom.exists()) Elm.loadPids(custom.readText()) else pids
        Thread { Elm.logLoop(getExternalFilesDir(null)!!, list, { Triple(gpsLat, gpsLon, gpsSpeed) }, { gpsAlt }) }.start()
    }

    private fun shareGenReport(dir: java.io.File) {
        val z = GenSession.report(this, dir) ?: return toast("Нет данных генерации")
        val uri = FileProvider.getUriForFile(this, "com.evlogger.files", z)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Отправить отчёт генерации"))
    }

    private val entered = LinkedHashMap<String, String>()   // что уже введено в текущей цепочке окон

    private class Ask(val step: String, val what: String, val hint: String, val where: String, val optional: Boolean)

    private fun askInfo(ev: String): Ask = when (ev) {
        "trip_gas_start" -> Ask("СТАРТ заезда · шаг 1 из 3", "ЗАПАС ХОДА ПО БЕНЗИНУ, км", "км, например 850", "На панели приборов: запас хода по бензину (не общий).", false)
        "trip_tot_start" -> Ask("СТАРТ заезда · шаг 2 из 3", "ОБЩИЙ ЗАПАС ХОДА, км", "км, например 914", "На панели приборов: общий запас хода (бензин + электричество).", false)
        "trip_odo_start" -> Ask("СТАРТ заезда · шаг 3 из 3", "ОДОМЕТР, км", "км, например 12345", "Общий пробег машины. Можно пропустить.", true)
        "trip_gas_end" -> Ask("ФИНИШ заезда · шаг 1 из 3", "ЗАПАС ХОДА ПО БЕНЗИНУ, км", "км, например 583", "На панели приборов: запас хода по бензину (не общий).", false)
        "trip_tot_end" -> Ask("ФИНИШ заезда · шаг 2 из 3", "ОБЩИЙ ЗАПАС ХОДА, км", "км, например 639", "На панели приборов: общий запас хода.", false)
        "trip_odo_end" -> Ask("ФИНИШ заезда · шаг 3 из 3", "ОДОМЕТР, км", "км, например 12475", "Общий пробег машины. Можно пропустить.", true)
        "chg_kwh_end" -> Ask("Зарядка закончена · шаг 1 из 2", "СКОЛЬКО кВт·ч ОТДАЛА СТАНЦИЯ", "кВт·ч, например 18.4", "Число на экране станции. Можно пропустить.", true)
        "chg_price_end" -> Ask("Зарядка закончена · шаг 2 из 2", "ЦЕНА ЗА кВт·ч, ₸", "₸, например 55", "Тариф станции. Можно пропустить.", true)
        "start" -> Ask("Сессия генерации · СТАРТ", "ЗАПАС ХОДА ПО БЕНЗИНУ, км", "км, например 850", "Перед запуском двигателя. Если число не изменилось с прошлого раза, просто жми OK.", false)
        "end" -> Ask("Сессия генерации · КОНЕЦ", "ЗАПАС ХОДА ПО БЕНЗИНУ, км", "км, например 798", "ДВС заглушён. На панели приборов: запас хода по бензину.", false)
        else -> Ask("Замер перед заправкой", "ЗАПАС ХОДА ПО БЕНЗИНУ, км", "км, например 640", "Перед заправкой. На панели приборов: запас хода по бензину.", false)
    }

    private fun showRangeDialog() {
        val ev = GenSession.rangeAsk
        if (ev.isEmpty()) return
        rangeOpen = true
        val info = askInfo(ev)
        val group = ev.substringBeforeLast("_").let { if (ev.startsWith("trip")) (if (ev.endsWith("start")) "ts" else "te") else ev }
        if (ev == "trip_gas_start") entered.clear()
        if (ev == "trip_gas_end") { entered.keys.removeAll { it.startsWith("ts_") || it.startsWith("te_") } ; entered.clear() }
        val et = EditText(this).apply {
            hint = info.hint; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 24f
        }
        val pk = if (ev == "end" || ev.startsWith("trip") || ev.startsWith("chg")) null else GenSession.prevKm(this)
        if (pk != null) { et.setText("%.0f".format(pk)); et.selectAll() }
        val msg = buildString {
            append("ВВЕДИ: ${info.what}\n\n${info.where}")
            if (pk != null) append("\n\nПрошлое значение подставлено. Не изменилось: жми OK.")
            if (entered.isNotEmpty()) append("\n\nУже введено: " + entered.entries.joinToString("; ") { "${it.key} = ${it.value}" })
            if (info.optional) append("\n\n(необязательно: «Пропустить»)")
        }
        val shortName = info.what.substringBefore(",").lowercase()
        android.app.AlertDialog.Builder(this).setTitle(info.step).setMessage(msg).setView(et).setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().replace(',', '.').toDoubleOrNull()
                if (v != null) { entered[shortName] = "%.0f".format(v); toast("Принято: $shortName = ${"%.0f".format(v)}") }
                GenSession.submitRange(v); rangeOpen = false
            }
            .setNegativeButton("Пропустить") { _, _ -> GenSession.submitRange(null); rangeOpen = false }.show()
    }

    private fun showGenAlert() {
        val kind = GenSession.alertKind; val text = GenSession.alertText; val fuel = GenSession.alertFuel
        GenSession.alertKind = ""; alertOpen = true
        val dir = getExternalFilesDir(null)!!
        val b = android.app.AlertDialog.Builder(this).setCancelable(false)
        if (kind == "drop") {
            b.setTitle("⛔ Топливо ушло между сессиями").setMessage(text + "\n\nПродолжать этот цикл бессмысленно, если расход был по неучтённой причине.")
                .setPositiveButton("Закрыть цикл и отправить статистику") { _, _ ->
                    GenSession.voidCycle(this, dir, "падение топлива вне сессий"); alertOpen = false
                    shareGenReport(dir)
                    toast("Цикл закрыт без расчёта кВт·ч/л, начат новый. Заправься до щелчка перед следующей сессией.")
                }
                .setNeutralButton("Продолжить этот цикл") { _, _ -> GenSession.acceptFuel(this, fuel); alertOpen = false; toast("Принято. Нажми СТАРТ ещё раз.") }
                .setNegativeButton("Отмена") { _, _ -> alertOpen = false }
        } else {
            b.setTitle("⛽ Была заправка").setMessage(text).setPositiveButton("OK") { _, _ -> alertOpen = false; genSummary() }
        }
        b.show()
    }

    private fun genSummary() {
        val dir = getExternalFilesDir(null)!!
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        lay.addView(TextView(this).apply { text = GenSession.totals(this@MainActivity, dir); textSize = 14f })
        val l1 = EditText(this).apply { hint = "Залито до щелчка, л (например 8.62)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val l2 = EditText(this).apply { hint = "Расходомер (Car Scanner) за цикл, л — необязательно"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        lay.addView(l1); lay.addView(l2)
        android.app.AlertDialog.Builder(this).setTitle("Итоги генерации").setView(android.widget.ScrollView(this).apply { addView(lay) })
            .setPositiveButton("Закрыть цикл") { _, _ ->
                val liters = l1.text.toString().replace(',', '.').toDoubleOrNull()
                if (liters == null || liters <= 0) return@setPositiveButton toast("Введи литры")
                val shown = l2.text.toString().replace(',', '.').toDoubleOrNull()
                val res = GenSession.closeCycle(this, dir, liters, shown)
                android.app.AlertDialog.Builder(this).setTitle("Результат").setMessage(res).setPositiveButton("OK", null).show()
            }
            .setNeutralButton("Недействителен (залито не до щелчка)") { _, _ ->
                val liters = l1.text.toString().replace(',', '.').toDoubleOrNull()
                GenSession.voidCycle(this, dir, "заправка не до щелчка" + (if (liters != null) " (залито $liters л)" else ""))
                android.app.AlertDialog.Builder(this).setTitle("Цикл закрыт без расчёта")
                    .setMessage("Сессии сохранены в данных с пометкой. Следующая заправка до щелчка станет новой точкой отсчёта; литры именно этой заправки в серию не идут.")
                    .setPositiveButton("OK", null).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun stopAll() {
        Homework.abort = true
        Elm.logging = false
        Elm.monitoring = false
        Elm.scanning = false
        Elm.abortNow = true // принудительно прерываем текущее ожидание ответа в send(), не ждём его таймаут
        toast("Останавливаю…")
        Thread {
            Elm.awaitStopped(8000) // дожидаемся, пока файл лога реально закрыт — иначе «Отправить» может словить его на полпути
            runOnUiThread { toast("Остановлено, файл закрыт — можно отправлять") }
        }.start()
    }

    private fun busy(): Boolean { Elm.releaseAutoLog(); return Elm.logging || Elm.monitoring || Elm.scanning }

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
        Elm.releaseAutoLog()
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

    private fun probeLabel() = if (Probe.on) "🔎 Фоновая разведка DID: ВКЛ (нажми = выкл)" else "🔎 Фоновая разведка DID: ВЫКЛ (нажми = вкл)"

    private fun shareProbe() {
        val dir = getExternalFilesDir(null)!!
        val files = ArrayList<java.io.File>()
        java.io.File(dir, "probe_found.csv").takeIf { it.exists() }?.let { files.add(it) }
        java.io.File(dir, "probe_const.csv").takeIf { it.exists() }?.let { files.add(it) }
        dir.listFiles { x -> x.name.startsWith("log_") && x.name.endsWith(".csv") }?.sortedByDescending { it.lastModified() }?.take(3)?.let { files.addAll(it) }
        if (files.isEmpty()) return toast("Нет данных разведки: сначала запиши лог")
        val z = java.io.File(dir, "probe_report.zip")
        java.util.zip.ZipOutputStream(z.outputStream()).use { zo ->
            files.forEach { f -> zo.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(zo) }; zo.closeEntry() }
            zo.putNextEntry(java.util.zip.ZipEntry("probe_state.txt")); zo.write((Probe.stateText() + "\n").toByteArray()); zo.closeEntry()
        }
        val uri = FileProvider.getUriForFile(this, "com.evlogger.files", z)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Отправить базу разведки"))
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

    private fun updateApp() {
        if (busy()) return toast("Останови запись/сканер перед обновлением")
        toast("Проверяю обновление…")
        Thread {
            try {
                val cur = packageManager.getPackageInfo(packageName, 0).versionName?.substringAfter("0.")?.toIntOrNull() ?: 0
                val last = Updater.latestBuild() ?: return@Thread runOnUiThread { toast("Не удалось определить версию на GitHub") }
                if (last <= cur) return@Thread runOnUiThread { toast("Уже последняя версия (0.$cur)") }
                runOnUiThread { toast("Новая версия 0.$last, скачиваю…") }
                var lastShown = 0L
                val apk = Updater.download(this) { p ->
                    val now = System.currentTimeMillis()
                    if (now - lastShown > 1500) { lastShown = now; runOnUiThread { Elm.scanProgress = "обновление: $p" } }
                }
                runOnUiThread {
                    Elm.scanProgress = ""
                    if (!Updater.install(this, apk)) toast("Разреши установку для EV Logger и нажми «Обновить» ещё раз")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Ошибка обновления: ${e.message}") }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
