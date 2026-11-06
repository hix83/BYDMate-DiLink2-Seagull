package com.bydmate.app.helper.dtc

import android.content.Context
import android.content.ContextWrapper
import java.lang.reflect.Method

/** Missing properties are unknown, never a fabricated idle value. */
internal fun readDtcSystemProperty(key: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, key) as? String
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

internal const val DTC_NOT_IN_FEATURE_MAP = "not_in_feature_map"

internal fun dtcDeviceInstance(className: String): Any {
    val base = DtcDaemon.context ?: error("daemon has no system Context")
    // As upstream: the shell UID has no app package for this local permission check.
    // The actual authority remains autoservice's own enableDevice permission check.
    val context = object : ContextWrapper(base) {
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) = Unit
    }
    return Class.forName(className).getMethod("getInstance", Context::class.java).invoke(null, context)
        ?: error("getInstance returned null")
}

internal fun findDtcListenerMethod(target: Any, listener: Any, name: String, withFeatureIds: Boolean): Method =
    target.javaClass.methods.firstOrNull {
        val p = it.parameterTypes
        it.name == name && p.size == (if (withFeatureIds) 2 else 1) &&
            p[0].isAssignableFrom(listener.javaClass) && (!withFeatureIds || p[1] == IntArray::class.java)
    } ?: throw NoSuchMethodException(name)

internal fun confirmDtcRegistration(device: Any, fid: Int): String {
    var type: Class<*>? = device.javaClass
    while (type != null) {
        val field = runCatching { type?.getDeclaredField("mIBYDAutoListenerMap") }.getOrNull()
        if (field != null) return runCatching {
            field.isAccessible = true
            val map = field.get(device) ?: return@runCatching "sent"
            val method = map.javaClass.getMethod("containsId", Int::class.javaPrimitiveType)
            method.isAccessible = true
            if (method.invoke(map, fid) == false) DTC_NOT_IN_FEATURE_MAP else "ok"
        }.getOrDefault("sent")
        type = type.superclass
    }
    return "sent"
}
