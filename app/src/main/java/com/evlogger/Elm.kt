package com.evlogger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Pid(val name: String, val header: String, val cmd: String, val formula: String)

/** Минимальный вычислитель формул: A,B,C,D + - * / скобки. */
object Formula {
    fun eval(expr: String, bytes: IntArray): Double {
        val s = expr.replace(" ", "")
        var pos = 0
        fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
        lateinit var parseExpr: () -> Double
        fun parseFactor(): Double {
            val c = peek()
            if (c == '(') { pos++; val v = parseExpr(); pos++; return v }
            if (c == '-') { pos++; return -parseFactor() }
            if (c in 'A'..'D') { pos++; return bytes.getOrElse(c - 'A') { 0 }.toDouble() }
            val st = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            return s.substring(st, pos).toDouble()
        }
        fun parseTerm(): Double {
            var v = parseFactor()
            while (peek() == '*' || peek() == '/') {
                val op = s[pos++]
                val r = parseFactor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        parseExpr = {
            var v = parseTerm()
            while (peek() == '+' || peek() == '-') {
                val op = s[pos++]
                val r = parseTerm()
                v = if (op == '+') v + r else v - r
            }
            v
        }
        return parseExpr()
    }
}

/** Одна общая сессия с адаптером ELM327 (Bluetooth SPP). Живёт, пока жив процесс. */
object Elm {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val lock = Any()

    @Volatile var logging = false
    @Volatile var monitoring = false
    @Volatile var lastValues: Map<String, String> = emptyMap()
    @Volatile var status: String = "не подключено"
    @Volatile var currentFile: File? = null
    @Volatile var appVersion: String = "?"
    @Volatile var lastDataMs: Long = 0L
    @Volatile var connecting = false
    @Volatile var lastRaw: String = ""
    @Volatile var initLog: String = ""
    var onTerminalLine: ((String) -> Unit)? = null

    val connected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(dev: BluetoothDevice) {
        close()
        val s = dev.createRfcommSocketToServiceRecord(SPP)
        s.connect()
        socket = s; input = s.inputStream; output = s.outputStream
        val sb = StringBuilder()
        for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATSP0", "ATI", "ATDP", "ATRV")) {
            sb.append("$c -> ").append(send(c, 4000).replace("\n", " | ")).append('\n')
        }
        // Первый запрос запускает автопоиск протокола, ждём его отдельно
        sb.append("0100 -> ").append(send("0100", 15000).replace("\n", " | ")).append('\n')
        sb.append("ATDPN -> ").append(send("ATDPN", 3000).replace("\n", " | ")).append('\n')
        initLog = sb.toString()
        status = "подключено: ${dev.name}"
    }

    fun close() {
        logging = false; monitoring = false; scanning = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
        status = "не подключено"
    }

    /** Отправить команду и дождаться приглашения '>' */
    fun send(cmd: String, timeoutMs: Long = 2500): String = synchronized(lock) {
        val o = output ?: return "NO CONNECTION"
        val i = input ?: return "NO CONNECTION"
        while (i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray()); o.flush()
        val sb = StringBuilder()
        var end = System.currentTimeMillis() + timeoutMs
        var extended = false
        while (System.currentTimeMillis() < end) {
            if (!extended && sb.contains("SEARCHING")) { end += 12000; extended = true }
            if (i.available() > 0) {
                val ch = i.read().toChar()
                if (ch == '>') break
                sb.append(ch)
            } else Thread.sleep(10)
        }
        return sb.toString().replace("\r", "\n").trim()
    }

    fun loadPids(text: String): List<Pid> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull {
            val p = it.split(";").map { x -> x.trim() }
            if (p.size < 4) null else Pid(p[0], p[1], p[2].uppercase(), p[3])
        }

    private fun parse(pid: Pid, resp: String): String = try {
        parseUnsafe(pid, resp)
    } catch (e: Exception) { "" }

    private fun parseUnsafe(pid: Pid, resp: String): String {
        val mode = pid.cmd.substring(0, 2).toInt(16) + 0x40
        val prefix = "%02X".format(mode) + pid.cmd.substring(2)
        // Разбираем построчно: при нескольких блоках берём первую подходящую строку
        var data = ""
        for (line in resp.lines()) {
            val clean = line.replace(" ", "").uppercase()
            val idx = clean.indexOf(prefix)
            if (idx >= 0) { data = clean.substring(idx + prefix.length); break }
        }
        if (data.isEmpty()) return ""
        val bytes = IntArray(minOf(4, data.length / 2)) { data.substring(it * 2, it * 2 + 2).toInt(16) }
        return try {
            "%.2f".format(Locale.US, Formula.eval(pid.formula, bytes))
        } catch (e: Exception) { "" }
    }

    /** Основной цикл опроса. Вызывать из фонового потока. */
    fun logLoop(dir: File, pids: List<Pid>, gps: () -> Triple<Double, Double, Double>) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "log_$stamp.csv")
        currentFile = f
        logging = true
        lastDataMs = System.currentTimeMillis()
        f.bufferedWriter().use { w ->
            w.write("# app version: $appVersion\n")
            initLog.lines().filter { it.isNotBlank() }.forEach { w.write("# $it\n") }
            w.write("time_ms,iso," + pids.joinToString(",") { it.name } + ",gps_lat,gps_lon,gps_speed_kmh,raw\n")
            w.flush()
            send("ATSH7DF"); send("ATAR")
            var lastHeader = ""
            while (logging && connected) {
                val vals = LinkedHashMap<String, String>()
                val raws = ArrayList<String>()
                for (p in pids) {
                    if (!logging) break
                    val h = if (p.header == "-") "" else p.header
                    if (h != lastHeader) {
                        if (h.contains(">")) {
                            // Личный запрос блоку: "7A1>7A9" = запрос на 7A1, ответ с 7A9
                            val parts = h.split(">")
                            send("ATSH${parts[0]}"); send("ATCRA${parts[1]}")
                        } else {
                            // Общий запрос 7DF, ответ принимаем только с адреса блока (ATCRA)
                            send("ATSH7DF")
                            send(if (h.isEmpty()) "ATAR" else "ATCRA$h")
                        }
                        lastHeader = h
                    }
                    val resp = send(p.cmd)
                    lastRaw = "${p.cmd}: ${resp.replace("\n", " | ")}"
                    raws.add(lastRaw)
                    vals[p.name] = parse(p, resp)
                }
                val g = gps()
                val now = System.currentTimeMillis()
                w.write("$now,${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(now))},")
                val rawCell = raws.joinToString(" ## ").replace("\"", "'")
                w.write(vals.values.joinToString(",") + ",${g.first},${g.second},${g.third},\"$rawCell\"\n")
                w.flush()
                lastValues = vals
                if (vals.values.any { it.isNotEmpty() }) lastDataMs = now
            }
        }
        logging = false
    }

    // ---------- Mode 22 (UDS ReadDataByIdentifier): ТОЛЬКО ЧТЕНИЕ ----------
    @Volatile var scanning = false
    @Volatile var scanProgress = ""
    val found: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun respAddr(req: String) = "%X".format(req.toInt(16) + 8)
    private fun clean(l: String) = l.replace(" ", "").uppercase()

    /** Ищет в ответе положительную строку 62<DID>. Возвращает (кто ответил, данные) или null. */
    private fun positive(r: String, did: String): Pair<String, String>? {
        for (line in r.lines()) {
            val l = clean(line)
            val i = l.indexOf("62$did")
            if (i >= 0) {
                val hdr = if (i >= 3 && l.take(3).all { it in "0123456789ABCDEF" }) l.take(3) else "?"
                return Pair(hdr, l.substring(i + 6))
            }
        }
        return null
    }

    /** 7DF = запрос всем блокам (ответы с заголовками: видно, кто ответил); иначе личный запрос блоку. */
    private fun setupAddressing(req: String, timeoutCmd: String = "ATST19") {
        send("ATCAF1")
        if (req == "7DF") { send("ATSH7DF"); send("ATH1"); send("ATAR") }
        else { send("ATSH$req"); send("ATCRA${respAddr(req)}") }
        send(timeoutCmd)
    }

    private fun restoreAddressing() { send("ATST32"); send("ATH0"); send("ATSH7DF"); send("ATAR") }

    /** Опрос стандартных идентификационных DID общим запросом: видно, какие блоки отвечают. */
    fun identify(dir: File) {
        val f = File(dir, "ident_${stamp()}.txt"); currentFile = f
        scanning = true
        f.bufferedWriter().use { w ->
            w.write("# app version: $appVersion\n# запрос всем блокам (7DF); первое число в ответе = адрес ответившего блока\n")
            setupAddressing("7DF", "ATST64")
            for (did in listOf("0100", "F190", "F187", "F18A", "F18C", "F197", "F191", "F189", "F193", "F195")) {
                if (!scanning || !connected) break
                scanProgress = "22$did"
                val r = send("22$did", 2500)
                w.write("22$did -> ${r.replace("\n", " | ")}\n"); w.flush()
            }
            restoreAddressing()
        }
        scanning = false; scanProgress = ""
    }

    /** Перебор DID. Пишет в файл только найденные (положительные ответы 62 xx xx ...). */
    fun scanDids(dir: File, req: String, from: Int, to: Int, step: Int = 1) {
        val f = File(dir, "scan_${req}_${stamp()}.txt"); currentFile = f
        scanning = true; found.clear()
        var pos = 0; var neg = 0; var none = 0
        f.bufferedWriter().use { w ->
            w.write("# app version: $appVersion\n# scan request=$req DID %04X..%04X step=$step\n".format(from, to))
            setupAddressing(req)
            var did = from
            while (scanning && connected && did <= to) {
                val d = "%04X".format(did)
                val r = send("22$d", 800)
                scanProgress = "$req $d  найдено $pos, отказов $neg, без ответа $none"
                if (positive(r, d) != null) {
                    pos++; w.write("$d ${r.replace("\n", " | ")}\n"); w.flush(); found.add(d)
                } else if (clean(r).contains("7F22")) {
                    neg++
                    val code = clean(r).substringAfter("7F22", "").take(2)
                    if (code != "31" && code != "11" && code != "12") { w.write("# NEG $d 7F22$code\n"); w.flush() }
                } else none++
                did += step
            }
            w.write("# end: found=$pos neg=$neg none=$none last=%04X\n".format(maxOf(from, did - step)))
            restoreAddressing()
        }
        scanning = false
    }

    /** Перебор адресов запроса 700..7FF командой TesterPresent (3E 00, ничего не меняет). Ответ 7E 00 = блок есть. */
    fun findEcus(dir: File) {
        val f = File(dir, "ecus_${stamp()}.txt"); currentFile = f
        scanning = true
        var hits = 0
        f.bufferedWriter().use { w ->
            w.write("# app version: $appVersion\n# поиск блоков: запрос 3E00 на адреса 700..7FF (ответ ...7E00 = блок есть)\n")
            send("ATCAF1"); send("ATH1"); send("ATAR"); send("ATST19")
            var id = 0x700
            while (scanning && connected && id <= 0x7FF) {
                val h = "%03X".format(id)
                scanProgress = "адрес $h, найдено блоков: $hits"
                send("ATSH$h")
                val r = send("3E00", 600)
                val c = clean(r)
                if (c.contains("027E00") || c.contains("7F3E")) {
                    hits++; w.write("$h -> ${r.replace("\n", " | ")}\n"); w.flush()
                }
                id++
            }
            w.write("# end: found=$hits last=%03X\n".format(id - 1))
            restoreAddressing()
        }
        scanning = false; scanProgress = ""
    }

    /** Склеивает многострочный ответ ELM (строки 0:, 1: ...) в одну hex-строку без пробелов. */
    private fun flatten(r: String): String {
        val sb = StringBuilder()
        for (line in r.lines()) {
            var l = clean(line)
            if (l.isEmpty()) continue
            if (l.length == 3 && l.all { it in "0123456789ABCDEF" }) continue // строка с длиной ответа
            if (l.length >= 2 && l[1] == ':') l = l.substring(2)
            sb.append(l)
        }
        return sb.toString()
    }

    private fun hexAscii(h: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < h.length) {
            val b = h.substring(i, i + 2).toIntOrNull(16) ?: 0
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
            i += 2
        }
        return sb.toString().trim('.')
    }

    /** Берёт найденные блоки из последнего ecus_*.txt и читает паспортные DID (названия, номера деталей). VIN не читаем. */
    fun identifyEcus(dir: File) {
        val src = dir.listFiles { x -> x.name.startsWith("ecus_") && x.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
        if (src == null) { scanProgress = "нет файла ecus_*, сначала найди адреса блоков"; return }
        val pairs = ArrayList<Pair<String, String>>()
        for (line in src.readLines()) {
            if (line.startsWith("#") || !line.contains("->")) continue
            val req = line.substringBefore("->").trim()
            if (req == "7DF") continue
            val resp = clean(line.substringAfter("->")).take(3)
            if (req.length == 3 && resp.length == 3) pairs.add(Pair(req, resp))
        }
        val f = File(dir, "ecuinfo_${stamp()}.txt"); currentFile = f
        scanning = true
        f.bufferedWriter().use { w ->
            w.write("# app version: $appVersion\n# паспортные данные блоков: запрос/ответ, DID, hex | текст\n")
            send("ATCAF1"); send("ATH0"); send("ATST32")
            for ((req, resp) in pairs) {
                if (!scanning || !connected) break
                send("ATSH$req"); send("ATCRA$resp")
                for (did in listOf("F187", "F188", "F189", "F18A", "F191", "F192", "F193", "F194", "F197")) {
                    if (!scanning || !connected) break
                    scanProgress = "$req/$resp 22$did"
                    val c = flatten(send("22$did", 800))
                    val i = c.indexOf("62$did")
                    if (i >= 0) {
                        val p = c.substring(i + 6)
                        w.write("$req/$resp $did $p | ${hexAscii(p)}\n"); w.flush()
                    } else if (c.contains("7F22")) {
                        w.write("# $req/$resp $did отказ ${c.substringAfter("7F22", "").take(2)}\n"); w.flush()
                    }
                }
            }
            restoreAddressing()
        }
        scanning = false; scanProgress = ""
    }

    /** Обзор блоков: для каждого адреса из последнего ecus_*.txt перебирает DID (диапазон/шаг как в сканере).
     *  Пишет survey_*.txt: найденные DID со значениями и итог по блокам. Только чтение (Mode 22). */
    fun surveyEcus(dir: File, from: Int, to: Int, step: Int) {
        val src = dir.listFiles { x -> x.name.startsWith("ecus_") && x.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
        if (src == null) { scanProgress = "нет файла ecus_*, сначала найди адреса блоков"; return }
        val pairs = LinkedHashMap<String, String>() // ответ -> запрос (без повторов-алиасов)
        for (line in src.readLines()) {
            if (line.startsWith("#") || !line.contains("->")) continue
            val req = line.substringBefore("->").trim()
            if (req == "7DF") continue
            val resp = clean(line.substringAfter("->")).take(3)
            if (req.length == 3 && resp.length == 3 && !pairs.containsKey(resp)) pairs[resp] = req
        }
        val f = File(dir, "survey_${stamp()}.txt"); currentFile = f
        scanning = true
        val summary = ArrayList<String>()
        f.bufferedWriter().use { w ->
            w.write("# app version: $appVersion\n# обзор блоков DID %04X..%04X step=$step\n".format(from, to))
            send("ATCAF1"); send("ATH0"); send("ATST19")
            var n = 0
            for ((resp, req) in pairs) {
                if (!scanning || !connected) break
                n++
                send("ATSH$req"); send("ATCRA$resp")
                w.write("## $req/$resp\n")
                var pos = 0; var neg = 0; var none = 0; var first = 0
                var did = from
                while (scanning && connected && did <= to) {
                    val d = "%04X".format(did)
                    val r = send("22$d", 800)
                    scanProgress = "блок $n/${pairs.size} $req/$resp $d  найдено $pos"
                    val c = flatten(r)
                    val i = c.indexOf("62$d")
                    if (i >= 0) {
                        pos++; w.write("$d ${c.substring(i + 6)}\n"); w.flush()
                    } else if (c.contains("7F22")) neg++ else none++
                    first++
                    if (first == 8 && pos == 0 && neg == 0) break // блок молчит - пропускаем
                    did += step
                }
                summary.add("$req/$resp found=$pos neg=$neg none=$none")
                w.write("# $req/$resp found=$pos neg=$neg none=$none\n"); w.flush()
            }
            w.write("# ИТОГ\n"); summary.forEach { w.write("# $it\n") }
            restoreAddressing()
        }
        scanning = false; scanProgress = ""
    }

    /** Список DID из самого свежего файла scan_*.txt (чтобы не вводить вручную). */
    fun loadFoundFromFile(dir: File): List<String> {
        val f = dir.listFiles { x -> x.name.startsWith("scan_") && x.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() } ?: return emptyList()
        return f.readLines().filter { !it.startsWith("#") }
            .mapNotNull { it.trim().take(4).takeIf { d -> d.length == 4 && d.toIntOrNull(16) != null } }
            .distinct()
    }

    /** Циклически читает список DID и пишет сырые значения в CSV (для поиска тока/мощности). */
    fun watchDids(dir: File, req: String, dids: List<String>) {
        val f = File(dir, "watch_${req}_${stamp()}.csv"); currentFile = f
        scanning = true
        f.bufferedWriter().use { w ->
            w.write("# app version: $appVersion\n# request=$req\n")
            w.write("time_ms,iso," + dids.joinToString(",") + "\n"); w.flush()
            setupAddressing(req, "ATST0F")
            while (scanning && connected) {
                val vals = ArrayList<String>()
                for (d in dids) {
                    if (!scanning) break
                    val p = positive(send("22$d", 800), d)
                    vals.add(p?.second ?: "")
                    scanProgress = "мониторинг $req, DID: ${dids.size}"
                }
                val now = System.currentTimeMillis()
                w.write("$now,${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(now))}," + vals.joinToString(",") + "\n")
                w.flush()
            }
            restoreAddressing()
        }
        scanning = false; scanProgress = ""
    }

    /** Прослушивание CAN (ATMA). Пишет сырые кадры в файл. Останавливается по monitoring=false. */
    fun monitorLoop(dir: File, headersOn: Boolean = true) {
        val o = output ?: return
        val i = input ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "can_$stamp.txt")
        currentFile = f
        monitoring = true
        synchronized(lock) {
            if (headersOn) send("ATH1")
            send("ATCAF0");
            o.write("ATMA\r".toByteArray()); o.flush()
            f.bufferedWriter().use { w ->
                val sb = StringBuilder()
                while (monitoring && connected) {
                    if (i.available() > 0) {
                        val ch = i.read().toChar()
                        if (ch == '\r' || ch == '\n') {
                            if (sb.isNotEmpty()) {
                                w.write("${System.currentTimeMillis()} $sb\n"); sb.setLength(0)
                            }
                        } else sb.append(ch)
                    } else Thread.sleep(2)
                }
            }
            o.write("\r".toByteArray()); o.flush()   // любой символ прерывает ATMA
            Thread.sleep(300)
            while (i.available() > 0) i.read()
            send("ATH0"); send("ATCAF1")
        }
        monitoring = false
    }
}
