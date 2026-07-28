package com.bydmate.app.service

object DiPlusWatchdog {
    fun shouldRelaunch(
        failuresCount: Int,
        threshold: Int,
        nowMs: Long,
        lastRelaunchTs: Long,
        cooldownMs: Long,
    ): Boolean {
        if (failuresCount < threshold) return false
        return lastRelaunchTs == 0L || nowMs - lastRelaunchTs >= cooldownMs
    }
}
