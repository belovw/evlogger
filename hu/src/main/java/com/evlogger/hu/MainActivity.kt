package com.evlogger.hu

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var recBtn: Button
    @Volatile private var recording = false
    private val dir: File get() = getExternalFilesDir(null)!!
    private fun prefs() = getSharedPreferences("hu", Context.MODE_PRIVATE)
    private fun ts() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun log(s: String) = runOnUiThread { out.text = s }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 18f; setOnClickListener { f() }; root.addView(this) }
        root.addView(TextView(this).apply { text = "HU Probe: разведка автомобильных данных магнитолы (только чтение)"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        btn("1. Список свойств машины и их значения") { Thread { scan() }.start() }
        recBtn = btn("2. Запись изменений: СТАРТ") { toggleRec() }
        val repo = EditText(this).apply { hint = "GitHub репозиторий (владелец/имя)"; setText(prefs().getString("repo", "")) }
        val tok = EditText(this).apply { hint = "GitHub токен (Contents: запись)"; setText(prefs().getString("tok", "")) }
        root.addView(repo); root.addView(tok)
        btn("3. Отправить файлы на GitHub") {
            prefs().edit().putString("repo", repo.text.toString().trim()).putString("tok", tok.text.toString().trim()).apply()
            Thread { upload() }.start()
        }
        out = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); text = "Готов. Нажми «1».\n\nПрава выдай один раз через ADB (см. подсказку в чате)." }
        root.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun scan() {
        log("Подключение к службе автомобиля…")
        if (!CarApi.connect(this)) { log("НЕ ПОДКЛЮЧИЛОСЬ: ${CarApi.lastError}"); File(dir, "hu_props_${ts()}.txt").writeText("connect failed: ${CarApi.lastError}\n"); return }
        val props = CarApi.list()
        if (props.isEmpty()) { log("Список пуст. ${CarApi.lastError}"); return }
        val sb = StringBuilder("id,name,area,access,change,value|status\n")
        var ok = 0; var err = 0
        for (p in props) for (a in p.areas) {
            val v = CarApi.read(p, a)
            if (v.startsWith("ERR")) err++ else ok++
            sb.append("0x%08x,%s,%d,%d,%d,%s\n".format(p.id, p.name, a, p.access, p.change, v.replace("\n", " ")))
        }
        val f = File(dir, "hu_props_${ts()}.csv"); f.writeText(sb.toString())
        log("Свойств: ${props.size}. Прочитано: $ok, отказов: $err.\nФайл: ${f.name}\n\n" + sb.lines().take(60).joinToString("\n"))
    }

    private fun toggleRec() {
        if (recording) { recording = false; return }
        recording = true; recBtn.text = "2. Запись изменений: СТОП"
        Thread {
            if (!CarApi.connect(this)) { log("НЕ ПОДКЛЮЧИЛОСЬ: ${CarApi.lastError}"); recording = false; runOnUiThread { recBtn.text = "2. Запись изменений: СТАРТ" }; return@Thread }
            val props = CarApi.list()
            val f = File(dir, "hu_car_${ts()}.csv")
            f.writeText("time_ms,iso,id,name,area,value|status\n")
            val last = HashMap<String, String>()
            var n = 0L; var rows = 0L
            while (recording) {
                val t0 = System.currentTimeMillis()
                val sb = StringBuilder()
                for (p in props) for (a in p.areas) {
                    val v = CarApi.read(p, a)
                    val k = "${p.id}/$a"
                    if (last[k] != v) {
                        last[k] = v; rows++
                        sb.append("$t0,${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(t0))},0x%08x,%s,%d,%s\n".format(p.id, p.name, a, v.replace("\n", " ")))
                    }
                }
                if (sb.isNotEmpty()) f.appendText(sb.toString())
                n++
                log("Запись: $n проходов, $rows записей изменений\nФайл: ${f.name}")
                val dt = 2000 - (System.currentTimeMillis() - t0)
                if (dt > 0) Thread.sleep(dt)
            }
            runOnUiThread { recBtn.text = "2. Запись изменений: СТАРТ" }
        }.start()
    }

    private fun upload() {
        val repo = prefs().getString("repo", "") ?: ""; val tok = prefs().getString("tok", "") ?: ""
        if (repo.isEmpty() || tok.isEmpty()) return log("Введи репозиторий и токен выше.")
        val files = (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("hu_") }
        if (files.isEmpty()) return log("Нет файлов hu_*.")
        val r = Uploader.upload(files, repo, tok) { log(it) }
        r.ok.forEach { it.delete() }
        log("Отправлено: ${r.ok.size} из ${files.size} в ${r.folder}\n" + r.errors.joinToString("\n"))
    }
}
