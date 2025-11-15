package com.outcastgeek.ubntth.service

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.camel.ProducerTemplate

@ApplicationScoped
class ChatService {

    @Inject
    lateinit var producerTemplate: ProducerTemplate

    /**
     * Send a chat message to Gemini and get a complete response (non-streaming)
     * Uses the Camel route "direct:chat"
     * @param message The question or message to send
     * @return The complete response from Gemini
     */
    fun chat(message: String): String {
        return producerTemplate.requestBody("direct:chat", message, String::class.java)
            ?: "No response from Gemini"
    }

//    /**
//     * Send a chat message asynchronously (fire and forget)
//     * @param message The question or message to send
//     */
//    fun chatAsync(message: String) {
//        producerTemplate.asyncSendBody("direct:chat", message)
//    }

    /**
     * Stream a chat response from Gemini via the Camel streaming route
     *
     * This calls the "direct:chat-stream" Camel route which:
     * 1. Receives the question
     * 2. Calls Gemini's streaming API
     * 3. Returns a Multi<String> stream of tokens as they arrive from Gemini
     *
     * The tokens are streamed in REAL-TIME directly from Gemini through the Camel route
     *
     * @param message The question or message to send
     * @return A Multi (reactive stream) of response tokens from the Camel route
     */
    fun chatStream(message: String): Multi<String> {
        // Call the Camel streaming route and get the Multi<String> stream back
        val stream = producerTemplate.requestBody("direct:chat-stream", message, Multi::class.java)

        @Suppress("UNCHECKED_CAST")
        return stream as Multi<String>
    }
}
