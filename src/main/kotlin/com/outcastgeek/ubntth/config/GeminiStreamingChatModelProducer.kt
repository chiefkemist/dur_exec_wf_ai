package com.outcastgeek.ubntth.config

import dev.langchain4j.model.chat.StreamingChatModel
import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiStreamingChatLanguageModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Named
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class GeminiStreamingChatModelProducer {

    @Produces
    @ApplicationScoped
    @Named("geminiStreamingChatModel")
    fun createGeminiStreamingChatModel(
        @ConfigProperty(name = "gemini.api.key") apiKey: String,
        @ConfigProperty(name = "gemini.model.name") modelName: String,
        @ConfigProperty(name = "gemini.model.temperature") modelTemperature: Double,
    ): StreamingChatModel {
        return AiGeminiStreamingChatLanguageModel.builder()
            .key(apiKey)
            .modelId(modelName)
            .temperature(modelTemperature)
            .build()
    }
}
