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
        initLog = sb.toString()
        status = "подключено: ${dev.name}"
    }

    fun close() {
        logging = false; monitoring = false
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
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
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
        val clean = resp.replace(" ", "").replace("\n", "").uppercase()
        if (clean.contains("NODATA") || clean.contains("ERROR") || clean.contains("?") || clean.isEmpty()) return ""
        val mode = pid.cmd.substring(0, 2).toInt(16) + 0x40
        val prefix = "%02X".format(mode) + pid.cmd.substring(2)
        val idx = clean.indexOf(prefix)
        if (idx < 0) return ""
        val data = clean.substring(idx + prefix.length)
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
        f.bufferedWriter().use { w ->
            initLog.lines().filter { it.isNotBlank() }.forEach { w.write("# $it\n") }
            w.write("time_ms,iso," + pids.joinToString(",") { it.name } + ",gps_lat,gps_lon,gps_speed_kmh,raw\n")
            w.flush()
            var lastHeader = ""
            while (logging && connected) {
                val vals = LinkedHashMap<String, String>()
                val raws = ArrayList<String>()
                for (p in pids) {
                    if (!logging) break
                    val h = if (p.header == "-") "" else p.header
                    if (h != lastHeader) {
                        send(if (h.isEmpty()) "ATSH7DF" else "ATSH$h"); lastHeader = h
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
            }
        }
        logging = false
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
