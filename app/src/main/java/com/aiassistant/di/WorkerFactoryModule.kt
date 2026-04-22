package com.aiassistant.di

import com.aiassistant.data.llm.OnDeviceLlmSettingsManager
import com.aiassistant.data.notification.NotificationHelper
import com.aiassistant.domain.repository.MessageRepository
import com.aiassistant.data.repository.SettingsDataRepository
import com.aiassistant.data.worker.WorkerFactory
import com.aiassistant.domain.repository.OnDeviceLlmRepository
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.usecase.TaskExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object WorkerFactoryModule {

    @Provides
    @javax.inject.Singleton
    fun provideWorkerFactory(
        taskExecutor: TaskExecutor,
        taskRepository: TaskRepository,
        settingsDataRepository: SettingsDataRepository,
        notificationHelper: NotificationHelper,
        onDeviceLlmRepository: OnDeviceLlmRepository,
        onDeviceLlmSettingsManager: OnDeviceLlmSettingsManager,
        messageRepository: MessageRepository
    ): WorkerFactory {
        return WorkerFactory(
            taskExecutor,
            taskRepository,
            settingsDataRepository,
            notificationHelper,
            onDeviceLlmRepository,
            onDeviceLlmSettingsManager,
            messageRepository
        )
    }
}
