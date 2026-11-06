package com.bydmate.app.data.remote

/** The gateway can select values, never supply executable command strings. */
object RemoteClimateCommands {
    fun canExecute(speed: Int?, gear: Int?, sampleAgeMs: Long, connected: Boolean, requiresPark: Boolean = true): Boolean =
        connected && sampleAgeMs in 0..30_000 && (!requiresPark || (speed == 0 && gear == 1))

    // The owner explicitly permits cabin climate without P. Keep this narrow:
    // unknown strings/other vehicle controls still require stationary P.
    fun requiresPark(command: String): Boolean =
        !(command in setOf(POWER_ON, POWER_OFF, COMPRESSOR_ON, COMPRESSOR_OFF,
            "吹前挡", "关闭吹前挡", "内循环", "外循环", "后视镜加热", "关闭后视镜加热") ||
            Regex("设置温度(1[6-9]|2[0-9]|3[0-3])").matches(command) || Regex("设置风量[1-7]").matches(command) ||
            Regex("bydmate:wind:[1-7]").matches(command))

    const val COMPRESSOR_ON = "bydmate:compressor:on"
    const val COMPRESSOR_OFF = "bydmate:compressor:off"
    const val POWER_ON = "bydmate:climate:power:on"
    const val POWER_OFF = "bydmate:climate:power:off"
    val WIND_MODES = mapOf(1 to "На лицо", 2 to "На лицо и в ноги", 3 to "В ноги",
        4 to "В ноги и на стекло", 5 to "На стекло", 6 to "На лицо, в ноги и на стекло", 7 to "На лицо и на стекло")
    data class Climate(val enabled: Boolean, val temperature: Int, val fan: Int, val driverHeat: Int, val passengerHeat: Int,
                       val compressor: Boolean? = null, val recirculation: Boolean? = null,
                       val frontDefrost: Boolean? = null, val mirrorHeat: Boolean? = null, val windMode: Int? = null)

    fun commands(c: Climate): List<Pair<String, String>> {
        require(c.temperature in 16..33 && c.fan in 1..7)
        require(c.windMode == null || c.windMode in WIND_MODES)
        return buildList {
            if (c.enabled) {
                // Normal power must not enable ventilation-only or force A/C ON.
                add("Климат включён" to POWER_ON)
                c.frontDefrost?.let { add("Обдув лобового стекла ${if (it) "включён" else "выключен"}" to if (it) "吹前挡" else "关闭吹前挡") }
                // F is a complete demist program and takes priority over distribution.
                if (c.frontDefrost != true) c.windMode?.let {
                    add("Направление: ${WIND_MODES.getValue(it)}" to "bydmate:wind:$it")
                }
                c.recirculation?.let { add("Рециркуляция ${if (it) "включена" else "выключена"}" to if (it) "内循环" else "外循环") }
                c.compressor?.let { add("Кондиционер A/C ${if (it) "включён" else "выключен"}" to if (it) COMPRESSOR_ON else COMPRESSOR_OFF) }
                add("Температура ${c.temperature} °C" to "设置温度${c.temperature}")
                add("Обдув ${c.fan}" to "设置风量${c.fan}")
            } else add("Климат выключен" to POWER_OFF)
            c.mirrorHeat?.let { add("Обогрев зеркал и заднего стекла ${if (it) "включён" else "выключен"}" to if (it) "后视镜加热" else "关闭后视镜加热") }
        }
    }
}
