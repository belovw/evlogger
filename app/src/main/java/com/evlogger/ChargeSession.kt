package com.evlogger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Зарядка от станции: энергия в батарею по U·I против показаний станции => КПД зарядки и ёмкость батареи. */
object ChargeSession {
    private const val FILE = "charge_sessions.csv"
    private const val HDR = "id,start,end,minutes,soc0,soc1,kwh_batt,p_max_kw,p_avg_kw,tmax0,tmax1,station_kwh,price,eff,pack_kwh_est"
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile var result = ""
    @Volatile private var finish = false
    private fun fmt(d: Double, n: Int = 2) = if (d.isNaN()) "" else "%.${n}f".format(Locale.US, d)
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ms))

    fun toggle(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>, toast: (String) -> Unit) {
        if (running) { finish = true; return }
        if (GenSession.running || Homework.running || TripTest.running) return toast("Идёт другая сессия или задание")
        if (!Elm.connected) return toast("Сначала подключись к ELM327")
        running = true; finish = false
        Thread { run(ctx, dir, gps) }.start()
    }

    private fun run(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>) {
        try {
            ctx.startForegroundService(android.content.Intent(ctx, KeepAliveService::class.java))
            if (!Elm.logging) { Elm.startAutoLog(ctx, dir, gps); var w = 0; while (!Elm.logging && w < 50) { Thread.sleep(100); w++ } }
            val t0 = System.currentTimeMillis()
            var prevMs = 0L; var prevP = Double.NaN
            var kwh = 0.0; var pMax = 0.0; var soc0 = Double.NaN; var soc1 = Double.NaN; var tm0 = Double.NaN; var tm1 = Double.NaN
            while (running && Elm.connected && !finish) {
                Thread.sleep(500)
                val ms = Elm.lastDataMs
                if (ms == prevMs) continue
                val v = Elm.lastValues
                val u = v["batt_v"]?.toDoubleOrNull(); val a = v["batt_a"]?.toDoubleOrNull()
                val s = (v["bms_f22f"] ?: v["soc_f22f"])?.toDoubleOrNull()
                val tm = v["batt_tmax_c"]?.toDoubleOrNull()
                if (s != null) { if (soc0.isNaN()) soc0 = s; soc1 = s }
                if (tm != null) { if (tm0.isNaN()) tm0 = tm; tm1 = tm }
                if (prevMs != 0L && u != null && a != null) {
                    val dt = ((ms - prevMs) / 1000.0).coerceIn(0.0, 30.0)
                    val p = -u * a / 1000.0       // + = в батарею
                    kwh += (if (prevP.isNaN()) p else (p + prevP) / 2) * dt / 3600
                    prevP = p; if (p > pMax) pMax = p
                } else if (u != null && a != null) prevP = -u * a / 1000.0
                prevMs = ms
                val min = (System.currentTimeMillis() - t0) / 60000.0
                status = "Зарядка: %.0f мин, в батарею %.2f кВт·ч, сейчас %.1f кВт, SOC %s".format(min, kwh, if (prevP.isNaN()) 0.0 else prevP, if (soc1.isNaN()) "—" else "%.1f%%".format(soc1))
            }
            val t1 = System.currentTimeMillis()
            status = "Конец зарядки: введи, сколько кВт·ч отдала станция (по её экрану)"
            val st = GenSession.askRange("chg_kwh_end") ?: Double.NaN
            status = "Введи цену за кВт·ч, ₸ (или пропусти)"
            val price = GenSession.askRange("chg_price_end") ?: Double.NaN
            val f = File(dir, FILE)
            if (f.exists() && f.bufferedReader().use { it.readLine() } != HDR) f.renameTo(File(dir, "charge_sessions_old_${System.currentTimeMillis() / 1000}.csv"))
            if (!f.exists()) f.writeText(HDR + "\n")
            val id = f.readLines().drop(1).size + 1
            val minutes = (t1 - t0) / 60000.0
            val eff = if (!st.isNaN() && st > 0) kwh / st else Double.NaN
            val pack = if (!soc0.isNaN() && !soc1.isNaN() && soc1 - soc0 > 3) kwh / ((soc1 - soc0) / 100) else Double.NaN
            f.appendText(listOf(id.toString(), iso(t0), iso(t1), fmt(minutes), fmt(soc0, 1), fmt(soc1, 1), fmt(kwh, 3), fmt(pMax), fmt(if (minutes > 0) kwh / (minutes / 60) else Double.NaN),
                fmt(tm0, 0), fmt(tm1, 0), fmt(st), fmt(price), fmt(eff, 3), fmt(pack, 1)).joinToString(",") + "\n")
            result = buildString {
                append("Зарядка №$id: %.0f мин\nSOC %s → %s%%, температура батареи %s → %s °C\n".format(minutes, fmt(soc0, 1), fmt(soc1, 1), fmt(tm0, 0), fmt(tm1, 0)))
                append("В батарею по U·I: %.2f кВт·ч (пик %.1f кВт, средняя %.1f кВт)\n".format(kwh, pMax, if (minutes > 0) kwh / (minutes / 60) else 0.0))
                if (!st.isNaN()) append("Станция отдала: %.2f кВт·ч → КПД зарядки ≈ %.1f%%\n".format(st, eff * 100))
                if (!price.isNaN() && !st.isNaN()) append("Стоимость: %.0f ₸, в батарею %.0f ₸/кВт·ч\n".format(st * price, if (kwh > 0) st * price / kwh else 0.0))
                if (!pack.isNaN()) append("Ёмкость батареи по SOC ≈ %.1f кВт·ч (для проверки 31,7)\n".format(pack))
                else append("Ёмкость по SOC не посчитана: набрано меньше 3% SOC\n")
            }
            status = "Зарядка записана (№$id)"
        } catch (e: Exception) { status = "Зарядка: ошибка ${e.message}" }
        finally { running = false; finish = false }
    }
}
