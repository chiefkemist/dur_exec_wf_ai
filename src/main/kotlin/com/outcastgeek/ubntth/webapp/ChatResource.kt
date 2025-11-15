package com.outcastgeek.ubntth.webapp

import com.outcastgeek.ubntth.service.ChatService
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.RestStreamElementType

@Path("/api/chat")
class ChatResource {

    @Inject
    lateinit var chatService: ChatService

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    fun chat(question: String): String {
        return chatService.chat(question)
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    fun chatGet(@QueryParam("q") question: String?): String {
        if (question.isNullOrBlank()) {
            return "Please provide a question using the 'q' query parameter"
        }
        return chatService.chat(question)
    }

    @GET
    @Path("/test")
    @Produces(MediaType.TEXT_PLAIN)
    fun testChat(): String {
        val question = "What are the benefits of Apache Camel?"
        return chatService.chat(question)
    }

    // SSE Streaming endpoints

    @POST
    @Path("/stream")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    fun chatStream(question: String): Multi<String> {
        return chatService.chatStream(question)
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    fun chatStreamGet(@QueryParam("q") question: String?): Multi<String> {
        if (question.isNullOrBlank()) {
            return Multi.createFrom().item("Please provide a question using the 'q' query parameter")
        }
        return chatService.chatStream(question)
    }

    @GET
    @Path("/stream/test")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    fun testChatStream(): Multi<String> {
        val question = "What are the benefits of Apache Camel? Please provide a detailed explanation."
        return chatService.chatStream(question)
    }
}
