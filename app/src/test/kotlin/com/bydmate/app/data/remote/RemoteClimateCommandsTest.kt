package com.bydmate.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteClimateCommandsTest {
    @Test fun separateCheckboxesProduceExplicitOnAndOffCommands() {
        val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(true, 25, 5, 0, 0,
            compressor = false, recirculation = true, frontDefrost = true, mirrorHeat = false)).map { it.second }
        assertEquals(listOf(RemoteClimateCommands.POWER_ON, "吹前挡", "内循环", RemoteClimateCommands.COMPRESSOR_OFF,
            "设置温度25", "设置风量5", "关闭后视镜加热"), commands)
        for (command in commands) assertFalse(RemoteClimateCommands.requiresPark(command))
    }
    @Test fun climateOffSkipsCompressorButMirrorHeatRemainsIndependent() {
        val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(false, 22, 3, 0, 0,
            compressor = true, recirculation = true, frontDefrost = true, mirrorHeat = true)).map { it.second }
        assertEquals(listOf(RemoteClimateCommands.POWER_OFF, "后视镜加热"), commands)
    }
    @Test fun temperatureAndFanDoNotRequireParkButRequireFreshConnection() {
        assertFalse(RemoteClimateCommands.requiresPark("设置温度24"))
        assertFalse(RemoteClimateCommands.requiresPark("设置风量3"))
        assertTrue(RemoteClimateCommands.canExecute(50, 4, 5000, true, requiresPark = false))
        assertTrue(RemoteClimateCommands.canExecute(0, null, 5000, true, requiresPark = false))
        assertFalse(RemoteClimateCommands.canExecute(50, 4, 30_001, true, requiresPark = false))
        assertFalse(RemoteClimateCommands.canExecute(50, 4, -1, true, requiresPark = false))
        assertFalse(RemoteClimateCommands.canExecute(50, 4, 5000, false, requiresPark = false))
        for (command in listOf("自动空调", "关闭空调", "主驾座椅加热2档", "设置温度50", "设置风量8")) {
            assertTrue(RemoteClimateCommands.requiresPark(command))
        }
    }
    @Test fun requiresFreshConnectedStationaryParkState() {
        assertTrue(RemoteClimateCommands.canExecute(0, 1, 5000, true))
        assertFalse(RemoteClimateCommands.canExecute(null, 1, 5000, true))
        assertFalse(RemoteClimateCommands.canExecute(0, null, 5000, true))
        assertFalse(RemoteClimateCommands.canExecute(1, 1, 5000, true))
        assertFalse(RemoteClimateCommands.canExecute(0, 4, 5000, true))
        assertFalse(RemoteClimateCommands.canExecute(0, 1, 30_001, true))
        assertFalse(RemoteClimateCommands.canExecute(0, 1, 5000, false))
        assertFalse(RemoteClimateCommands.canExecute(0, 1, -1, true))
    }
    @Test fun allowedClimateIsTranslatedLocally() {
        val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(true, 25, 7, 2, 0)).map { it.second }
        assertEquals(listOf(RemoteClimateCommands.POWER_ON, "设置温度25", "设置风量7"), commands)
    }
    @Test fun disabledClimateDoesNotWriteTemperatureOrFan() {
        val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(false, 22, 3, 0, 1)).map { it.second }
        assertEquals(listOf(RemoteClimateCommands.POWER_OFF), commands)
    }
    @Test fun heatingNeverUsesVentilationOnlyOrForcesCompressorOn() {
        val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(true, 33, 4, 0, 0,
            compressor = false, frontDefrost = false)).map { it.second }
        assertEquals(RemoteClimateCommands.POWER_ON, commands.first())
        assertFalse(commands.contains("打开空调通风"))
        assertFalse(commands.contains("自动空调"))
        assertFalse(commands.contains(RemoteClimateCommands.COMPRESSOR_ON))
        assertTrue(commands.indexOf("关闭吹前挡") < commands.indexOf(RemoteClimateCommands.COMPRESSOR_OFF))
        assertFalse(RemoteClimateCommands.requiresPark(RemoteClimateCommands.POWER_ON))
        assertFalse(RemoteClimateCommands.requiresPark(RemoteClimateCommands.POWER_OFF))
        assertTrue(RemoteClimateCommands.requiresPark("车门解锁"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidValueCannotReachHardware() {
        RemoteClimateCommands.commands(RemoteClimateCommands.Climate(true, 50, 3, 0, 0))
    }
    @Test fun directionIsIndependentOfDefrostAndOptionalForOldSettings() {
        for (mode in 1..7) {
            val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(true, 33, 4, 0, 0,
                frontDefrost = false, windMode = mode)).map { it.second }
            assertTrue(commands.indexOf("关闭吹前挡") < commands.indexOf("bydmate:wind:$mode"))
            assertFalse(RemoteClimateCommands.requiresPark("bydmate:wind:$mode"))
        }
        for (climate in listOf(RemoteClimateCommands.Climate(true, 33, 4, 0, 0),
            RemoteClimateCommands.Climate(false, 33, 4, 0, 0, windMode = 5),
            RemoteClimateCommands.Climate(true, 33, 4, 0, 0, frontDefrost = true, windMode = 5))) {
            assertFalse(RemoteClimateCommands.commands(climate).any { it.second.startsWith("bydmate:wind:") })
        }
    }
    @Test(expected = IllegalArgumentException::class) fun invalidDirectionIsRejected() {
        RemoteClimateCommands.commands(RemoteClimateCommands.Climate(true, 33, 4, 0, 0, windMode = 8))
    }
}
