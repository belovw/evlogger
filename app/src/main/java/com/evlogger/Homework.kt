package com.evlogger

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.AlertDialog
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** «Домашнее задание»: сценарий из assets/homework.txt. Выполняется по шагам, в конце всё упаковывается в zip и отправляется. */
object Homework {
    @Volatile var running = false
    @Volatile var abort = false
    /** Текущая инструкция на экране (пусто = не показывать) и момент конца шага для обратного отсчёта. */
    @Volatile var banner = ""
    @Volatile var stepEndMs = 0L
    /** Процент выполнения задания целиком (по сумме длительностей шагов watch/log), для экрана. */
    @Volatile var progressPct = 0
    @Volatile private var totalPlannedMs = 0L
    @Volatile private var doneMs = 0L
    @Volatile private var curStepStartMs = 0L
    @Volatile private var curStepPlannedMs = 0L
    private fun stepSeconds(st: Step): Long = when (st.cmd) {
        "watch" -> st.args.getOrNull(2)?.toLongOrNull() ?: 0L
        "log" -> st.args.getOrNull(0)?.toLongOrNull() ?: 0L
        else -> 0L
    }
    private fun fingerprint(steps: List<Step>) = steps.joinToString("|") { it.cmd + ":" + it.args.joinToString(",") }.hashCode().toString()
    private fun progressTicker() {
        Thread {
            while (running) {
                val cur = if (curStepPlannedMs > 0) minOf(System.currentTimeMillis() - curStepStartMs, curStepPlannedMs) else 0L
                progressPct = if (totalPlannedMs > 0) (100.0 * (doneMs + cur) / totalPlannedMs).toInt().coerceIn(0, 100) else 0
                Thread.sleep(500)
            }
        }.start()
    }

    private class Step(val cmd: String, val args: List<String>)

    private const val REMOTE = "https://raw.githubusercontent.com/belovw/evlogger/main/homework.txt"

    private fun parse(text: String): List<Step> =
        text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { l -> val p = l.split(";").map { it.trim() }; Step(p[0].lowercase(), p.drop(1)) }

    /** Сначала файл homework.txt из репозитория на GitHub (обновляется без новой версии приложения), иначе встроенный. */
    private fun fetch(act: Activity): Pair<List<Step>, String> {
        try {
            val c = java.net.URL(REMOTE + "?t=" + System.currentTimeMillis()).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
            try { return Pair(parse(c.inputStream.bufferedReader().readText()), "с GitHub") } finally { c.disconnect() }
        } catch (e: Exception) {}
        return Pair(parse(act.assets.open("homework.txt").bufferedReader().readText()), "встроенное, нет связи с GitHub")
    }

