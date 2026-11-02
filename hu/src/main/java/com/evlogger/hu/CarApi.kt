package com.evlogger.hu

import android.content.Context
import java.lang.reflect.InvocationTargetException

/** Доступ к штатному автомобильному API Android через отражение (в SDK его нет). Только чтение. */
object CarApi {
    class Prop(val id: Int, val name: String, val areas: IntArray, val access: Int, val change: Int, val type: Class<*>?)

    @Volatile var lastError = ""
    private var car: Any? = null
    private var mgr: Any? = null

    private fun root(e: Throwable): Throwable { var t = e; while ((t is InvocationTargetException) && t.cause != null) t = t.cause!!; return t }

    /** Подключение. Вызывать не из главного потока. */
    fun connect(ctx: Context): Boolean {
        if (mgr != null) return true
        try {
            val carCls = Class.forName("android.car.Car")
            val c = carCls.getMethod("createCar", Context::class.java).invoke(null, ctx)
            car = c
            if (c == null) { lastError = "Car.createCar вернул null (службы автомобиля нет)"; return false }
            val isConn = carCls.getMethod("isConnected")
            var w = 0
            while (!(isConn.invoke(c) as Boolean) && w < 60) {
                if (w == 0) try { carCls.getMethod("connect").invoke(c) } catch (_: Exception) {}
                Thread.sleep(100); w++
            }
            if (!(isConn.invoke(c) as Boolean)) { lastError = "не удалось подключиться к службе автомобиля за 6 с"; return false }
            mgr = carCls.getMethod("getCarManager", String::class.java).invoke(c, "property")
            if (mgr == null) { lastError = "менеджер свойств (property) недоступен"; return false }
            return true
        } catch (e: Throwable) {
            val r = root(e); lastError = "${r.javaClass.simpleName}: ${r.message}"; return false
        }
    }

    private fun idName(id: Int): String = try {
        Class.forName("android.car.VehiclePropertyIds").getMethod("toString", Int::class.javaPrimitiveType).invoke(null, id) as String
    } catch (_: Throwable) { "0x%08x".format(id) }

    fun list(): List<Prop> {
        val m = mgr ?: return emptyList()
        return try {
            val l = m.javaClass.getMethod("getPropertyList").invoke(m) as List<*>
            l.mapNotNull { c ->
                try {
                    val k = c!!.javaClass
                    val id = k.getMethod("getPropertyId").invoke(c) as Int
                    val areas = try { k.getMethod("getAreaIds").invoke(c) as IntArray } catch (_: Throwable) { intArrayOf(0) }
                    val acc = try { k.getMethod("getAccess").invoke(c) as Int } catch (_: Throwable) { -1 }
                    val chg = try { k.getMethod("getChangeMode").invoke(c) as Int } catch (_: Throwable) { -1 }
                    val ty = try { k.getMethod("getPropertyType").invoke(c) as Class<*> } catch (_: Throwable) { null }
                    Prop(id, idName(id), if (areas.isEmpty()) intArrayOf(0) else areas, acc, chg, ty)
                } catch (_: Throwable) { null }
            }.sortedBy { it.id }
        } catch (e: Throwable) { val r = root(e); lastError = "getPropertyList: ${r.javaClass.simpleName}: ${r.message}"; emptyList() }
    }

    private fun show(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(" ")
        is FloatArray -> v.joinToString(" ")
        is LongArray -> v.joinToString(" ")
        is ByteArray -> v.joinToString(" ") { "%02x".format(it) }
        is Array<*> -> v.joinToString(" ") { show(it) }
        else -> v.toString()
    }

    /** Возвращает "значение|статус" или "ERR: причина". */
    fun read(p: Prop, area: Int): String {
        val m = mgr ?: return "ERR: нет подключения"
        return try {
            val ty = p.type ?: Any::class.java
            val pv = m.javaClass.getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, ty, p.id, area)
                ?: return "ERR: null"
            val value = pv.javaClass.getMethod("getValue").invoke(pv)
            val status = try { pv.javaClass.getMethod("getStatus").invoke(pv) } catch (_: Throwable) { "?" }
            show(value) + "|" + status
        } catch (e: Throwable) { val r = root(e); "ERR: ${r.javaClass.simpleName}: ${(r.message ?: "").take(120)}" }
    }
}
