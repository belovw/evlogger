package com.evlogger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Фоновая разведка неизвестных DID (Mode 22, ТОЛЬКО ЧТЕНИЕ) во время обычной записи лога.
 * Каждый цикл основного опроса добавляется 1-2 неизвестных запроса. Нашлось (положительный ответ) -> база
 * probe_found.csv и далее значение этого DID периодически перечитывается и пишется в лог (колонка raw, метка PR).
 * Прогресс сохраняется в настройках и продолжается в следующих поездках.
 */
object Probe {
    @Volatile var on = true
    @Volatile var status = ""
    private lateinit var appCtx: Context
    private class Ecu(val req: String, val resp: String)
    private val ecus = ArrayList<Ecu>()
    private val RANGES = listOf(
        0x0100 to 0x01FF, 0x0400 to 0x04FF, 0xF200 to 0xF2FF, 0xF000 to 0xF0FF, 0xF300 to 0xF3FF,
        0xFD00 to 0xFDFF, 0x2100 to 0x21FF, 0x2A00 to 0x2AFF, 0xB000 to 0xB0FF, 0x0000 to 0x00FF,
        0x0200 to 0x03FF, 0xF400 to 0xF4FF, 0xF100 to 0xF1FF, 0x0500 to 0x07FF, 0x2000 to 0x20FF,
        0x2200 to 0x29FF, 0xB100 to 0xB1FF, 0xD000 to 0xDFFF)
    private val PRIORITY_ECUS = 12   // сначала первые N блоков по всем диапазонам, потом остальные
    private class Task(val ecu: Ecu, val from: Int, val to: Int)
    private val tasks = ArrayList<Task>()
    private var ti = 0; private var did = 0
    private val found = ArrayList<Pair<Ecu, String>>()   // найденные (блок, did)
    private val foundSet = HashSet<String>()
    private var ri = 0
    private var slot = 0
    private var tot = 0; private var pos = 0; private var neg = 0
    private val bad = HashMap<String, Int>()      // блок -> подряд ошибок шины
    private val muted = HashSet<String>()         // отключённые за ошибки блоки
    private var loaded = false
    private var lastSave = 0L
    private val seen = HashMap<String, Int>()          // сколько раз перечитан найденный DID
    private val first = HashMap<String, String>()      // первое значение
    private val varies = HashSet<String>()            // менялся ли DID
    private var activeMs = 0L          // накопленное время записи с включённой разведкой
    private var lastStepMs = 0L
    private var totalDids = 0

