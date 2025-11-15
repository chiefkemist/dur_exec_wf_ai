package com.outcastgeek.ubntth.webapp

import com.outcastgeek.ubntth.service.CodeExplanation
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.apache.camel.ProducerTemplate

@Path("/api/code-explanation")
class CodeExplanationResource {

    @Inject
    lateinit var producerTemplate: ProducerTemplate

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    fun explainCode(code: String): CodeExplanation {
        return producerTemplate.requestBody("direct:coding-chat-stream", code, CodeExplanation::class.java)
            ?: CodeExplanation("", "No response from AI")
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun explainCodeGet(@QueryParam("code") code: String?): CodeExplanation {
        if (code.isNullOrBlank()) {
            return CodeExplanation("", "Please provide code using the 'code' query parameter")
        }
        return producerTemplate.requestBody("direct:coding-chat-stream", code, CodeExplanation::class.java)
            ?: CodeExplanation("", "No response from AI")
    }

    @GET
    @Path("/test")
    @Produces(MediaType.APPLICATION_JSON)
    fun testExplainCode(): CodeExplanation {
        val sampleCode = """
            fun fibonacci(n: Int): Int {
                return if (n <= 1) n else fibonacci(n - 1) + fibonacci(n - 2)
            }
        """.trimIndent()
        return producerTemplate.requestBody("direct:coding-chat-stream", sampleCode, CodeExplanation::class.java)
            ?: CodeExplanation("", "No response from AI")
    }
}
