package com.outcastgeek.ubntth.workflows.utils

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import io.smallrye.mutiny.Multi
import org.slf4j.Logger

/**
 * Creates a reactive Multi stream that emits tokens from Gemini in real-time
 */
fun String.createTokenStream(log: Logger, streamingChatModel: StreamingChatModel): Multi<String> {
    return Multi.createFrom().emitter { emitter ->
        try {
            // Create chat request with user message
            val userMessage: ChatMessage = UserMessage.from(this)
            val chatRequest = ChatRequest.builder()
                .messages(listOf(userMessage))
                .build()

            // Call Gemini streaming API - tokens will arrive in real-time
            streamingChatModel.chat(chatRequest, object : StreamingChatResponseHandler {
                override fun onPartialResponse(partialResponse: String) {
                    // Each token from Gemini arrives here in real-time
                    log.debug("Token received: $partialResponse")
                    emitter.emit(partialResponse)
                }

                override fun onCompleteResponse(response: ChatResponse) {
                    // Stream is complete
                    log.info("Streaming complete for this: $this")
                    emitter.complete()
                }

                override fun onError(error: Throwable) {
                    // Handle errors during streaming
                    log.error("Error during streaming: ${error.message}", error)
                    emitter.fail(error)
                }
            })
        } catch (e: Exception) {
            log.error("Failed to create stream: ${e.message}", e)
            emitter.fail(e)
        }
    }
}
