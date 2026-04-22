package com.aiassistant.di

import com.aiassistant.data.repository.ChatRepository
import com.aiassistant.data.repository.ConversationRepositoryImpl
import com.aiassistant.data.repository.MessageRepositoryImpl
import com.aiassistant.data.repository.MemoryRepositoryImpl
import com.aiassistant.data.repository.TaskRepositoryImpl
import com.aiassistant.domain.repository.ChatApiRepository
import com.aiassistant.domain.repository.ChatApiRepositoryImpl
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.repository.MessageRepository
import com.aiassistant.domain.repository.MemoryRepository
import com.aiassistant.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingModule {

    @Binds
    @Singleton
    abstract fun bindChatApiRepository(impl: ChatApiRepositoryImpl): ChatApiRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository
}
