package com.outcastgeek.ubntth.config

import dev.langchain4j.model.chat.ChatModel
import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiChatLanguageModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Named
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class GeminiChatModelProducer {

    @Produces
    @ApplicationScoped
    @Named("geminiChatModel")
    fun createGeminiChatModel(
        @ConfigProperty(name = "gemini.api.key") apiKey: String,
        @ConfigProperty(name = "gemini.model.name") modelName: String,
        @ConfigProperty(name = "gemini.model.temperature") modelTemperature: Double,
    ): ChatModel {
        return AiGeminiChatLanguageModel.builder()
            .key(apiKey)
            .modelId(modelName)
            .temperature(modelTemperature)
            .build()
    }
}
