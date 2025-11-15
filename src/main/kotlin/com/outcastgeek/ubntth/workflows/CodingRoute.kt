package com.outcastgeek.ubntth.workflows

import org.apache.camel.builder.RouteBuilder

class CodingRoute : RouteBuilder() {

    override fun configure() {

        // Direct route for STREAMING chat - returns a reactive Multi<String>
        // This is the route that ChatService will call to get a stream of tokens
        from("direct:coding-chat-stream")
            .log("Received STREAMING chat request: \${body}")
            .bean("codeExplainer", "explainCode")
    }
}

