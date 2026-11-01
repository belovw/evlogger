package com.evlogger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Контрольный заезд: старт и финиш в одной точке, запас хода по бензину и общий вводятся в начале и конце.
 * Считает пробег (CAN и GPS), баланс энергии батареи, SOC, время работы ДВС; после ввода литров заправки до щелчка
 * даёт расход на 100 км с поправкой на изменение заряда батареи.
 */
object TripTest {
    private const val FILE = "trip_tests.csv"
    private const val HDR = "id,start,end,minutes,km_can,km_gps,kwh_out,soc0,soc1,ice_min,ice_starts,v_avg,rpm_avg,gas0,gas1,tot0,tot1,liters,l100_real,odo0,odo1"
    private const val L_PER_KM = 45.0 / 850.0
    private const val C_DASH = 3.19            // кВт·ч/л: 33.2 кВт·ч / 10.4 л по табло, ±0.02 от округления
    private const val PACK_KWH_PER_PCT = 0.308  // 30.8 кВт·ч на 100% SOC
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile var result = ""         // текст итогов для показа окном
    @Volatile private var finish = false

    private fun fmt(d: Double, n: Int = 2) = if (d.isNaN()) "" else "%.${n}f".format(Locale.US, d)
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ms))

    fun toggle(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>, toast: (String) -> Unit) {
        if (running) { finish = true; return }
        if (GenSession.running || Homework.running) return toast("Идёт сессия генерации или задание")
        if (!Elm.connected) return toast("Сначала подключись к ELM327")
        if (Elm.monitoring || Elm.scanning) return toast("Останови монитор/сканер")
        running = true; finish = false
        Thread { run(ctx, dir, gps) }.start()
    }

    private fun num(m: Map<String, String>, k: String) = m[k]?.toDoubleOrNull()

    private fun run(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>) {
        try {
            ctx.startForegroundService(android.content.Intent(ctx, KeepAliveService::class.java))
            if (!Elm.logging) {
                Elm.startAutoLog(ctx, dir, gps)
                var w = 0; while (!Elm.logging && w < 50) { Thread.sleep(100); w++ }
            }
            status = "Заезд: введи запас хода по БЕНЗИНУ с панели (км)"
            val gas0 = GenSession.askRange("trip_gas_start") ?: Double.NaN
            status = "Заезд: введи ОБЩИЙ запас хода с панели (км)"
            val tot0 = GenSession.askRange("trip_tot_start") ?: Double.NaN
            status = "Заезд: введи показание одометра (км) или пропусти"
            val odo0 = GenSession.askRange("trip_odo_start") ?: Double.NaN
            val t0 = System.currentTimeMillis()
            var prevMs = 0L; var prevP = Double.NaN
            var kwhOut = 0.0; var kmCan = 0.0; var kmGps = 0.0; var iceSec = 0.0; var rpmSum = 0.0; var starts = 0; var wasIce = false
            var soc0 = Double.NaN; var soc1 = Double.NaN
            while (running && Elm.connected && !finish) {
                Thread.sleep(400)
                val ms = Elm.lastDataMs
                if (ms == prevMs) continue
                val v = Elm.lastValues
                val u = num(v, "batt_v"); val a = num(v, "batt_a")
                val sp = num(v, "speed_kmh") ?: 0.0
                val rpm = num(v, "rpm") ?: 0.0
                val s = num(v, "bms_f22f") ?: num(v, "soc_f22f")
                if (s != null) { if (soc0.isNaN()) soc0 = s; soc1 = s }
                if (prevMs != 0L) {
                    val dt = ((ms - prevMs) / 1000.0).coerceIn(0.0, 15.0)
                    if (u != null && a != null) {
                        val p = u * a / 1000.0
                        kwhOut += (if (prevP.isNaN()) p else (p + prevP) / 2) * dt / 3600
                        prevP = p
                    }
                    kmCan += sp * dt / 3600
                    kmGps += gps().third * dt / 3600
                    if (rpm > 300) { iceSec += dt; rpmSum += rpm * dt }
                }
                val ice = rpm > 300
                if (ice && !wasIce) starts++
                wasIce = ice
                prevMs = ms
                val min = (System.currentTimeMillis() - t0) / 60000.0
                status = "Заезд: %.0f мин, %.1f км, ДВС %.0f%% времени (%d запусков), из батареи %+.2f кВт·ч, SOC %s".format(
                    min, kmCan, if (min > 0) iceSec / 60 / min * 100 else 0.0, starts, kwhOut, if (soc1.isNaN()) "—" else "%.1f%%".format(soc1))
            }
            val t1 = System.currentTimeMillis()
            status = "Финиш: введи запас хода по БЕНЗИНУ с панели (км)"
            val gas1 = GenSession.askRange("trip_gas_end") ?: Double.NaN
            status = "Финиш: введи ОБЩИЙ запас хода с панели (км)"
            val tot1 = GenSession.askRange("trip_tot_end") ?: Double.NaN
            status = "Финиш: введи показание одометра (км) или пропусти"
            val odo1 = GenSession.askRange("trip_odo_end") ?: Double.NaN
            val f = File(dir, FILE)
            if (f.exists() && f.bufferedReader().use { it.readLine() } != HDR) f.renameTo(File(dir, "trip_tests_old_${System.currentTimeMillis() / 1000}.csv"))
            if (!f.exists()) f.writeText(HDR + "\n")
            val id = (f.readLines().drop(1).size + 1)
            val minutes = (t1 - t0) / 60000.0
            f.appendText(listOf(id.toString(), iso(t0), iso(t1), fmt(minutes), fmt(kmCan), fmt(kmGps), fmt(kwhOut, 3), fmt(soc0, 1), fmt(soc1, 1),
                fmt(iceSec / 60), starts.toString(), fmt(if (minutes > 0) kmCan / (minutes / 60) else Double.NaN, 1), fmt(if (iceSec > 0) rpmSum / iceSec else Double.NaN, 0),
                fmt(gas0, 0), fmt(gas1, 0), fmt(tot0, 0), fmt(tot1, 0), "", "", fmt(odo0, 0), fmt(odo1, 0)).joinToString(",") + "\n")
            result = describe(readRow(dir, id)!!, null)
            status = "Заезд записан (№$id). Заправься до щелчка на той же колонке и введи литры в «Итоги заездов»."
        } catch (e: Exception) {
            status = "Заезд: ошибка ${e.message}"
        } finally { running = false; finish = false }
    }

    private fun readRow(dir: File, id: Int): List<String>? =
        File(dir, FILE).takeIf { it.exists() }?.readLines()?.drop(1)?.map { it.split(",") }?.firstOrNull { it.size >= 21 && it[0] == id.toString() }

    fun lastId(dir: File): Int = File(dir, FILE).takeIf { it.exists() }?.readLines()?.drop(1)?.size ?: 0

    private fun d(s: String) = s.toDoubleOrNull() ?: Double.NaN

    /** Текстовая выкладка по заезду; liters != null = расход с поправкой на изменение заряда батареи. */
    fun describe(r: List<String>, liters: Double?): String {
        val km = d(r[4]).let { if (it.isNaN() || it < 0.1) d(r[5]) else it }
        val kwhOut = d(r[6])
        val sb = StringBuilder("Заезд №${r[0]}\n")
        sb.append("Время: %.0f мин, пробег %.1f км (GPS %s), средняя %s км/ч, обороты ДВС %s\n".format(d(r[3]), km, r[5], r[11], r[12]))
        val o0 = d(r[19]); val o1 = d(r[20])
        if (!o0.isNaN() && !o1.isNaN()) sb.append("Одометр: %.0f → %.0f = %.0f км (интеграл скорости %.1f км, расхождение %+.1f%%)\n".format(o0, o1, o1 - o0, km, if (o1 > o0) (km - (o1 - o0)) / (o1 - o0) * 100 else 0.0))
        sb.append("ДВС работал %s мин (%d запусков)\n".format(r[9], r[10].toIntOrNull() ?: 0))
        sb.append("Батарея: SOC %s → %s%%, из батареи %+.2f кВт·ч (минус = зарядилась)\n".format(r[7], r[8], kwhOut))
        val g0 = d(r[13]); val g1 = d(r[14]); val t0 = d(r[15]); val t1 = d(r[16])
        if (!g0.isNaN() && !g1.isNaN()) {
            val lp = (g0 - g1) * L_PER_KM
            sb.append("По панели: бензин %.0f → %.0f км (−%.0f км ≈ %.2f л)%s\n".format(g0, g1, g0 - g1, lp,
                if (!t0.isNaN() && !t1.isNaN()) ", общий %.0f → %.0f".format(t0, t1) else ""))
            if (km > 1) sb.append("Расход по панели ≈ %.1f л/100 км (грубо, панель сглажена)\n".format(lp / km * 100))
        }
        if (liters != null && km > 1) {
            sb.append("Залито до щелчка: %.2f л (±0.05)\n".format(liters))
            sb.append("Без поправки: %.2f л/100 км\n".format(liters / km * 100))
            // интервал: округление ±0.05 у литров и км, SOC в логе с шагом 0.1, коэффициент 3.19 (+-0.02) по табло машины (33.2 кВт·ч / 10.4 л)
            val s0 = d(r[7]); val s1 = d(r[8])
            val kwhSoc = if (s0.isNaN() || s1.isNaN()) kwhOut else (s0 - s1) * PACK_KWH_PER_PCT
            val kwhA = if (kwhOut.isNaN()) kwhSoc else kwhOut
            val bLo = minOf(kwhA, kwhSoc) - 0.03; val bHi = maxOf(kwhA, kwhSoc) + 0.03
            val vals = ArrayList<Double>()
            for (dl in listOf(-0.05, 0.05)) for (dk in listOf(-0.05, 0.05)) for (b in listOf(bLo, bHi)) for (c in listOf(C_DASH - 0.02, C_DASH + 0.02))
                vals.add((liters + dl + b / c) / (km + dk) * 100)
            val mid = (liters + (bLo + bHi) / 2 / C_DASH) / km * 100
            sb.append("С поправкой на батарею (%.2f кВт·ч/л): %.2f л/100 км (от %.2f до %.2f)\n".format(C_DASH, mid, vals.min(), vals.max()))
            sb.append("Поправка на батарею: от %+.2f до %+.2f кВт·ч (интеграл V·I %+.2f, по SOC %+.2f)\n".format(bLo, bHi, kwhA, kwhSoc))
            if (!d(r[5]).isNaN() && d(r[5]) > 1 && Math.abs(d(r[5]) - km) / km > 0.02)
                sb.append("Внимание: GPS %.1f км против %.1f км по CAN (%+.1f%%); по GPS расход был бы %.2f л/100 км\n".format(d(r[5]), km, (d(r[5]) - km) / km * 100, (liters + (bLo + bHi) / 2 / C_DASH) / d(r[5]) * 100))
            sb.append("(поправка = литры на разницу заряда батареи; интервал учитывает округление ±0.05)\n")
        } else if (liters == null) sb.append("Литров ещё нет: заправься до щелчка на той же колонке и введи в «Итоги заездов».\n")
        return sb.toString()
    }

    fun setLiters(dir: File, id: Int, liters: Double): String {
        val f = File(dir, FILE)
        if (!f.exists()) return "Нет заездов"
        val lines = f.readLines().toMutableList()
        var out = "Заезд №$id не найден"
        for (i in 1 until lines.size) {
            val c = lines[i].split(",").toMutableList()
            if (c.size >= 21 && c[0] == id.toString()) {
                val km = d(c[4]).let { if (it.isNaN() || it < 0.1) d(c[5]) else it }
                val kwhOut = d(c[6])
                c[17] = fmt(liters)
                c[18] = fmt((liters + (if (kwhOut.isNaN()) 0.0 else kwhOut / C_DASH)) / km * 100)
                lines[i] = c.joinToString(",")
                out = describe(c, liters)
            }
        }
        f.writeText(lines.joinToString("\n") + "\n")
        return out
    }

    fun summary(dir: File): String {
        val id = lastId(dir)
        if (id == 0) return "Заездов ещё не было."
        return describe(readRow(dir, id)!!, readRow(dir, id)!![17].toDoubleOrNull())
    }
}
