package com.bydmate.app.data.remote

/** The gateway can select values, never supply executable command strings. */
object RemoteClimateCommands {
    fun canExecute(speed: Int?, gear: Int?, sampleAgeMs: Long, connected: Boolean): Boolean =
        connected && speed == 0 && gear == 1 && sampleAgeMs in 0..30_000

    data class Climate(val enabled: Boolean, val temperature: Int, val fan: Int, val driverHeat: Int, val passengerHeat: Int)

    fun commands(c: Climate): List<Pair<String, String>> {
        require(c.temperature in 16..33 && c.fan in 1..7 && c.driverHeat in 0..3 && c.passengerHeat in 0..3)
        fun seat(prefix: String, level: Int) = if (level == 0) "${prefix}座椅加热关闭" else "${prefix}座椅加热${level}档"
        return buildList {
            if (c.enabled) {
                add("Климат включён" to "自动空调")
                add("Температура ${c.temperature} °C" to "设置温度${c.temperature}")
                add("Обдув ${c.fan}" to "设置风量${c.fan}")
            } else add("Климат выключен" to "关闭空调")
            add("Подогрев водителя ${c.driverHeat}" to seat("主驾", c.driverHeat))
            add("Подогрев пассажира ${c.passengerHeat}" to seat("副驾", c.passengerHeat))
        }
    }
}
