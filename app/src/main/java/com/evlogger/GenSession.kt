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
    @Volatile var precision = ""
    @Volatile var alertKind = ""      // "drop" | "refill" | ""
    @Volatile var alertText = ""
    @Volatile var alertFuel = Double.NaN
    @Volatile private var stopFlag = false
    private const val SIGMA_FILL = 0.20   // л, повторяемость отстрела колонки (допущение)
    private const val ERR_ENERGY = 0.02   // относительная погрешность измерения энергии (допущение)
    private const val TARGET_FILL = 0.01  // цель: вклад заправок не более 1 %
    private const val C_REF = 3.26
    private const val DROP_PCT = 0.30     // падение уровня топлива между сессиями, % бака (≈0,135 л, ≈2,7 км на ДВС)
    private const val SESSIONS = "gen_sessions.csv"
    private const val CYCLES = "gen_cycles.csv"
    private const val HDR_S = "cycle,start,end,minutes,kwh_in,kwh_out,kwh_net,soc0,soc1,rpm_avg,ice_min,p_max_kw,p_avg_kw,coolant0,coolant1,fuel0_pct,fuel1_pct,range0_km,range1_km,gap_s,log_file,note"
    private const val HDR_C = "cycle,closed,sessions,minutes,kwh_net,liters_real,liters_shown,kwh_per_l,k_flow,implied_pack_kwh,status"

    private fun fmt(d: Double, n: Int = 3) = if (d.isNaN()) "" else "%.${n}f".format(Locale.US, d)
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ms))
    private fun prefs(c: Context) = c.getSharedPreferences("evlogger", Context.MODE_PRIVATE)
    fun cycle(c: Context) = prefs(c).getInt("gen_cycle", 1)

    @Volatile var phase = 0     // 1 = замер топлива до, 2 = генерация, 3 = замер топлива после
    @Volatile private var skipFlag = false
    private const val SETTLE_MS = 30_000L
    private const val MAX_WAIT_MS = 120_000L
    private const val SPREAD_OK = 0.8       // макс. разброс уровня за окно, % бака (2 шага датчика)

    private class FuelWin {
        val t = ArrayList<Long>(); val v = ArrayList<Double>()
        fun add(ms: Long, x: Double) { t.add(ms); v.add(x); while (t.isNotEmpty() && ms - t[0] > SETTLE_MS) { t.removeAt(0); v.removeAt(0) } }
        fun clear() { t.clear(); v.clear() }
        fun span() = if (t.size < 2) 0L else t.last() - t.first()
        fun mean() = if (v.isEmpty()) Double.NaN else v.average()
        fun spread() = if (v.isEmpty()) 0.0 else v.max() - v.min()
    }


    // ---------- ввод запаса хода по бензину с панели (км), шаг ≈ 50 мл ----------
    private const val L_PER_KM = 45.0 / 850.0   // бак 45 л = 850 км по WLTC (допущение; уточняется по заправкам)
    private const val KM_DROP = 2.0             // падение запаса хода между сессиями, при котором цикл считается нарушенным
    @Volatile var rangeAsk = ""                 // "start" | "end" | "prefill" | ""
    @Volatile private var rangeAnswer = Double.NaN
    @Volatile private var rangeDone = false

    private fun askRange(event: String, timeoutMs: Long = 180_000): Double? {
        rangeAnswer = Double.NaN; rangeDone = false; rangeAsk = event
        val t = System.currentTimeMillis()
        beep()
        while (!rangeDone && !stopFlag && System.currentTimeMillis() - t < timeoutMs) Thread.sleep(200)
        rangeAsk = ""
        return if (rangeDone && !rangeAnswer.isNaN()) rangeAnswer else null
    }

    fun submitRange(km: Double?) { rangeAnswer = km ?: Double.NaN; rangeDone = true }

    private fun lastKm(pr: android.content.SharedPreferences, cyc: Int): Double? {
        val v = pr.getFloat("gen_last_km", Float.NaN)
        return if (!v.isNaN() && pr.getInt("gen_last_km_cycle", -1) == cyc) v.toDouble() else null
    }

    private fun saveKm(pr: android.content.SharedPreferences, cyc: Int, km: Double) {
        pr.edit().putFloat("gen_last_km", km.toFloat()).putInt("gen_last_km_cycle", cyc).apply()
    }

    private const val GAP_MAX_KM = 40.0         // больше этого расхода вне сессий цикл лучше закрыть

    fun prevKm(ctx: Context): Double? = lastKm(prefs(ctx), cycle(ctx))

    private fun gapKm(pr: android.content.SharedPreferences, cyc: Int): Double =
        if (pr.getInt("gen_gap_cycle", -1) == cyc) pr.getFloat("gen_gap_km", 0f).toDouble() else 0.0

    private fun addGap(pr: android.content.SharedPreferences, cyc: Int, km: Double) {
        pr.edit().putFloat("gen_gap_km", (gapKm(pr, cyc) + km).toFloat()).putInt("gen_gap_cycle", cyc).apply()
    }

    private fun logRange(dir: File, cyc: Int, event: String, km: Double) {
        val f = File(dir, "gen_range.csv")
        if (!f.exists()) f.writeText("time,cycle,event,km\n")
        f.appendText("${iso(System.currentTimeMillis())},$cyc,$event,${fmt(km, 0)}\n")
    }

    private fun ensureHeader(f: File, hdr: String) {
        if (f.exists()) {
            val first = f.bufferedReader().use { it.readLine() }
            if (first != hdr) f.renameTo(File(f.parentFile, f.nameWithoutExtension + "_old_" + System.currentTimeMillis() / 1000 + ".csv"))
        }
        if (!f.exists()) f.writeText(hdr + "\n")
    }

    private fun beep() {
        try { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100).startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 600) } catch (_: Exception) {}
    }

    fun toggle(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>, toast: (String) -> Unit) {
        if (running) { if (phase == 3) skipFlag = true else stopFlag = true; return }
        if (!Elm.connected) return toast("Сначала подключись к ELM327")
        if (Elm.monitoring || Elm.scanning) return toast("Останови монитор/сканер")
        if (Homework.running) return toast("Идёт домашнее задание")
        running = true; stopFlag = false; skipFlag = false; phase = 1
        toast("Не запускай двигатель: сначала замер уровня топлива (30 с). Когда услышишь сигнал, включай «Максимальную подзарядку».")
        val cyc = cycle(ctx)
        Thread { run(ctx, dir, gps, cyc) }.start()
    }

    private fun run(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>, cyc: Int) {
        val t0req = System.currentTimeMillis()
        var t0 = t0req
        var startedLog = false
        var note = ""
        var eIn = 0.0; var eOut = 0.0; var gap = 0.0; var iceSec = 0.0
        var rpmSum = 0.0; var rpmDt = 0.0; var pMax = 0.0; var pSum = 0.0; var pDt = 0.0
        var soc0 = Double.NaN; var soc1 = Double.NaN; var c0 = Double.NaN; var c1 = Double.NaN
        var f0 = Double.NaN; var f1 = Double.NaN
        var range0 = Double.NaN; var range1 = Double.NaN
        var logName = ""
        val win = FuelWin()
        val pr = prefs(ctx)
        var aborted = false; var cancelled = false
        var phaseStart = System.currentTimeMillis()
        try {
            ctx.startForegroundService(android.content.Intent(ctx, KeepAliveService::class.java))
            Elm.releaseAutoLog()
            if (!Elm.logging) {
                val pids = Elm.loadPids(ctx.assets.open("pids_gen.txt").bufferedReader().readText())
                Thread { Elm.logLoop(dir, pids, gps) }.start()
                startedLog = true
                var w = 0
                while (!Elm.logging && w < 50) { Thread.sleep(100); w++ }
            }
            logName = Elm.currentFile?.name ?: ""
            status = "Замер уровня топлива: жди 30 с, двигатель не запускай"
            var prevMs = 0L; var pPrev = 0.0; var havePrev = false
            var warnedEngine = false
            while (Elm.connected && Elm.logging) {
                Thread.sleep(150)
                if (phase == 3 && skipFlag) break
                if (phase == 1 && stopFlag) { cancelled = true; break }
                if (phase == 2 && stopFlag) {                 // переход к замеру после
                    phase = 3; phaseStart = System.currentTimeMillis(); win.clear()
                    status = "Выключи режим генерации и заглуши ДВС. Замер уровня топлива 30 с (кнопка = пропустить)"
                }
                val ms = Elm.lastDataMs
                if (ms == prevMs) continue
                val v = Elm.lastValues
                val volt = v["batt_v"]?.toDoubleOrNull()
                val amp = v["batt_a"]?.toDoubleOrNull()
                val rpm = v["rpm"]?.toDoubleOrNull() ?: 0.0
                val soc = v["soc_f22f"]?.toDoubleOrNull() ?: v["soc_5b_pct"]?.toDoubleOrNull()
                val cool = v["coolant_c"]?.toDoubleOrNull()
                val fuel = v["fuel_pct"]?.toDoubleOrNull()
                val now = System.currentTimeMillis()

                if (phase == 1 || phase == 3) {
                    if (rpm > 300) {                              // двигатель работает: уровень не мерим, окно сбрасываем
                        win.clear()
                        if (phase == 1 && !warnedEngine) { warnedEngine = true; note += "ДВС был запущен до замера топлива; " }
                    } else if (fuel != null) win.add(now, fuel)
                    prevMs = ms
                    val waited = now - phaseStart
                    val ready = win.span() >= SETTLE_MS - 1500 && (win.spread() <= SPREAD_OK || waited >= MAX_WAIT_MS)
                    if (phase == 1 && ready) {
                        f0 = win.mean()
                        if (win.spread() > SPREAD_OK) note += "уровень топлива до сессии нестабилен (разброс %.1f%%); ".format(win.spread())
                        val pf = pr.getFloat("gen_last_fuel", Float.NaN); val ps = pr.getFloat("gen_last_soc", Float.NaN)
                        val acc = pr.getString("gen_accept_note", "") ?: ""
                        if (acc.isNotEmpty()) { note += "$acc; "; pr.edit().remove("gen_accept_note").apply() }
                        if (soc != null) soc0 = soc
                        if (!pf.isNaN()) {
                            val df = f0 - pf
                            val ds = if (ps.isNaN() || soc0.isNaN()) Double.NaN else soc0 - ps
                            val hasOpen = readCycle(dir, cyc).isNotEmpty()
                            if (false) {
                            } else if (df > 3.0 && hasOpen) {
                                alertKind = "refill"; alertFuel = f0
                                alertText = "Уровень топлива вырос на %.1f%%: была заправка, а цикл не закрыт.\nСначала нажми «Итоги генерации», впиши литры и закрой цикл. Потом запускай новую сессию.".format(df)
                                aborted = true; break
                            } else note += "между сессиями: топливо %+.2f%% (%+.2f л), SOC %s; ".format(df, df * 0.45, if (ds.isNaN()) "?" else "%+.1f%%".format(ds))
                        }
                        status = "Введи запас хода по БЕНЗИНУ с панели (км), двигатель пока не запускай"
                        val kmS = askRange("start")
                        if (stopFlag) { cancelled = true; break }
                        val hasOpen2 = readCycle(dir, cyc).isNotEmpty()
                        val dfL = if (pf.isNaN()) Double.NaN else f0 - pf
                        if (kmS != null) {
                            range0 = kmS
                            val lk = lastKm(pr, cyc)
                            if (lk != null && hasOpen2 && lk - kmS >= KM_DROP) {
                                val g = lk - kmS
                                if (g > GAP_MAX_KM) {
                                    alertKind = "drop"; alertFuel = f0
                                    alertText = "По панели вне сессий израсходовано %.0f км (≈ %.1f л): было %.0f, стало %.0f.\nЭто много, погрешность вычета станет заметной. Лучше закрыть цикл.".format(g, g * L_PER_KM, lk, kmS)
                                    aborted = true; break
                                }
                                addGap(pr, cyc, g)
                                logRange(dir, cyc, "gap", g)
                                note += "вне сессий −%.0f км (≈ %.2f л) вычтено; ".format(g, g * L_PER_KM)
                            }
                            logRange(dir, cyc, "start", kmS)
                        } else if (hasOpen2 && !dfL.isNaN() && dfL < -DROP_PCT) {
                            alertKind = "drop"; alertFuel = f0
                            alertText = "Между сессиями уровень топлива упал на %.2f%% (≈ %.2f л, ≈ %.0f км на ДВС), а запас хода по панели не введён, учесть расход нечем.".format(-dfL, -dfL * 0.45, -dfL * 0.45 / 0.06)
                            aborted = true; break
                        }
                        phase = 2; t0 = System.currentTimeMillis(); prevMs = 0L; havePrev = false
                        c0 = cool ?: Double.NaN
                        beep()
                        status = "ГОТОВО: уровень топлива %.2f%%. Можно включать «Максимальную подзарядку»".format(f0)
                    } else if (phase == 3 && ready) {
                        f1 = win.mean()
                        if (win.spread() > SPREAD_OK) note += "уровень топлива после сессии нестабилен (разброс %.1f%%); ".format(win.spread())
                        status = "Введи запас хода по БЕНЗИНУ с панели (км) после сессии"
                        val kmE = askRange("end")
                        if (kmE != null) { range1 = kmE; logRange(dir, cyc, "end", kmE) }
                        break
                    } else if (phase == 1) {
                        status = "Замер уровня топлива: %d из 30 с (разброс %.1f%%), двигатель не запускай".format(win.span() / 1000, win.spread())
                    }
                    continue
                }

                // ---- фаза 2: генерация ----
                if (soc != null) { if (soc0.isNaN()) soc0 = soc; soc1 = soc }
                if (cool != null) { c1 = cool; if (c0.isNaN()) c0 = cool }
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
            if (!Elm.connected) note += "потеря связи; "
        } catch (e: Exception) {
            note += "ошибка: ${e.message}; "
        } finally {
            if (startedLog) { Elm.logging = false; Thread.sleep(600); Elm.startAutoLog(ctx, dir, gps) }
            phase = 0
            if (aborted || cancelled) {
                status = if (aborted) "Сессия прервана: проверка топлива не пройдена" else "Сессия отменена"
                running = false
                refreshPrecision(ctx, dir)
            } else {
                val t1 = System.currentTimeMillis()
                val f = File(dir, SESSIONS)
                ensureHeader(f, HDR_S)
                val minutes = (t1 - t0) / 60000.0
                f.appendText(listOf(cyc.toString(), iso(t0), iso(t1), fmt(minutes, 2), fmt(eIn), fmt(eOut), fmt(eIn - eOut),
                    fmt(soc0, 1), fmt(soc1, 1), fmt(if (rpmDt > 0) rpmSum / rpmDt else Double.NaN, 0), fmt(iceSec / 60, 2),
                    fmt(pMax, 2), fmt(if (pDt > 0) pSum / pDt else Double.NaN, 2), fmt(c0, 0), fmt(c1, 0),
                    fmt(f0, 2), fmt(f1, 2), fmt(range0, 0), fmt(range1, 0), fmt(gap, 0), logName, note.replace(",", ";"))
                    .joinToString(",") + "\n")
                if (!f1.isNaN()) pr.edit().putFloat("gen_last_fuel", f1.toFloat()).apply()
                if (!range1.isNaN()) saveKm(pr, cyc, range1)
                if (!soc1.isNaN()) pr.edit().putFloat("gen_last_soc", soc1.toFloat()).apply()
                status = "Сессия записана: %.1f мин, в батарею %.2f кВт·ч, топливо %s→%s%%. %s".format(minutes, eIn - eOut,
                    if (f0.isNaN()) "—" else "%.2f".format(f0), if (f1.isNaN()) "—" else "%.2f".format(f1), note)
                running = false
                refreshPrecision(ctx, dir)
            }
        }
    }


    /** Контрольный замер уровня топлива перед заправкой: ловит расход топлива после последней сессии. */
    fun prefill(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>, toast: (String) -> Unit) {
        if (running) return toast("Идёт сессия или замер: сначала заверши (кнопка сессии)")
        if (!Elm.connected) return toast("Сначала подключись к ELM327")
        if (Elm.monitoring || Elm.scanning || Homework.running) return toast("Останови монитор/сканер/задание")
        if (readCycle(dir, cycle(ctx)).isEmpty()) return toast("В цикле нет сессий: сверять не с чем")
        running = true; stopFlag = false; phase = 4
        toast("Замер уровня топлива перед заправкой: 30 с, двигатель заглушён, не двигай машину.")
        Thread { runPrefill(ctx, dir, gps) }.start()
    }

    private fun runPrefill(ctx: Context, dir: File, gps: () -> Triple<Double, Double, Double>) {
        var startedLog = false
        val win = FuelWin()
        val pr = prefs(ctx)
        val begin = System.currentTimeMillis()
        try {
            ctx.startForegroundService(android.content.Intent(ctx, KeepAliveService::class.java))
            Elm.releaseAutoLog()
            if (!Elm.logging) {
                val pids = Elm.loadPids(ctx.assets.open("pids_gen.txt").bufferedReader().readText())
                Thread { Elm.logLoop(dir, pids, gps) }.start()
                startedLog = true
                var w = 0
                while (!Elm.logging && w < 50) { Thread.sleep(100); w++ }
            }
            var prevMs = 0L
            status = "Замер перед заправкой: жди 30 с, ДВС заглушён"
            while (Elm.connected && Elm.logging && !stopFlag) {
                Thread.sleep(150)
                val ms = Elm.lastDataMs
                if (ms == prevMs) continue
                prevMs = ms
                val v = Elm.lastValues
                val rpm = v["rpm"]?.toDoubleOrNull() ?: 0.0
                val fuel = v["fuel_pct"]?.toDoubleOrNull()
                val now = System.currentTimeMillis()
                if (rpm > 300) win.clear() else if (fuel != null) win.add(now, fuel)
                val ready = win.span() >= SETTLE_MS - 1500 && (win.spread() <= SPREAD_OK || now - begin >= MAX_WAIT_MS)
                if (!ready) { status = "Замер перед заправкой: %d из 30 с (разброс %.1f%%), ДВС заглушён".format(win.span() / 1000, win.spread()); continue }
                val f = win.mean()
                val pf = pr.getFloat("gen_last_fuel", Float.NaN)
                val df = if (pf.isNaN()) Double.NaN else f - pf
                val cyc = cycle(ctx)
                status = "Введи запас хода по БЕНЗИНУ с панели (км) перед заправкой"
                val km = askRange("prefill")
                val lk = lastKm(pr, cyc)
                val kmDrop = if (km != null && lk != null) lk - km else Double.NaN
                if (km != null) logRange(dir, cyc, "prefill", km)
                var gapNote = ""
                var tooBig = false
                if (km != null && !kmDrop.isNaN() && kmDrop >= KM_DROP) {
                    if (kmDrop > GAP_MAX_KM) tooBig = true
                    else { addGap(pr, cyc, kmDrop); logRange(dir, cyc, "gap", kmDrop); gapNote = ", вне сессий −%.0f км вычтено".format(kmDrop) }
                }
                val levelDrop = km == null && !df.isNaN() && df < -DROP_PCT
                if (levelDrop || tooBig) {
                    alertKind = "drop"; alertFuel = f
                    alertText = "Перед заправкой топлива меньше, чем в конце последней сессии: " +
                        (if (levelDrop) "уровень −%.2f%% (≈ %.2f л), запас хода не введён; ".format(-df, -df * 0.45) else "") +
                        (if (tooBig) "по панели −%.0f км (≈ %.1f л), это много; ".format(kmDrop, kmDrop * L_PER_KM) else "") +
                        "\nПосле последней сессии двигатель расходовал бензин, поэтому литры нельзя надёжно сопоставить с энергией этого цикла."
                    status = "Замер перед заправкой: топливо ушло"
                } else {
                    pr.edit().putFloat("gen_last_fuel", f.toFloat()).apply()
                    if (km != null) saveKm(pr, cyc, km)
                    beep()
                    status = "Замер перед заправкой: уровень %.2f%%, изменение %s, панель %s. МОЖНО ЗАПРАВЛЯТЬСЯ до щелчка, потом «Итоги генерации» → литры".format(
                        f, if (df.isNaN()) "—" else "%+.2f%%".format(df), (if (km == null) "не введена" else "%.0f км".format(km)) + gapNote)
                }
                break
            }
            if (stopFlag) status = "Замер перед заправкой отменён"
            if (!Elm.connected) status = "Замер перед заправкой: потеря связи"
        } catch (e: Exception) {
            status = "Замер перед заправкой: ошибка ${e.message}"
        } finally {
            if (startedLog) { Elm.logging = false; Thread.sleep(600); Elm.startAutoLog(ctx, dir, gps) }
            phase = 0; running = false
        }
    }

    private fun readCycle(dir: File, cyc: Int): List<List<String>> {
        val f = File(dir, SESSIONS)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).map { it.split(",") }.filter { it.size >= 22 && it[0] == cyc.toString() }
    }

    fun totals(ctx: Context, dir: File): String {
        val cyc = cycle(ctx)
        val rows = readCycle(dir, cyc)
        val min = rows.sumOf { it[3].toDoubleOrNull() ?: 0.0 }
        val kwh = rows.sumOf { it[6].toDoubleOrNull() ?: 0.0 }
        val sb = StringBuilder("СЕРИЯ: ${precisionFor(dir)}\n\n")
        sb.append("Цикл №$cyc (от последней заправки до щелчка)\nСессий: ${rows.size}, ${"%.1f".format(min)} мин, в батарею ${"%.2f".format(kwh)} кВт·ч")
        rows.forEachIndexed { i, r ->
            sb.append("\n${i + 1}) ${r[1].takeLast(8)} ${r[3]} мин, ${r[6]} кВт·ч, SOC ${r[7]}→${r[8]}%, топливо ${r[15]}→${r[16]}%, ${r[9]} об/мин")
            val ra = r[17].toDoubleOrNull(); val rb = r[18].toDoubleOrNull()
            if (ra != null && rb != null && ra > rb) {
                val lit = (ra - rb) * L_PER_KM
                sb.append("\n   панель: ${"%.0f".format(ra)}→${"%.0f".format(rb)} км (−${"%.0f".format(ra - rb)} км ≈ ${"%.2f".format(lit)} л) → ${"%.2f".format((r[6].toDoubleOrNull() ?: 0.0) / lit)} кВт·ч/л")
            }
            if (r[21].isNotBlank()) sb.append("\n   ⚠ ${r[21]}")
        }
        val fa = rows.firstOrNull()?.get(15)?.toDoubleOrNull(); val fb = rows.lastOrNull()?.get(16)?.toDoubleOrNull()
        if (fa != null && fb != null) sb.append("\nУровень топлива за цикл: %.1f%% → %.1f%% (≈ %.2f л по датчику, бак 45 л)".format(fa, fb, (fa - fb) * 0.45))
        // ---- промежуточные итоги текущего цикла ----
        val pr0 = prefs(ctx)
        val gap0 = gapKm(pr0, cyc)
        var panelKm = 0.0; var panelAll = true
        rows.forEach { r ->
            val ra = r[17].toDoubleOrNull(); val rb = r[18].toDoubleOrNull()
            if (ra != null && rb != null && ra >= rb) panelKm += ra - rb else panelAll = false
        }
        val litPanel = if (panelAll && panelKm > 0) panelKm * L_PER_KM else Double.NaN
        val litSensor = if (fa != null && fb != null && fa > fb) (fa - fb) * 0.45 - gap0 * L_PER_KM else Double.NaN
        val litCur = if (!litPanel.isNaN()) litPanel else litSensor
        sb.append("\n\n=== ПРОМЕЖУТОЧНО: текущий цикл ===")
        sb.append("\nВ батарею: %.2f кВт·ч".format(kwh))
        if (litCur.isNaN() || litCur <= 0) sb.append("\nБензин на зарядку: нет данных (введи км в начале и в конце сессий)")
        else {
            sb.append("\nБензин на зарядку: ≈ %.2f л (%s)".format(litCur, if (!litPanel.isNaN()) "по панели" else "по датчику уровня"))
            sb.append("\nС 1 л в батарею: %.3f кВт·ч".format(kwh / litCur))
            sb.append("\nТепловой коэффициент (от 8,9 кВт·ч/л): %.3f".format(kwh / litCur / 8.9))
        }
        sb.append("\nВне сессий израсходовано: %.0f км (≈ %.2f л)%s".format(gap0, gap0 * L_PER_KM, if (gap0 <= 0.0) " → цикл чистый" else ""))
        // ---- итого по закрытым циклам ----
        val cf = File(dir, CYCLES)
        var nC = 0; var kwhC = 0.0; var litC = 0.0; var nClean = 0; var kwhClean = 0.0; var litClean = 0.0
        if (cf.exists()) cf.readLines().drop(1).map { it.split(",") }.forEach { r ->
            if (r.size < 8) return@forEach
            if (r.size >= 11 && r[10].isNotBlank() && r[10] != "ok") return@forEach
            val e = r[4].toDoubleOrNull() ?: return@forEach
            val c = r[7].toDoubleOrNull() ?: return@forEach
            val lr = r[5].toDoubleOrNull() ?: return@forEach
            if (c <= 0) return@forEach
            val ls = e / c
            nC++; kwhC += e; litC += ls
            if (Math.abs(ls - lr) < 0.02) { nClean++; kwhClean += e; litClean += ls }
        }
        sb.append("\n\n=== ИТОГО по закрытым циклам ===")
        if (nC == 0) sb.append("\nПока нет закрытых циклов.")
        else {
            sb.append("\nЦиклов: $nC. Бензина на зарядку: %.2f л. В батарею: %.2f кВт·ч".format(litC, kwhC))
            sb.append("\nС 1 л: %.3f кВт·ч, тепловой коэффициент %.3f".format(kwhC / litC, kwhC / litC / 8.9))
            if (nClean > 0) sb.append("\nТолько чистые циклы ($nClean): %.2f л, %.2f кВт·ч → %.3f кВт·ч/л, коэф. %.3f".format(litClean, kwhClean, kwhClean / litClean, kwhClean / litClean / 8.9))
            if (!litCur.isNaN() && litCur > 0) sb.append("\nВместе с текущим (оценка): %.2f л, %.2f кВт·ч → %.3f кВт·ч/л".format(litC + litCur, kwhC + kwh, (kwhC + kwh) / (litC + litCur)))
        }
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
        val prc = prefs(ctx)
        val gap = gapKm(prc, cyc)
        val kmFirst = rows.firstOrNull()?.get(17)?.toDoubleOrNull()
        val kmLastPref = prc.getFloat("gen_last_km", Float.NaN)
        val kmLast = if (!kmLastPref.isNaN() && prc.getInt("gen_last_km_cycle", -1) == cyc) kmLastPref.toDouble() else rows.lastOrNull()?.get(18)?.toDoubleOrNull()
        val dkAll = if (kmFirst != null && kmLast != null) kmFirst - kmLast else Double.NaN
        val sessLiters = if (gap > 0 && !dkAll.isNaN() && dkAll > gap) liters * (1 - gap / dkAll) else liters
        val c = kwh / sessLiters
        val k = if (shown != null && shown > 0) liters / shown else Double.NaN
        val f = File(dir, CYCLES)
        ensureHeader(f, HDR_C)
        f.appendText(listOf(cyc.toString(), iso(System.currentTimeMillis()), rows.size.toString(), fmt(min, 1), fmt(kwh, 2), fmt(liters, 2),
            if (shown == null) "" else fmt(shown, 2), fmt(c, 3), fmt(k, 3), fmt(pack, 1), "ok").joinToString(",") + "\n")
        prefs(ctx).edit().putInt("gen_cycle", cyc + 1).remove("gen_gap_km").apply()
        refreshPrecision(ctx, dir)
        return buildString {
            append("Цикл №$cyc закрыт.\nВ батарею: ${"%.2f".format(kwh)} кВт·ч за ${"%.0f".format(min)} мин\nЗалито: ${"%.2f".format(liters)} л\n")
            if (gap <= 0.0) append("Цикл ЧИСТЫЙ: вне сессий бензин не расходовался.\n")
            if (sessLiters != liters) append("Вне сессий израсходовано %.0f км по панели (≈ %.2f л), вычтено. Литров на сессии: %.2f\n".format(gap, liters - sessLiters, sessLiters))
            else if (gap > 0) append("Вне сессий было %.0f км, но вычесть не удалось (нет начального/конечного км).\n".format(gap))
            append("Итого на батарею: %.3f кВт·ч/л (КПД от топлива ≈ %.1f %%)\n".format(c, c / 8.9 * 100))
            if (!k.isNaN()) append("Поправка расходомера: %.3f (фактически = показания × %.3f)\n".format(k, k))
            val kmA = rows.firstOrNull()?.get(17)?.toDoubleOrNull()
            val pre = prefs(ctx).getFloat("gen_last_km", Float.NaN)
            val kmB = if (!pre.isNaN() && prefs(ctx).getInt("gen_last_km_cycle", -1) == cyc) pre.toDouble() else rows.lastOrNull()?.get(18)?.toDoubleOrNull()
            if (kmA != null && kmB != null && kmA > kmB) {
                val dk = kmA - kmB
                append("По панели за цикл: %.0f → %.0f км (−%.0f км). Реальный расход на километр панели = %.4f л/км (при 45 л = 850 км было бы %.4f).\n".format(kmA, kmB, dk, liters / dk, L_PER_KM))
            }
            val fa = rows.firstOrNull()?.get(15)?.toDoubleOrNull(); val fb = rows.lastOrNull()?.get(16)?.toDoubleOrNull()
            if (fa != null && fb != null) append("Датчик уровня за цикл: %.1f%% → %.1f%% (≈ %.2f л)\n".format(fa, fb, (fa - fb) * 0.45))
            if (!pack.isNaN()) append("Ёмкость по SOC ≈ %.1f кВт·ч (для проверки 31,7)\n".format(pack))
            append("\nСерия: ${precision}\n")
            append("Новый цикл №${cyc + 1} начат. Можно продолжать: следующая заправка добавит точности (общая ошибка заправок не растёт от их числа).")
        }
    }


    // ---------- серия (несколько заправок) и точность ----------
    private class Series(val n: Int, val liters: Double, val kwh: Double, val shownL: Double, val shownSumL: Double)

    private fun series(dir: File): Series {
        val f = File(dir, CYCLES)
        if (!f.exists()) return Series(0, 0.0, 0.0, 0.0, 0.0)
        var n = 0; var l = 0.0; var e = 0.0; var kl = 0.0; var ks = 0.0
        f.readLines().drop(1).map { it.split(",") }.forEach { r ->
            if (r.size < 8) return@forEach
            if (r.size >= 11 && r[10].isNotBlank() && r[10] != "ok") return@forEach
            val lr = r[5].toDoubleOrNull() ?: return@forEach
            val ek = r[4].toDoubleOrNull() ?: return@forEach
            n++; l += lr; e += ek
            val sh = r[6].toDoubleOrNull()
            if (sh != null && sh > 0) { kl += lr; ks += sh }
        }
        return Series(n, l, e, kl, ks)
    }

    fun precisionFor(dir: File): String {
        val s = series(dir)
        if (s.n == 0 || s.liters <= 0) return "нет закрытых интервалов. Нужна хотя бы одна заправка с вводом литров."
        // Заправка до щелчка: литры i-й заправки = расход + (отклонение щелчка i − отклонение щелчка i-1).
        // Сумма по серии «телескопируется»: ошибка заправок = два отклонения щелчка, независимо от числа заправок.
        val errFill = Math.sqrt(2.0) * SIGMA_FILL / s.liters
        val err = Math.hypot(errFill, ERR_ENERGY)
        val c = s.kwh / s.liters
        val sb = StringBuilder("%d инт., %.1f л, %.1f кВт·ч → %.3f кВт·ч/л ± %.1f%% (заправки ±%.1f%%, энергия ±%.1f%%)".format(
            s.n, s.liters, s.kwh, c, err * 100, errFill * 100, ERR_ENERGY * 100))
        if (s.shownSumL > 0) sb.append("; поправка расходомера %.3f".format(s.shownL / s.shownSumL))
        val need = Math.sqrt(2.0) * SIGMA_FILL / TARGET_FILL - s.liters
        if (need <= 0) sb.append("\n✔ ТОЧНОСТИ ДОСТАТОЧНО: можно останавливаться, дальше упрёшься в погрешность измерения тока, а не в заправки.")
        else sb.append("\nДо вклада заправок ±1 %% нужно ещё ≈ %.0f л (≈ %.0f кВт·ч генерации).".format(need, need * C_REF))
        return sb.toString()
    }

    fun refreshPrecision(ctx: Context, dir: File) {
        val s = series(dir)
        precision = if (s.n == 0) "Точность серии: пока нет закрытых интервалов" else {
            val errFill = Math.sqrt(2.0) * SIGMA_FILL / s.liters
            val err = Math.hypot(errFill, ERR_ENERGY)
            val need = Math.sqrt(2.0) * SIGMA_FILL / TARGET_FILL - s.liters
            "Точность серии: ±%.1f%% (%.1f л, %d инт.). %s".format(err * 100, s.liters, s.n,
                if (need <= 0) "МОЖНО ОСТАНАВЛИВАТЬСЯ" else "Нужно ещё ≈ %.0f л".format(need))
        }
    }

    /** Аннулирует текущий цикл (расход топлива вне сессий), начинает новый. */
    fun voidCycle(ctx: Context, dir: File, reason: String) {
        val cyc = cycle(ctx)
        val rows = readCycle(dir, cyc)
        val f = File(dir, CYCLES)
        ensureHeader(f, HDR_C)
        f.appendText(listOf(cyc.toString(), iso(System.currentTimeMillis()), rows.size.toString(),
            fmt(rows.sumOf { it[3].toDoubleOrNull() ?: 0.0 }, 1), fmt(rows.sumOf { it[6].toDoubleOrNull() ?: 0.0 }, 2), "", "", "", "", "",
            "void: " + reason.replace(",", ";")).joinToString(",") + "\n")
        prefs(ctx).edit().putInt("gen_cycle", cyc + 1).remove("gen_last_fuel").remove("gen_last_soc").apply()
        refreshPrecision(ctx, dir)
    }

    /** Продолжить цикл вопреки падению топлива: принять новый уровень за базу и пометить в следующей сессии. */
    fun acceptFuel(ctx: Context, newFuel: Double) {
        val old = prefs(ctx).getFloat("gen_last_fuel", Float.NaN)
        prefs(ctx).edit().putFloat("gen_last_fuel", newFuel.toFloat())
            .putString("gen_accept_note", "принято вручную: топливо %.1f→%.1f%% вне сессий".format(old.toDouble(), newFuel)).apply()
    }

    /** zip: gen_sessions.csv, gen_cycles.csv и логи, на которые ссылаются сессии. Возвращает файл. */
    fun report(ctx: Context, dir: File): File? {
        val names = LinkedHashSet<String>()
        listOf(SESSIONS, CYCLES).forEach { if (File(dir, it).exists()) names.add(it) }
        File(dir, SESSIONS).takeIf { it.exists() }?.readLines()?.drop(1)?.forEach { l ->
            val p = l.split(","); if (p.size >= 21 && p[20].isNotBlank() && File(dir, p[20]).exists()) names.add(p[20])
        }
        if (names.isEmpty()) return null
        val zip = File(ctx.cacheDir, "gen_report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            names.forEach { n -> z.putNextEntry(ZipEntry(n)); File(dir, n).inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        return zip
    }
}
