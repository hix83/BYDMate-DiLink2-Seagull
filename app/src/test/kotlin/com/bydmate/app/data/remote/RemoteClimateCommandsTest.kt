package com.bydmate.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteClimateCommandsTest {
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
        assertEquals(listOf("自动空调", "设置温度25", "设置风量7", "主驾座椅加热2档", "副驾座椅加热关闭"), commands)
    }
    @Test fun disabledClimateDoesNotWriteTemperatureOrFan() {
        val commands = RemoteClimateCommands.commands(RemoteClimateCommands.Climate(false, 22, 3, 0, 1)).map { it.second }
        assertEquals(listOf("关闭空调", "主驾座椅加热关闭", "副驾座椅加热1档"), commands)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidValueCannotReachHardware() {
        RemoteClimateCommands.commands(RemoteClimateCommands.Climate(true, 50, 3, 0, 0))
    }
}
