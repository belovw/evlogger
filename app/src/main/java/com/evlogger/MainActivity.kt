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
    private lateinit var recheckLoopBtn: Button
    private lateinit var recheck7A1Btn: Button
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
    private var alertOpen = false
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tabButtons: List<Button>
    private lateinit var tabPanels: List<LinearLayout>
    private lateinit var searchStatus: TextView

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
            if (gpsMs != 0L && now - gpsMs > 5000) gpsSpeed = 0.0   // GPS замёрз: не тянуть старую скорость в логи
            info.text = buildString {
                append("Версия приложения: ${Elm.appVersion}\n")
                if (Probe.on && Elm.logging && Probe.status.isNotEmpty()) append("${Probe.status}\n")
                append("Статус: ${Elm.status}\n")
                append("Запись: ${if (Elm.probeOnlyActive) "нет (идёт только разведка DID)" else if (Elm.recheckOnlyActive) "нет (идёт только проверка найденных)" else if (Elm.logging) "ИДЁТ" else "нет"}   CAN-монитор: ${if (Elm.monitoring) "ИДЁТ" else "нет"}\n")
                Elm.currentFile?.let { append("Файл: ${it.name}\n") }
                if (Elm.scanning || Elm.scanProgress.isNotEmpty()) append("Сканер: ${Elm.scanProgress}\n")
                val age = (now - Elm.lastDataMs) / 1000
                if (v.isNotEmpty() && Elm.logging && !Elm.probeOnlyActive && !Elm.recheckOnlyActive && age <= 6) v.forEach { (k, x) -> append("$k = ${if (x.isEmpty()) "—" else x}\n") }
                else if (v.isNotEmpty() && !Elm.probeOnlyActive && !Elm.recheckOnlyActive) append("Показания машины не обновляются${if (Homework.running) " (идёт задание, читаются другие адреса)" else if (!Elm.logging) " (запись не идёт)" else ""}. Последние были ${if (Elm.lastDataMs == 0L) "—" else "$age с назад"}.\n")
                if (Elm.lastRaw.isNotEmpty()) append("ответ адаптера: ${Elm.lastRaw}\n")
                append("gps_speed = %.1f км/ч   gps_alt = %.0f м".format(gpsSpeed, gpsAlt))
            }
            // Дублируем прогресс поиска прямо у вкладок "Поиск блоков"/"Поиск PID" - иначе его
            // видно только в блоке "ПОДКЛЮЧЕНИЕ" наверху экрана, до которого после перехода на
            // вкладку нужно долго скроллить обратно, и кажется, что индикации вовсе нет.
            val sText = buildString {
                if (Elm.scanning || Elm.scanProgress.isNotEmpty()) append("Сканер: ${Elm.scanProgress}\n")
                if (Probe.on && Elm.logging && Probe.status.isNotEmpty()) append(Probe.status)
                if (Elm.recheckLoopOn && Elm.logging && Elm.recheckStatus.isNotEmpty()) append(Elm.recheckStatus + "\n")
            }
            if (sText.isEmpty()) { searchStatus.visibility = android.view.View.GONE } else {
                searchStatus.text = sText.trim()
                searchStatus.visibility = android.view.View.VISIBLE
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe.init(this)
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

        // ===================== ПОДКЛЮЧЕНИЕ (всегда наверху, отдельно от вкладок) =====================
        title("ПОДКЛЮЧЕНИЕ")
        spinner = Spinner(this); root.addView(spinner)
        btn("Подключиться к ELM327 (запись и разведка пойдут сами)") { connect() }
        info = TextView(this).apply { textSize = 13f; setPadding(4, 16, 4, 8) }
        root.addView(info)
        btn("■ СТОП (запись, разведка, задания)") { stopAll() }

        // ===================== ПАНЕЛЬ ПОИСКА (общие поля для вкладок "Поиск блоков" и "Поиск PID") =====================
        title("Параметры поиска (используются вкладками «Поиск блоков» и «Поиск PID»)")
        fun field(hintText: String, def: String) = EditText(this).apply {
            hint = hintText; setText(def); isSingleLine = true; target.addView(this)
        }
        hdrBox = field("Адрес запроса: один (7E0) или список через пробел (703 732 744 765); 7DF = всем блокам", "7DF")
        fromBox = field("DID с (hex)", "0000")
        toBox = field("DID по (hex)", "FFFF")
        stepBox = field("Шаг перебора (1 = каждый DID, 16 = каждый 16-й)", "1")
        didBox = field("DID для мониторинга: 0100 0801-0820 ... (пусто = все найденные последним сканом)", "")

        // ===================== ВКЛАДКИ =====================
        val tabNames = listOf("Логирование", "Домашка", "Поиск блоков", "Поиск PID", "Инструменты")
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 24, 0, 0) }
        root.addView(tabRow)
        val tabButtonsMut = ArrayList<Button>()
        for (name in tabNames) {
            val b = Button(this).apply { text = name; textSize = 11f; setPadding(4, 4, 4, 4) }
            tabRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabButtonsMut.add(b)
        }
        tabButtons = tabButtonsMut

        searchStatus = TextView(this).apply {
            textSize = 13f; setPadding(4, 16, 4, 8)
            setTextColor(0xFF2E7D32.toInt()); visibility = android.view.View.GONE
        }
        root.addView(searchStatus)

        val panelsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(panelsContainer)
        val tabPanelsMut = ArrayList<LinearLayout>()
        for (i in tabNames.indices) {
            val p = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (i == 0) android.view.View.VISIBLE else android.view.View.GONE
            }
            panelsContainer.addView(p)
            tabPanelsMut.add(p)
        }
        tabPanels = tabPanelsMut
        tabButtons.forEachIndexed { i, b -> b.setOnClickListener { showTab(i) } }
        showTab(0)

        // --- Вкладка: Логирование ---
        target = tabPanels[0]
        btn("Запись лога: старт (обычно не нужна, стартует сама)") { startLog() }
        btn("CAN-монитор: старт") { startMonitor() }
        btn("📤 Отправить всё и очистить") { sendAll() }
        btn("🔋 Разрешить работу в фоне (если запись слетает после сворачивания)") { requestIgnoreBatteryOptimizations() }
        btn("Обновить приложение") { updateApp() }

        // --- Вкладка: Домашка ---
        target = tabPanels[1]
        btn("★ ДОМАШНЕЕ ЗАДАНИЕ") { Homework.start(this, { Triple(gpsLat, gpsLon, gpsSpeed) }) { toast(it) } }

        // --- Вкладка: Поиск блоков ---
        target = tabPanels[2]
        target.addView(TextView(this).apply {
            text = "Поиск данных (Mode 22, только ЧТЕНИЕ). Только на стоянке, зажигание включено. Запрос идёт всем блокам, в файле видно, кто ответил."
            textSize = 12f
        })
        btn("Опознать блоки (кто отвечает на Mode 22)") { startIdent() }
        btn("Найти адреса блоков (700-7FF)") { startFindEcus() }
        btn("Опознать найденные блоки (названия)") { startIdentEcus() }
        btn("Обзор всех блоков (диапазон и шаг из полей)") { startSurvey() }

        // --- Вкладка: Поиск PID ---
        target = tabPanels[3]
        probeBtn = btn("") { Probe.enable(!Probe.on); probeBtn.text = probeLabel() }
        probeBtn.text = probeLabel()
        btn("Сканер Mode 22: старт") { startScan() }
        btn("Мониторинг DID: старт") { startWatch() }
        btn("🚀 Только разведка DID (без лога известных PID, быстрее): старт") { startProbeOnly() }
        btn("🔁 Проверить ВСЕ найденные PID один раз (кто отвечает сейчас)") { startRecheckAll() }
        recheckLoopBtn = btn("") { toggleRecheckLoop() }
        recheckLoopBtn.text = recheckLoopLabel()
        btn("⚡ Только непрерывная проверка найденных (без обычного лога, быстрее)") { startRecheckOnly() }
        recheck7A1Btn = btn("") { toggleRecheck7A1() }
        recheck7A1Btn.text = recheck7A1Label()

        // --- Вкладка: Инструменты ---
        target = tabPanels[4]
        target.addView(TextView(this).apply { text = "Отправка на GitHub (приватный репозиторий!). Пусто = отправка zip через «Поделиться»."; textSize = 12f })
        val sp = getSharedPreferences("evlogger", Context.MODE_PRIVATE)
        val ghRepo = EditText(this).apply { hint = "Репозиторий: владелец/имя, например belovw/evlogger-data"; setText(sp.getString("gh_repo", "")); isSingleLine = true }
        val ghToken = EditText(this).apply {
            hint = if (sp.getString("gh_token", "").isNullOrBlank()) "Токен GitHub (Contents: read/write только на этот репозиторий)" else "Токен сохранён (введи новый, чтобы заменить)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true
        }
        target.addView(ghRepo); target.addView(ghToken)
        btn("Сохранить настройки GitHub") {
            val e = sp.edit().putString("gh_repo", ghRepo.text.toString().trim())
            if (ghToken.text.isNotBlank()) { e.putString("gh_token", ghToken.text.toString().trim()); ghToken.setText("") }
            e.apply(); toast("Сохранено")
        }
        btn("Отправить zip через «Поделиться» (запасной путь)") { shareAll() }
        cmdBox = EditText(this).apply { hint = "Команда ELM327, например ATRV или 22F190"; isSingleLine = true }
        target.addView(cmdBox)
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
        target.addView(term)

        setContentView(android.widget.ScrollView(this).apply { addView(root) })

        askPermissions()
        requestIgnoreBatteryOptimizations()
        loadDevices()
        startGps()
        ui.post(refresh)
    }

    private fun showTab(i: Int) {
        tabPanels.forEachIndexed { idx, p -> p.visibility = if (idx == i) android.view.View.VISIBLE else android.view.View.GONE }
        // Раньше активную вкладку отмечали только жирным шрифтом кнопки - на прошивке магнитолы
        // это иногда не перерисовывалось (кнопка "залипала" с прошлой подсветкой). Заливка фона
        // меняется гарантированно, так что это основной признак, а жирный шрифт - дополнительный.
        tabButtons.forEachIndexed { idx, b ->
            val sel = idx == i
            b.setBackgroundColor(if (sel) 0xFF2E7D32.toInt() else 0xFFE0E0E0.toInt())
            b.setTextColor(if (sel) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            b.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
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
                .setMessage("Удалить с телефона ${pend.size} отправленных файлов (логи, сканы и т.п.)?\nСлужебные файлы (база разведки) и текущий лог остаются.")
                .setPositiveButton("Да, удалить") { _, _ -> var n = 0; pend.forEach { if (it.delete()) n++ }; toast("Удалено файлов: $n") }
                .setNegativeButton("Нет, оставить", null).show()
        }
    }

    private var pendingDelete: List<java.io.File> = emptyList()

    /** Служебные файлы: нужны приложению для работы, отправляются копией, но не удаляются. */
    private fun isKeep(f: java.io.File) = f.name == "probe_found.csv" || f.name == "probe_const.csv" ||
        f.name == "probe_progress.txt" || f.name == "pids.txt"

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
        val all = (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".apk") && !it.name.endsWith(".zip") }
        if (all.isEmpty()) return toast("Файлов пока нет")
        val z = java.io.File(dir, "evlogger_all_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.zip")
        java.util.zip.ZipOutputStream(z.outputStream()).use { zo ->
            all.forEach { f -> zo.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(zo) }; zo.closeEntry() }
            zo.putNextEntry(java.util.zip.ZipEntry("state.txt"))
            zo.write(("version ${Elm.appVersion}\n${Probe.stateText()}\n").toByteArray()); zo.closeEntry()
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

    private fun stopAll() {
        Homework.abort = true
        Elm.logging = false
        Elm.monitoring = false
        Elm.scanning = false
        Elm.scanQueueAbort = true // не даёт перейти к следующему блоку в очереди "Сканер Mode 22"
        Elm.recheckLoopOn = false
        if (::recheckLoopBtn.isInitialized) recheckLoopBtn.text = recheckLoopLabel()
        if (::recheck7A1Btn.isInitialized) recheck7A1Btn.text = recheck7A1Label()
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
        // можно перечислить несколько адресов через пробел/запятую (например "703 732 744 765") -
        // тогда блоки сканируются по очереди одним запуском, без ручной смены адреса между ними
        val blocks = hdrBox.text.toString().trim().uppercase().split(Regex("[\\s,]+"))
            .filter { it.isNotEmpty() }
        if (blocks.isEmpty() || blocks.any { it.toIntOrNull(16) == null }) return toast("Проверь адрес(а) (hex, через пробел)")
        val a = fromBox.text.toString().trim().toIntOrNull(16)
        val b = toBox.text.toString().trim().toIntOrNull(16)
        if (a == null || b == null || a > b || b > 0xFFFF) return toast("Проверь диапазон (hex)")
        val st = stepBox.text.toString().trim().toIntOrNull() ?: 1
        if (st < 1 || st > 4096) return toast("Шаг от 1 до 4096")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Elm.scanQueueAbort = false
        Thread {
            for ((i, h) in blocks.withIndex()) {
                if (Elm.scanQueueAbort || !Elm.connected) break
                // Небольшая пауза перед каждым следующим блоком: смена заголовка (ATSH/ATCRA) и так
                // ждёт ответа на каждую AT-команду, но резкая смена заголовков между блоками была
                // одной из вероятных причин обрывов связи с дешёвым клоном V-LINK - лучше перестраховаться.
                if (i > 0) Thread.sleep(300)
                Elm.scanDids(getExternalFilesDir(null)!!, h, a, b, st)
            }
        }.start()
    }

    private fun startRecheckOnly() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.recheckOnlyLoop(getExternalFilesDir(null)!!) }.start()
    }

    private fun startRecheckAll() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.recheckAll(getExternalFilesDir(null)!!) }.start()
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

    private fun startProbeOnly() {
        if (!Elm.connected) return toast("Сначала подключись")
        if (busy()) return toast("Останови текущую запись или сканер")
        if (!Probe.on) return toast("Сначала включи «Фоновая разведка DID» выше")
        startForegroundService(Intent(this, KeepAliveService::class.java))
        Thread { Elm.probeOnlyLoop(getExternalFilesDir(null)!!) }.start()
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

    private fun recheckLoopLabel() = if (Elm.recheckLoopOn) "🔁 Непрерывная проверка всех найденных: ВКЛ (нажми = выкл)" else "🔁 Непрерывная проверка всех найденных: ВЫКЛ (нажми = вкл, работает во время обычной записи)"

    private fun toggleRecheckLoop() {
        if (!Elm.recheckLoopOn && !Elm.logging) return toast("Включи сначала обычную запись - проверка крутится вместе с ней")
        Elm.recheckLoopToggle(getExternalFilesDir(null)!!, !Elm.recheckLoopOn)
        recheckLoopBtn.text = recheckLoopLabel()
        if (Elm.recheckLoopOn) toast("Крутится по кругу, пишет в recheck_live_*.csv")
    }

    private fun recheck7A1Label() = if (Elm.recheckLoopOn) "⚡ Быстрая проверка только блока 7A1 (343 DID, ищем мощность тяги): ВКЛ (нажми = выкл)" else "⚡ Быстрая проверка только блока 7A1 (343 DID, ищем мощность тяги): ВЫКЛ (нажми = вкл, во время обычной записи)"

    /** Как toggleRecheckLoop, но крутит ТОЛЬКО блок 7A1 (343 найденных DID вместо 800+) -
     *  круг получается в разы быстрее, за одну поездку набирается много повторов одного и того же
     *  DID в разных состояниях (стоим/едем/тормозим) - то, что нужно для поиска параметра мощности
     *  тягового электродвигателя по корреляции со speed/rpm/batt_a. */
    private fun toggleRecheck7A1() {
        if (!Elm.recheckLoopOn && !Elm.logging) return toast("Включи сначала обычную запись - проверка крутится вместе с ней")
        Elm.recheckLoopToggle(getExternalFilesDir(null)!!, !Elm.recheckLoopOn, "7A1")
        recheckLoopBtn.text = recheckLoopLabel()
        recheck7A1Btn.text = recheck7A1Label()
        if (Elm.recheckLoopOn) toast("Крутим только 7A1 (343 DID), пишет в recheck_live_7A1_*.csv")
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