    private fun hm(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин" }

    private fun doneDids(): Int {
        var d = 0
        for (i in 0 until ti) d += tasks[i].to - tasks[i].from + 1
        return d + (did - tasks[ti].from).coerceAtLeast(0)
    }

    private fun progressLine(): String {
        val done = doneDids()
        val pct = if (totalDids > 0) 100.0 * done / totalDids else 0.0
        val rate = if (activeMs > 60000 && tot > 20) tot * 60000.0 / activeMs else Double.NaN   // DID в минуту
        val eta = if (rate.isNaN() || rate <= 0) "оценка появится через пару минут" else "осталось ≈ " + hm(((totalDids - done) / rate * 60000).toLong())
        return "%.1f%% (%d из %d), найдено %d, затрачено %s, %s".format(pct, done, totalDids, found.size, hm(activeMs), eta)
    }

    private fun pr() = appCtx.getSharedPreferences("evlogger", Context.MODE_PRIVATE)
    private fun clean(l: String) = l.replace(" ", "").uppercase()
    private fun flatten(r: String): String {
        val sb = StringBuilder()
        for (line in r.lines()) {
            var l = clean(line)
            if (l.isEmpty()) continue
            if (l.length == 3 && l.all { it in "0123456789ABCDEF" }) continue
            if (l.length >= 2 && l[1] == ':') l = l.substring(2)
            sb.append(l)
        }
        return sb.toString()
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        on = pr().getBoolean("probe_on", true)
    }

    fun enable(v: Boolean) { on = v; pr().edit().putBoolean("probe_on", v).apply(); if (!v) status = "" }

    private fun load(dir: File) {
        loaded = true
        appCtx.assets.open("probe_ecus.txt").bufferedReader().readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.forEach {
                val p = it.split(" ").filter { x -> x.isNotEmpty() }
                if (p.size >= 2) ecus.add(Ecu(p[0], p[1]))
            }
        val head = ecus.take(PRIORITY_ECUS); val tail = ecus.drop(PRIORITY_ECUS)
        for (grp in listOf(head, tail)) for ((a, b) in RANGES) for (e in grp) tasks.add(Task(e, a, b))
        ti = pr().getInt("probe_task", 0).coerceIn(0, maxOf(0, tasks.size - 1))
        did = pr().getInt("probe_did", tasks.getOrNull(ti)?.from ?: 0)
        totalDids = tasks.sumOf { it.to - it.from + 1 }
        activeMs = pr().getLong("probe_ms", 0L)
        tot = pr().getInt("probe_tot", 0); pos = pr().getInt("probe_pos", 0); neg = pr().getInt("probe_neg", 0)
        val constKeys = HashSet<String>()
        File(dir, "probe_const.csv").takeIf { it.exists() }?.readLines()?.drop(1)?.forEach { l -> val c = l.split(","); if (c.size >= 3) constKeys.add(c[1] + c[2]) }
        val f = File(dir, "probe_found.csv")
        if (f.exists()) f.readLines().drop(1).forEach { l ->
            val c = l.split(",")
            if (c.size >= 4) { val e = ecus.firstOrNull { it.req == c[1] }; if (e != null && foundSet.add(c[1] + c[2]) && (c[1] + c[2]) !in constKeys) found.add(e to c[2]) }
        }
    }

    private fun save() {
        pr().edit().putInt("probe_task", ti).putInt("probe_did", did).putInt("probe_tot", tot).putInt("probe_pos", pos).putInt("probe_neg", neg).putLong("probe_ms", activeMs).apply()
    }

    private fun addr(e: Ecu) {
        val key = e.req + ">" + e.resp
        if (Elm.curHeader != key) { Elm.send("ATSH${e.req}"); Elm.send("ATCRA${e.resp}"); Elm.curHeader = key }
    }

    /** Один запрос. Возвращает данные при положительном ответе, "" при отказе/нет данных, null при ошибке шины. */
    private fun query(e: Ecu, d: String): String? {
        addr(e)
        val r = Elm.send("22$d", 800)
        val u = clean(r)
        if (u.contains("CANERROR") || u.contains("BUFFERFULL") || u.contains("BUSERROR") || u.contains("UNABLE") || u.contains("NOCONNECTION")) {
            val n = (bad[e.req] ?: 0) + 1; bad[e.req] = n
            if (n >= 3) muted.add(e.req)
            return null
        }
        bad[e.req] = 0
        val c = flatten(r)
        val i = c.indexOf("62$d")
        if (i >= 0) return c.substring(i + 6).take(60)
        return ""
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    /** Вызывается основным циклом после опроса всех известных PID. Укладывается в ~450 мс. */
    fun step(dir: File, raws: ArrayList<String>) {
        val gen = GenSession.running
        if (!on || Homework.running || Elm.scanning || Elm.monitoring) return
        if (gen && GenSession.phase != 2) return   // замеры топлива до/после: не мешаем
        if (!loaded) { try { load(dir) } catch (e: Exception) { on = false; status = "Разведка: ошибка ${e.message}"; return } }
        if (tasks.isEmpty()) return
        val t0 = System.currentTimeMillis()
        if (lastStepMs != 0L && t0 - lastStepMs < 15000) activeMs += t0 - lastStepMs
        lastStepMs = t0
        var n = 0
        val maxN = if (gen) 1 else 2
        while (n < maxN && System.currentTimeMillis() - t0 < 450 && Elm.logging) {
            n++; slot++
            if (slot % 3 != 0 && found.isNotEmpty()) {            // перечитываем найденный DID
                ri = (ri + 1) % found.size
                val (e, d) = found[ri]
                if (e.req in muted || (gen && e.req == "7A1")) continue
                val v = query(e, d)
                if (!v.isNullOrEmpty()) {
                    raws.add("PR ${e.req}>${e.resp} 22$d: $v")
                    val key = e.req + d
                    seen[key] = (seen[key] ?: 0) + 1
                    val f0 = first.getOrPut(key) { v }
                    if (v != f0) varies.add(key)
                    // константы не тратят время: 6 одинаковых ответов -> убираем из ротации
                    if ((seen[key] ?: 0) >= 6 && key !in varies) {
                        found.removeAt(ri); if (ri > 0) ri--
                        val cf = File(dir, "probe_const.csv")
                        if (!cf.exists()) cf.writeText("time,ecu,did,data\n")
                        cf.appendText("${stamp()},${e.req},$d,$v\n")
                    }
                }
            } else {                                                // разведка нового DID
                val tk = tasks[ti]
                if (did < tk.from || did > tk.to) did = tk.from
                if (tk.ecu.req in muted || (gen && tk.ecu.req == "7A1")) { advance(); continue }
                val d = "%04X".format(did)
                val key = tk.ecu.req + d
                val v = query(tk.ecu, d)
                tot++
                if (v == null) { /* ошибка шины: повторим позже */ }
                else {
                    if (v.isNotEmpty()) {
                        pos++
                        if (foundSet.add(key)) {
                            found.add(tk.ecu to d)
                            val f = File(dir, "probe_found.csv")
                            if (!f.exists()) f.writeText("time,ecu,did,data\n")
                            f.appendText("${stamp()},${tk.ecu.req},$d,$v\n")
                        }
                        raws.add("PR ${tk.ecu.req}>${tk.ecu.resp} 22$d: $v")
                    } else neg++
                    advance()
                }
            }
        }
        val tk = tasks[ti]
        status = "Разведка DID: ${progressLine()}\nсейчас: блок ${tk.ecu.req} %04X${if (muted.isNotEmpty()) ", отключены ${muted.joinToString("/")}" else ""}".format(did)
        if (System.currentTimeMillis() - lastSave > 20000) { lastSave = System.currentTimeMillis(); save() }
    }

    private fun advance() {
        did++
        val tk = tasks[ti]
        if (did > tk.to) { ti = (ti + 1) % tasks.size; did = tasks[ti].from }
    }

    fun stateText(): String = (if (loaded) progressLine() else "ещё не запускалась") + "; положительных ответов $pos, отказов $neg"

    fun resetProgress() { activeMs = 0; ti = 0; did = tasks.firstOrNull()?.from ?: 0; tot = 0; pos = 0; neg = 0; muted.clear(); save() }
}
