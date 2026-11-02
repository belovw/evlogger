package com.evlogger.hu

/**
 * Числовые ID свойств службы производителя (mega.car.hardware.property.ICarProperty),
 * найденные в декомпилированных константах классов mega.car.config.ElecPower / Ecu / Climate
 * внутри /system/priv-app/BigsurCarService/BigsurCarService.apk (pid com.mega.car).
 * Это НЕ стандартный android.car.VehiclePropertyIds — тот отвечает тестовыми данными
 * ("Toy Vehicle"), настоящие значения идут только через эти ID.
 * area почти везде 0 (глобально, не по зонам) — у этого производителя зональность
 * обычно зашита прямо в имя/ID (например отдельные ID для левой/правой температуры).
 */
object CarIds {
    data class P(val name: String, val id: Int, val area: Int = 0, val note: String = "")

    val ALL = listOf(
        // --- ДВС / топливо (mega.car.config.ElecPower, Ecu) ---
        P("ENGINE_STATUS", 83886146, note = "0=STOP 1=CRANK 2=RUNNING 3=RESERVED"),
        P("FUEL_REMAIN_RANGE", 83886131, note = "запас хода на бензине"),
        P("FUEL_PERCENT", 83886130),
        P("FUEL_L", 83886166, note = "литры в баке"),
        P("FUEL_LEVEL", 83886170),
        P("CONFIG_FUEL_TANK_CAPACITY", 856686926, note = "паспортный объём бака"),
        P("CONFIG_ENGINE_TYPE", 856687024),
        P("CONFIG_ICE_MODE", 856686645),
        P("REMOTE_START_REQ", 83886149),

        // --- Батарея / зарядка ---
        P("BATTERY_SOC", 83886148),
        P("BATTERY_SOC_STATUS", 83886147),
        P("DRV_ENERGY_HOLD_SOC", 83886104, note = "порог удержания SOC водителем"),
        P("EP_HV_BATT_REMAIN_RANGE", 83886110, note = "запас хода на электричестве"),
        P("EP_HV_BATT_VOL", 83886081),
        P("EP_HV_BATT_CURRENT", 83886082),
        P("EP_HV_BATT_CHG_STATUS", 83886085),
        P("EP_MOTOR_SPEED", 83886140, note = "обороты тягового мотора"),
        P("CONFIG_BATTERY_CAPACITY", 856687288, note = "паспортная ёмкость батареи"),
        P("CHARGE_RANGE_PLUS_KWH", 83886089),
        P("ELEC_POWER_CHG_RANGE_PLUS_MILEAGE", 83886090),
        P("ELEC_POWER_CHG_POWER", 83886093),
        P("ELEC_POWER_CHG_CURRENT", 83886092),
        P("ELEC_POWER_CHG_VOLTAGE", 83886091),
        P("ELEC_POWER_CHG_TYPE", 83886088),
        P("REMAINING_MILEAGE_REAL", 83886164, note = "общий запас хода (реальный)"),
        P("REMAINING_MILEAGE_STANDARD", 83886165, note = "общий запас хода (паспортный)"),
        P("AVERAGE_ENERGY_CONSUME", 83886155),
        P("MOMENT_ENERGY_CONSUME_10KM", 83886159),
        P("SYS_EGY_FLOW_DIRECTION", 83886127, note = "откуда куда идёт энергия"),
        P("VEH_EGY_CNSE_SELECT", 83886126, note = "возможно режим EV/эконом/стандарт"),
        P("ENERGY_SOURCE", 83886129),
        P("ENG_DIST_SW", 83886125),
        P("POWER_BATTERY_WORK_MODE", 83886167),
        P("SMART_ENERGY_MANAGER", 83886154),
        P("BATTERY_HEAT_ACTIVE_STATUS", 83886168),
        P("BATTERY_TEMPERATURE_MIN", 83886169),
        P("BATT_HEATING_STATUS", 83886139),

        // --- Климат (mega.car.config.Climate) ---
        P("TEMPERATURE", 33554442),
        P("TEMPERATURE_FRONTLEFT", 33554551, note = "заданная темп. слева спереди"),
        P("TEMPERATURE_FRONTRIGHT", 33554552),
        P("TEMPERATURE_REAR", 33554555),
        P("AC_RUN_REQ", 33554498, note = "запрос компрессора A/C"),
        P("AC_FAST_HEAT", 33554521, note = "быстрый прогрев"),
        P("AC_SYSTEM_ERROR", 33554520),
        P("FAN_SPEED_MODE_ON_AUTO", 33554470),
        P("TEMPERATURE_UNIT", 33554495),
    )
}
