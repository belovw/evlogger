package com.evlogger

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Учётный журнал: фоном считает пробег, энергию батареи (U·I), минуты ДВС и SOC, пока идёт запись лога.
 * Заправка закрывает интервал и считает баланс; счётчики обнуляются.
 */
object Ledger {
    private lateinit var ctx: Context
    private var lastMs = 0L
    private var lastP = Double.NaN
    private var lastSave = 0L
    @Volatile var km = 0.0
    @Volatile var kwhOut = 0.0      // нетто из батареи (минус = зарядилась)
    @Volatile var iceSec = 0.0
    @Volatile var sec = 0.0
    @Volatile var since = 0L
    @Volatile var soc0 = Double.NaN
    @Volatile var soc1 = Double.NaN
    private const val L_PER_KM = 45.0 / 850.0
    private const val FILE = "refuels.csv"
    private const val HDR = "id,time,liters,price_per_l,odo,gas_range,days,km_integral,km_odo,kwh_out,soc0,soc1,ice_min,l100_raw,l100_c327,l100_c22,note"

    private fun pr() = ctx.getSharedPreferences("evlogger", Context.MODE_PRIVATE)
    private fun fmt(d: Double, n: Int = 2) = if (d.isNaN()) "" else "%.${n}f".format(Locale.US, d)

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = pr()
        km = p.getFloat("lg_km", 0f).toDouble(); kwhOut = p.getFloat("lg_kwh", 0f).toDouble()
        iceSec = p.getFloat("lg_ice", 0f).toDouble(); sec = p.getFloat("lg_sec", 0f).toDouble()
        since = p.getLong("lg_since", System.currentTimeMillis())
        soc0 = p.getFloat("lg_soc0", Float.NaN).toDouble(); soc1 = p.getFloat("lg_soc1", Float.NaN).toDouble()
    }

    private fun save() {
        pr().edit().putFloat("lg_km", km.toFloat()).putFloat("lg_kwh", kwhOut.toFloat()).putFloat("lg_ice", iceSec.toFloat())
            .putFloat("lg_sec", sec.toFloat()).putLong("lg_since", since).putFloat("lg_soc0", soc0.toFloat()).putFloat("lg_soc1", soc1.toFloat()).apply()
    }

    /** Вызывается основным циклом после каждого опроса. */
    fun tick(v: Map<String, String>, now: Long) {
        if (!::ctx.isInitialized) return
        val u = v["batt_v"]?.toDoubleOrNull(); val a = v["batt_a"]?.toDoubleOrNull()
        val sp = v["speed_kmh"]?.toDoubleOrNull() ?: 0.0
        val rpm = v["rpm"]?.toDoubleOrNull() ?: 0.0
        val s = (v["bms_f22f"] ?: v["soc_f22f"])?.toDoubleOrNull()
        if (s != null) { if (soc0.isNaN()) soc0 = s; soc1 = s }
        if (lastMs != 0L) {
            val dt = ((now - lastMs) / 1000.0).coerceIn(0.0, 15.0)
            sec += dt
            if (u != null && a != null) {
                val p = u * a / 1000.0
                kwhOut += (if (lastP.isNaN()) p else (p + lastP) / 2) * dt / 3600
                lastP = p
            }
            km += sp * dt / 3600
            if (rpm > 300) iceSec += dt
        }
        lastMs = now
        if (now - lastSave > 30000) { lastSave = now; save() }
    }

    fun status(): String = "Журнал с заправки: %.1f км, из батареи %+.2f кВт·ч, ДВС %.0f мин".format(km, kwhOut, iceSec / 60)

    /** Заправка: закрывает интервал, возвращает текст итогов. */
    fun refuel(dir: File, liters: Double, price: Double, odo: Double, gas: Double, toClick: Boolean): String {
        val f = File(dir, FILE)
        if (f.exists() && f.bufferedReader().use { it.readLine() } != HDR) f.renameTo(File(dir, "refuels_old_${System.currentTimeMillis() / 1000}.csv"))
        if (!f.exists()) f.writeText(HDR + "\n")
        val p = pr()
        val id = f.readLines().drop(1).size + 1
        val now = System.currentTimeMillis()
        val prevOdo = p.getFloat("lg_prev_odo", Float.NaN).toDouble()
        val kmOdo = if (!odo.isNaN() && !prevOdo.isNaN()) odo - prevOdo else Double.NaN
        val kmUse = if (!kmOdo.isNaN() && kmOdo > 1) kmOdo else km
        val days = (now - since) / 86400000.0
        val l100 = if (kmUse > 1) liters / kmUse * 100 else Double.NaN
        val l327 = if (kmUse > 1) (liters + kwhOut / 3.27) / kmUse * 100 else Double.NaN
        val l22 = if (kmUse > 1) (liters + kwhOut / 2.2) / kmUse * 100 else Double.NaN
        val note = if (toClick) "" else "не до щелчка"
        f.appendText(listOf(id.toString(), SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(now)), fmt(liters), fmt(price), fmt(odo, 0), fmt(gas, 0),
            fmt(days, 1), fmt(km, 1), fmt(kmOdo, 0), fmt(kwhOut, 2), fmt(soc0, 1), fmt(soc1, 1), fmt(iceSec / 60, 1), fmt(l100), fmt(l327), fmt(l22), note).joinToString(",") + "\n")
        val sb = StringBuilder("Заправка №$id: %.2f л%s\n".format(liters, if (toClick) "" else " (не до щелчка, в серию для калибровки не идёт)"))
        sb.append("С прошлой заправки: %.1f км по скорости".format(km))
        if (!kmOdo.isNaN()) sb.append(", по одометру %.0f км".format(kmOdo))
        sb.append(", %.1f суток\n".format(days))
        sb.append("ДВС работал %.0f мин. Батарея: SOC %s → %s%%, из неё %+.2f кВт·ч (минус = зарядилась)\n".format(iceSec / 60, fmt(soc0, 1), fmt(soc1, 1), kwhOut))
        if (!l100.isNaN()) {
            sb.append("Расход без поправки: %.2f л/100 км\n".format(l100))
            sb.append("С поправкой на батарею: %.2f (3,27 кВт·ч/л) / %.2f (2,2 кВт·ч/л) л/100 км\n".format(l327, l22))
        }
        if (price > 0 && !l100.isNaN()) sb.append("Топливо: %.0f ₸ на 100 км\n".format(l100 * price))
        p.edit().apply {
            if (!odo.isNaN()) putFloat("lg_prev_odo", odo.toFloat())
            putLong("lg_since", now)
        }.apply()
        km = 0.0; kwhOut = 0.0; iceSec = 0.0; sec = 0.0; since = now; soc0 = soc1; save()
        return sb.toString()
    }
}
