package com.bydmate.app.di

import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.platform.PlatformParsReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NativeModule {

    @Binds
    @Singleton
    abstract fun bindParsReader(impl: PlatformParsReader): ParsReader
}
