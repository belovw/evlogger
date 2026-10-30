package com.evlogger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сессии генерации: пользователь нажимает СТАРТ перед включением зарядки от ДВС (на P) и СТОП после.
 * Считается энергия, вошедшая в батарею (интеграл U·I), время, SOC, обороты.
 * Сессии копятся в «цикл» между двумя заправками до щелчка; при вводе литров цикл закрывается: кВт·ч / л.
 */
object GenSession {
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile private var stopFlag = false
    private const val SESSIONS = "gen_sessions.csv"
    private const val CYCLES = "gen_cycles.csv"
    private const val HDR_S = "cycle,start,end,minutes,kwh_in,kwh_out,kwh_net,soc0,soc1,rpm_avg,ice_min,p_max_kw,p_avg_kw,coolant0,coolant1,fuel0_pct,fuel1_pct,gap_s,log_file,note"
    private const val HDR_C = "cycle,closed,sessions,minutes,kwh_net,liters_real,liters_shown,kwh_per_l,k_flow,implied_pack_kwh"

    private fun fmt(d: Double, n: Int = 3) = if (d.isNaN()) "" else "%.${n}f".format(Locale.US, d)
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ms))
    private fun prefs(c: Context) = c.getSharedPreferences("evlogger", Context.MODE_PRIVATE)
    fun cycle(c: Context) = prefs(c).getInt("gen_cycle", 1)

    fun toggle(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>, toast: (String) -> Unit) {
        if (running) { stopFlag = true; return }
        if (!Elm.connected) return toast("Сначала подключись к ELM327")
        if (Elm.monitoring || Elm.scanning) return toast("Останови монитор/сканер")
        if (Homework.running) return toast("Идёт домашнее задание")
        running = true; stopFlag = false
        toast("Сессия идёт. Машина на P, педаль тормоза, включи «Максимальную подзарядку». По окончании нажми кнопку ещё раз.")
        val cyc = cycle(ctx)
        Thread { run(ctx, dir, gps, cyc) }.start()
    }

    private fun run(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>, cyc: Int) {
        val t0 = System.currentTimeMillis()
        var startedLog = false
        var note = ""
        var eIn = 0.0; var eOut = 0.0; var gap = 0.0; var iceSec = 0.0
        var rpmSum = 0.0; var rpmDt = 0.0; var pMax = 0.0; var pSum = 0.0; var pDt = 0.0
        var soc0 = Double.NaN; var soc1 = Double.NaN; var c0 = Double.NaN; var c1 = Double.NaN
        var logName = ""
        val fuelFirst = ArrayList<Double>(); val fuelLast = java.util.ArrayDeque<Double>()
        val pr = prefs(ctx)
        var checked = false
        try {
            ctx.startForegroundService(android.content.Intent(ctx, KeepAliveService::class.java))
            if (!Elm.logging) {
                val pids = Elm.loadPids(ctx.assets.open("pids_gen.txt").bufferedReader().readText())
                Thread { Elm.logLoop(dir, pids, gps) }.start()
                startedLog = true
                var w = 0
                while (!Elm.logging && w < 50) { Thread.sleep(100); w++ }
            }
            logName = Elm.currentFile?.name ?: ""
            var prevMs = 0L; var pPrev = 0.0; var havePrev = false
            while (!stopFlag && Elm.connected && Elm.logging) {
                Thread.sleep(150)
                val ms = Elm.lastDataMs
                if (ms == prevMs) continue
                val v = Elm.lastValues
                val volt = v["batt_v"]?.toDoubleOrNull()
                val amp = v["batt_a"]?.toDoubleOrNull()
                val rpm = v["rpm"]?.toDoubleOrNull() ?: 0.0
                val soc = v["soc_f22f"]?.toDoubleOrNull() ?: v["soc_5b_pct"]?.toDoubleOrNull()
                val cool = v["coolant_c"]?.toDoubleOrNull()
                val fuel = v["fuel_pct"]?.toDoubleOrNull()
                if (fuel != null) {
                    if (fuelFirst.size < 12) fuelFirst.add(fuel)
                    fuelLast.addLast(fuel); if (fuelLast.size > 12) fuelLast.removeFirst()
                }
                if (soc != null) { if (soc0.isNaN()) soc0 = soc; soc1 = soc }
                if (!checked && fuelFirst.size >= 8 && !soc0.isNaN()) {
                    checked = true
                    val f0 = fuelFirst.average()
                    val pf = pr.getFloat("gen_last_fuel", Float.NaN); val ps = pr.getFloat("gen_last_soc", Float.NaN)
                    if (!pf.isNaN()) {
                        val df = f0 - pf
                        val ds = if (ps.isNaN()) Double.NaN else soc0 - ps
                        note += "между сессиями: топливо %+.1f%% (%+.2f л), SOC %s%s; ".format(df, df * 0.45,
                            if (ds.isNaN()) "?" else "%+.1f%%".format(ds),
                            if (df < -0.8) " — ДВС расходовал топливо между сессиями!" else if (df > 3.0) " (была заправка)" else "")
                    }
                }
                if (cool != null) { if (c0.isNaN()) c0 = cool; c1 = cool }
                if (volt == null || amp == null) { prevMs = ms; havePrev = false; continue }
                val p = -volt * amp / 1000.0          // кВт, плюс = заряд батареи
                if (havePrev && prevMs != 0L) {
                    val dt = (ms - prevMs) / 1000.0
                    if (dt in 0.0..10.0) {
                        val pa = (p + pPrev) / 2
                        if (pa > 0) eIn += pa * dt / 3600 else eOut += -pa * dt / 3600
                        if (rpm > 300) iceSec += dt
                        rpmSum += rpm * dt; rpmDt += dt
                        pSum += pa * dt; pDt += dt
                    } else gap += dt
                }
                if (p > pMax) pMax = p
                pPrev = p; prevMs = ms; havePrev = true
                val min = (System.currentTimeMillis() - t0) / 60000.0
                status = "ГЕНЕРАЦИЯ %.1f мин: в батарею %.2f кВт·ч, P=%.1f кВт, обороты %.0f, SOC %s".format(
                    min, eIn - eOut, p, rpm, if (soc1.isNaN()) "—" else "%.1f%%".format(soc1))
            }
            if (!Elm.connected) note += "потеря связи"
        } catch (e: Exception) {
            note += "ошибка: ${e.message}"
        } finally {
            if (startedLog) { Elm.logging = false; Thread.sleep(600) }
            val t1 = System.currentTimeMillis()
            val f = File(dir, SESSIONS)
            if (!f.exists()) f.writeText(HDR_S + "\n")
            val minutes = (t1 - t0) / 60000.0
            f.appendText(listOf(cyc.toString(), iso(t0), iso(t1), fmt(minutes, 2), fmt(eIn), fmt(eOut), fmt(eIn - eOut),
                fmt(soc0, 1), fmt(soc1, 1), fmt(if (rpmDt > 0) rpmSum / rpmDt else Double.NaN, 0), fmt(iceSec / 60, 2),
                fmt(pMax, 2), fmt(if (pDt > 0) pSum / pDt else Double.NaN, 2), fmt(c0, 0), fmt(c1, 0),
                fmt(if (fuelFirst.isEmpty()) Double.NaN else fuelFirst.average(), 1), fmt(if (fuelLast.isEmpty()) Double.NaN else fuelLast.average(), 1),
                fmt(gap, 0), logName, note.replace(",", ";"))
                .joinToString(",") + "\n")
            if (fuelLast.isNotEmpty()) pr.edit().putFloat("gen_last_fuel", fuelLast.average().toFloat()).apply()
            if (!soc1.isNaN()) pr.edit().putFloat("gen_last_soc", soc1.toFloat()).apply()
            status = "Сессия записана: %.1f мин, в батарею %.2f кВт·ч. %s".format(minutes, eIn - eOut, note)
            running = false
        }
    }

    private fun readCycle(dir: File, cyc: Int): List<List<String>> {
        val f = File(dir, SESSIONS)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).map { it.split(",") }.filter { it.size >= 20 && it[0] == cyc.toString() }
    }

    fun totals(ctx: Context, dir: File): String {
        val cyc = cycle(ctx)
        val rows = readCycle(dir, cyc)
        val min = rows.sumOf { it[3].toDoubleOrNull() ?: 0.0 }
        val kwh = rows.sumOf { it[6].toDoubleOrNull() ?: 0.0 }
        val sb = StringBuilder("Цикл №$cyc (от последней заправки до щелчка)\nСессий: ${rows.size}, ${"%.1f".format(min)} мин, в батарею ${"%.2f".format(kwh)} кВт·ч")
        rows.forEachIndexed { i, r ->
            sb.append("\n${i + 1}) ${r[1].takeLast(8)} ${r[3]} мин, ${r[6]} кВт·ч, SOC ${r[7]}→${r[8]}%, топливо ${r[15]}→${r[16]}%, ${r[9]} об/мин")
            if (r[19].isNotBlank()) sb.append("\n   ⚠ ${r[19]}")
        }
        val fa = rows.firstOrNull()?.get(15)?.toDoubleOrNull(); val fb = rows.lastOrNull()?.get(16)?.toDoubleOrNull()
        if (fa != null && fb != null) sb.append("\nУровень топлива за цикл: %.1f%% → %.1f%% (≈ %.2f л по датчику, бак 45 л)".format(fa, fb, (fa - fb) * 0.45))
        sb.append("\n\nПосле заправки до щелчка впиши ниже литры, цикл закроется и посчитается кВт·ч/л.")
        return sb.toString()
    }

    /** Закрывает цикл: считает кВт·ч/л. liters = литры, влитые при заправке до щелчка; shown = показание расходомера за тот же период (необязательно). */
    fun closeCycle(ctx: Context, dir: File, liters: Double, shown: Double?): String {
        val cyc = cycle(ctx)
        val rows = readCycle(dir, cyc)
        if (rows.isEmpty()) return "В этом цикле нет сессий."
        val min = rows.sumOf { it[3].toDoubleOrNull() ?: 0.0 }
        val kwh = rows.sumOf { it[6].toDoubleOrNull() ?: 0.0 }
        // ёмкость по SOC: сумма kWh / сумма прироста SOC
        val dSoc = rows.sumOf { (it[8].toDoubleOrNull() ?: 0.0) - (it[7].toDoubleOrNull() ?: 0.0) }
        val pack = if (dSoc > 1) kwh / (dSoc / 100) else Double.NaN
        val c = kwh / liters
        val k = if (shown != null && shown > 0) liters / shown else Double.NaN
        val f = File(dir, CYCLES)
        if (!f.exists()) f.writeText(HDR_C + "\n")
        f.appendText(listOf(cyc.toString(), iso(System.currentTimeMillis()), rows.size.toString(), fmt(min, 1), fmt(kwh, 2), fmt(liters, 2),
            if (shown == null) "" else fmt(shown, 2), fmt(c, 3), fmt(k, 3), fmt(pack, 1)).joinToString(",") + "\n")
        prefs(ctx).edit().putInt("gen_cycle", cyc + 1).apply()
        return buildString {
            append("Цикл №$cyc закрыт.\nВ батарею: ${"%.2f".format(kwh)} кВт·ч за ${"%.0f".format(min)} мин\nЗалито: ${"%.2f".format(liters)} л\n")
            append("Итого на батарею: %.3f кВт·ч/л (КПД от топлива ≈ %.1f %%)\n".format(c, c / 8.9 * 100))
            if (!k.isNaN()) append("Поправка расходомера: %.3f (фактически = показания × %.3f)\n".format(k, k))
            val fa = rows.firstOrNull()?.get(15)?.toDoubleOrNull(); val fb = rows.lastOrNull()?.get(16)?.toDoubleOrNull()
            if (fa != null && fb != null) append("Датчик уровня за цикл: %.1f%% → %.1f%% (≈ %.2f л)\n".format(fa, fb, (fa - fb) * 0.45))
            if (!pack.isNaN()) append("Ёмкость по SOC ≈ %.1f кВт·ч (для проверки 31,7)\n".format(pack))
            append("Новый цикл №${cyc + 1} начат. Нажми «Отправить файлы генерации».")
        }
    }

    /** zip: gen_sessions.csv, gen_cycles.csv и логи, на которые ссылаются сессии. Возвращает файл. */
    fun report(ctx: Context, dir: File): File? {
        val names = LinkedHashSet<String>()
        listOf(SESSIONS, CYCLES).forEach { if (File(dir, it).exists()) names.add(it) }
        File(dir, SESSIONS).takeIf { it.exists() }?.readLines()?.drop(1)?.forEach { l ->
            val p = l.split(","); if (p.size >= 19 && p[18].isNotBlank() && File(dir, p[18]).exists()) names.add(p[18])
        }
        if (names.isEmpty()) return null
        val zip = File(ctx.cacheDir, "gen_report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            names.forEach { n -> z.putNextEntry(ZipEntry(n)); File(dir, n).inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        return zip
    }
}
