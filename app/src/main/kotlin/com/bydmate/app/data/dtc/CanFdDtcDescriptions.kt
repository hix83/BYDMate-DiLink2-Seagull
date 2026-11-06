package com.bydmate.app.data.dtc

/** Reference wording from bundled BYD tables, scoped to the inspected firmware and observed addresses/codes. */
object CanFdDtcDescriptions {
    fun lookup(found: DtcFound, supportedFirmware: Boolean): DtcDescription? {
        if (!supportedFirmware) return null
        return when (found.addr to found.row.code) {
            0x741 to "B1AB7-00" -> DtcDescription(
                "Network registration fault", "网络注册故障", DtcDescLevel.SIMILAR,
                "BYD texts.tsv:14172", "Ошибка регистрации в сети",
            )
            0x741 to "U0247-87" -> DtcDescription(
                "Communication fault with TCP server", "与TCP服务器之间通讯故障", DtcDescLevel.SIMILAR,
                "BYD texts.tsv:5067,5068", "Ошибка связи с TCP-сервером",
            )
            0x720 to "B2A4B-92" -> DtcDescription(
                "Recirculation motor not turning into position", "内外循环电机转不到位", DtcDescLevel.SIMILAR,
                "BYD texts.tsv:6768,11960", "Привод рециркуляции не достигает заданного положения",
            )
            else -> null
        }
    }
}