    private fun fetchText(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun build(act: Activity): Int =
        try { act.packageManager.getPackageInfo(act.packageName, 0).versionName?.substringAfter("0.")?.toIntOrNull() ?: 0 } catch (e: Exception) { 0 }

    private fun range(s: String): Pair<Int, Int>? {
        val p = s.split("-"); val a = p.getOrNull(0)?.toIntOrNull(16); val b = p.getOrNull(1)?.toIntOrNull(16)
        return if (a != null && b != null && a <= b) Pair(a, b) else null
    }

    private fun dids(s: String): List<String> {
        val out = ArrayList<String>()
        for (tok in s.uppercase().split(Regex("[\\s,]+"))) {
            if (tok.contains("-")) {
                val r = range(tok) ?: continue
                for (v in r.first..r.second) out.add("%04X".format(v))
            } else if (tok.length == 4 && tok.toIntOrNull(16) != null) out.add(tok)
        }
        return out
    }

    private fun beep(act: Activity) {
        try {
            val v = act.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (e: Exception) {}
    }

    /** Подсказки вида «00:00 Заведи ДВС | 01:30 Выключи ДВС»: по таймеру меняют инструкцию, пикают и пишут время в notes. */
    private fun runCues(act: Activity, spec: String, seconds: Long, note: (String) -> Unit, stop: () -> Unit) {
        val cues = spec.split("|").mapNotNull { c ->
            val m = Regex("""^\s*(\d+):(\d+)\s+(.*)$""").find(c) ?: return@mapNotNull null
            Pair(m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong(), m.groupValues[3].trim())
        }.sortedBy { it.first }
        val start = System.currentTimeMillis()
        stepEndMs = start + seconds * 1000
        Thread {
            var i = 0
            while (!abort && System.currentTimeMillis() < stepEndMs) {
                val el = (System.currentTimeMillis() - start) / 1000
                while (i < cues.size && cues[i].first <= el) { banner = cues[i].second; note("cue: ${cues[i].second}"); beep(act); i++ }
                Thread.sleep(300)
            }
            banner = ""; stop()
        }.start()
    }

    fun start(act: Activity, gps: () -> Triple<Double, Double, Double>, toast: (String) -> Unit) {
        if (running) return toast("Задание уже выполняется")
        if (!Elm.connected) return toast("Сначала подключись")
        Elm.releaseAutoLog()
        if (Elm.logging || Elm.monitoring || Elm.scanning) return toast("Останови запись/сканер")
        toast("Загружаю задание…")
        Thread {
            val (steps, src) = try { fetch(act) } catch (e: Exception) { return@Thread act.runOnUiThread { toast("Не удалось прочитать задание") } }
            val title = steps.firstOrNull { it.cmd == "title" }?.args?.firstOrNull() ?: "без описания"
            val need = steps.firstOrNull { it.cmd == "needs" }?.args?.firstOrNull()?.toIntOrNull() ?: 0
            val pr = act.getSharedPreferences("evlogger", Context.MODE_PRIVATE)
            val fp = fingerprint(steps)
            val savedIdx = if (pr.getString("homework_fp", "") == fp) pr.getInt("homework_step", 0) else 0
            act.runOnUiThread {
                if (build(act) < need) {
                    AlertDialog.Builder(act).setTitle("Нужно обновить приложение")
                        .setMessage("Задание требует версию 0.$need, у вас 0.${build(act)}. Нажми «Обновить приложение».")
                        .setPositiveButton("OK", null).show()
                } else if (savedIdx in 1 until steps.size) {
                    AlertDialog.Builder(act).setTitle("Домашнее задание ($src)")
                        .setMessage("$title\n\nВ прошлый раз остановился на шаге ${savedIdx + 1} из ${steps.size}. Продолжить с того же места или начать сначала?")
                        .setPositiveButton("Продолжить с шага ${savedIdx + 1}") { _, _ -> Thread { run(act, steps, gps, toast, savedIdx) }.start() }
                        .setNeutralButton("Начать сначала") { _, _ -> Thread { run(act, steps, gps, toast, 0) }.start() }
                        .setNegativeButton("Отмена", null).show()
                } else {
                    AlertDialog.Builder(act).setTitle("Домашнее задание ($src)").setMessage(title + "\n\nНачать?")
                        .setPositiveButton("Начать") { _, _ -> Thread { run(act, steps, gps, toast, 0) }.start() }
                        .setNegativeButton("Отмена", null).show()
                }
            }
        }.start()
    }

    private fun run(act: Activity, steps: List<Step>, gps: () -> Triple<Double, Double, Double>, toast: (String) -> Unit, startIndex: Int = 0) {
        running = true; abort = false
        val dir = act.getExternalFilesDir(null)!!
        val t0 = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notes = File(dir, "notes_$ts.txt")
        fun note(s: String) { notes.appendText("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $s\n") }
        note("homework started, app ${Elm.appVersion}${if (startIndex > 0) ", продолжение с шага ${startIndex + 1}" else ""}")
        val pr = act.getSharedPreferences("evlogger", Context.MODE_PRIVATE)
        val fp = fingerprint(steps)
        totalPlannedMs = steps.sumOf { stepSeconds(it) } * 1000
        doneMs = steps.take(startIndex).sumOf { stepSeconds(it) } * 1000
        progressPct = 0
        progressTicker()
        try {
            act.startForegroundService(Intent(act, KeepAliveService::class.java))
            try {
            for ((i, st) in steps.withIndex()) {
                if (i < startIndex) continue
                if (abort || !Elm.connected) break
                Elm.scanProgress = "задание: шаг ${i + 1}/${steps.size}"
                curStepStartMs = System.currentTimeMillis()
                curStepPlannedMs = stepSeconds(st) * 1000
                when (st.cmd) {
                    "note" -> {
                        val latch = CountDownLatch(1)
                        act.runOnUiThread {
                            AlertDialog.Builder(act).setTitle("Задание").setMessage(st.args.getOrElse(0) { "" })
                                .setCancelable(false).setPositiveButton("Готово") { _, _ -> latch.countDown() }.show()
                        }
                        latch.await()
                        note("note done: ${st.args.getOrElse(0) { "" }}")
                    }
                    "update" -> {
                        val last = Updater.latestBuild() ?: 0
                        if (last > build(act)) {
                            note("update: 0.${build(act)} -> 0.$last")
                            Elm.scanProgress = "скачиваю обновление 0.$last"
                            val apk = Updater.download(act) { }
                            act.runOnUiThread { Updater.install(act, apk) }
                            abort = true
                            note("update installer started; homework stopped, run it again after install")
                        } else note("update: already latest 0.${build(act)}")
                    }
                    "survey" -> {
                        val only = st.args.getOrElse(0) { "" }.uppercase().split(Regex("\\s+")).filter { it.length == 3 }.toSet()
                        val r = range(st.args.getOrElse(1) { "" }) ?: continue
                        note("survey ${st.args}"); Elm.surveyEcus(dir, r.first, r.second, st.args.getOrNull(2)?.toIntOrNull() ?: 1, only)
                    }
                    "scan" -> {
                        val r = range(st.args.getOrElse(1) { "" }) ?: continue
                        note("scan ${st.args}"); Elm.scanDids(dir, st.args[0].uppercase(), r.first, r.second, st.args.getOrNull(2)?.toIntOrNull() ?: 1)
                    }
                    "log" -> {
                        val sec = st.args.getOrNull(0)?.toLongOrNull() ?: 60
                        note("log ${st.args}")
                        val custom = File(dir, "pids.txt")
                        val pidsName = st.args.getOrNull(2)?.removePrefix("pids=")?.takeIf { it.isNotBlank() && st.args.getOrNull(2)?.startsWith("pids=") == true }
                        var text: String? = null
                        if (pidsName != null) text = try { fetchText("https://raw.githubusercontent.com/belovw/evlogger/main/$pidsName?t=${System.currentTimeMillis()}") } catch (e: Exception) { null }
                        note("pids: ${if (text != null) "remote $pidsName" else "default"}")
                        val list = Elm.loadPids(text ?: if (custom.exists()) custom.readText() else act.assets.open("pids.txt").bufferedReader().readText())
                        runCues(act, st.args.getOrElse(1) { "" }, sec, ::note) { Elm.logging = false }
                        Elm.logLoop(dir, list, gps)
                    }
                    "watch" -> {
                        val list = dids(st.args.getOrElse(1) { "" })
                        val sec = st.args.getOrNull(2)?.toLongOrNull() ?: 60
                        note("watch ${st.args}")
                        runCues(act, st.args.getOrElse(3) { "" }, sec, ::note) { Elm.scanning = false }
                        Elm.watchDids(dir, st.args[0].uppercase(), list)
                    }
                }
                if (abort || !Elm.connected) break
                doneMs += stepSeconds(st) * 1000           // шаг прошёл целиком — засчитываем в прогресс
                pr.edit().putString("homework_fp", fp).putInt("homework_step", i + 1).apply()  // можно продолжить отсюда при следующем запуске
            }
            } catch (e: Exception) { note("step error: ${e.message}"); abort = true; Elm.logging = false; Elm.scanning = false }
            if (!abort) pr.edit().putInt("homework_step", 0).apply()   // дошли до конца без остановки — следующий запуск начнётся с начала
            note("homework finished${if (abort) " (aborted, можно продолжить с того же места)" else ""}")
            val zip = File(act.cacheDir, "homework_$ts.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                dir.listFiles { f -> f.isFile && f.lastModified() >= t0 - 2000 }?.forEach { f ->
                    z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
            act.runOnUiThread {
                val uri = FileProvider.getUriForFile(act, "com.evlogger.files", zip)
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Отправить результаты задания"))
            }
        } catch (e: Exception) {
            note("error: ${e.message}"); toast("Ошибка задания: ${e.message}")
        } finally {
            running = false; banner = ""; progressPct = 0; Elm.scanning = false; Elm.logging = false; Elm.scanProgress = ""
        }
    }
}
