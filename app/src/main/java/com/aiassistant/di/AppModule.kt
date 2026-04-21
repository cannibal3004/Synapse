package com.aiassistant.di

import android.content.Context
import androidx.room.Room
import com.aiassistant.data.api.OpenAIService
import com.aiassistant.data.api.RetrofitClient
import com.aiassistant.data.database.AppDatabase
import com.aiassistant.data.repository.*
import com.aiassistant.domain.repository.*
import com.aiassistant.domain.service.VectorMathService
import com.aiassistant.domain.tool.CalculatorTool
import com.aiassistant.domain.tool.CodeInterpreterTool
import com.aiassistant.domain.tool.DeviceInfoTool
import com.aiassistant.domain.tool.WebPageFetcherTool
import com.aiassistant.domain.tool.WeatherTool
import com.aiassistant.domain.tool.WebSearchTool
import com.aiassistant.domain.usecase.MemorySearchUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOpenAIService(): OpenAIService {
        return RetrofitClient.openAIService
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ai_assistant_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideConversationDao(database: AppDatabase) = database.conversationDao()

    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase) = database.messageDao()

    @Provides
    @Singleton
    fun provideMemoryDao(database: AppDatabase) = database.memoryDao()

    @Provides
    @Singleton
    fun provideChatRepository(service: OpenAIService) = ChatRepository(service)

    @Provides
    @Singleton
    fun provideConversationRepository(dao: com.aiassistant.data.database.ConversationDao) =
        ConversationRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideMessageRepository(dao: com.aiassistant.data.database.MessageDao) =
        MessageRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideMemoryRepository(dao: com.aiassistant.data.database.MemoryDao) =
        MemoryRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideMemorySearchUseCase(repository: MemoryRepository) =
        MemorySearchUseCase(repository)

    @Provides
    @Singleton
    fun provideWebSearchTool(@ApplicationContext context: Context) = WebSearchTool(context)

    @Provides
    @Singleton
    fun provideCalculatorTool() = CalculatorTool()

    @Provides
    @Singleton
    fun provideWeatherTool() = WeatherTool()

    @Provides
    @Singleton
    fun provideWebPageFetcherTool() = WebPageFetcherTool()

    @Provides
    @Singleton
    fun provideCodeInterpreterTool() = CodeInterpreterTool()

    @Provides
    @Singleton
    fun provideDeviceInfoTool(@ApplicationContext context: android.content.Context) =
        DeviceInfoTool(context)

    @Provides
    @Singleton
    fun provideVectorMathService() = VectorMathService()

    @Provides
    @Singleton
    fun provideSettingsDataRepository(@ApplicationContext context: Context) =
        SettingsDataRepository(context)
}
