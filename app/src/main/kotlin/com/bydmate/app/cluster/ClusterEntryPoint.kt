package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.hud.HudController
import com.bydmate.app.voice.VoiceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bridges app singletons into framework/UI callers that are not constructor-injected.
 * SteeringWheelKeyService must not request [VoiceController] because it runs in :steering;
 * main-process callers such as WidgetController may use it directly.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClusterEntryPoint {
    fun helperClient(): HelperClient
    fun helperBootstrap(): HelperBootstrap
    fun voiceController(): VoiceController
    fun hudController(): HudController
}
