package com.outcastgeek.ubntth.workflows

import com.outcastgeek.ubntth.workflows.utils.createTokenStream
import dev.langchain4j.model.chat.StreamingChatModel
import org.apache.camel.builder.RouteBuilder

class ChatRoute : RouteBuilder() {

    override fun configure() {
//        // Simple chat route using Gemini via LangChain4j
//        // Triggered every 30 seconds with a sample question
//        from("timer:chatTimer?period=30000")
//            .setBody(constant("What is Apache Camel?"))
//            .log("Sending question to Gemini: \${body}")
//            .to("langchain4j-chat:geminiChat?chatModel=#geminiChatModel")
//            .log("Gemini response: \${body}")

        // Direct route for on-demand chat (synchronous, complete response)
        // Can be triggered via: producerTemplate.requestBody("direct:chat", "your question")
        from("direct:chat")
            .log("Received chat request: \${body}")
            .to("langchain4j-chat:geminiChat?chatModel=#geminiChatModel")
            .log("Chat response: \${body}")

        // Direct route for STREAMING chat - returns a reactive Multi<String>
        // This is the route that ChatService will call to get a stream of tokens
        from("direct:chat-stream")
            .log("Received STREAMING chat request: \${body}")
            .process { exchange ->
                val question = exchange.`in`.getBody(String::class.java)

                // Get the streaming chat model from the Camel registry
                val streamingChatModel = exchange.context.registry
                    .lookupByNameAndType("geminiStreamingChatModel", StreamingChatModel::class.java)
                    ?: throw IllegalStateException("geminiStreamingChatModel bean not found in registry")

                // Create the Multi stream that will emit tokens from Gemini
                val tokenStream = question.createTokenStream(log, streamingChatModel)

                // Set the Multi<String> as the body - this will be returned to the caller
                exchange.message.body = tokenStream

                log.info("Created streaming response for question: $question")
            }
    }
}

