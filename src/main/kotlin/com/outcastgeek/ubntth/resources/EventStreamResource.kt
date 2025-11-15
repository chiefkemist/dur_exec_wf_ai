package com.outcastgeek.ubntth.resources

import com.outcastgeek.ubntth.services.EventBroadcaster
import io.quarkus.logging.Log
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.Sse
import jakarta.ws.rs.sse.SseEventSink
import java.util.*

/**
 * REST API for Server-Sent Events (SSE) stream.
 *
 * This endpoint provides real-time updates to frontend clients about:
 * - Exchange state changes (created, started, paused, resumed, completed, failed)
 * - Checkpoints
 * - Approval requests
 * - Route events
 * - Camel events
 *
 * Frontend clients connect to this endpoint using EventSource API.
 */
@Path("/api/events")
class EventStreamResource {

    @Inject
    lateinit var broadcaster: EventBroadcaster

    /**
     * SSE endpoint for real-time event streaming
     *
     * Usage from frontend:
     * ```javascript
     * const eventSource = new EventSource('http://localhost:8080/api/events/stream');
     * eventSource.onmessage = (event) => {
     *   const data = JSON.parse(event.data);
     *   console.log('Received event:', data);
     * };
     * ```
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun stream(@Context sse: Sse, @Context sseEventSink: SseEventSink) {
        // Generate unique client ID
        val clientId = UUID.randomUUID().toString()

        // Initialize SSE in the broadcaster (if not already done)
        broadcaster.setSse(sse)

        // Register this client
        broadcaster.register(clientId, sseEventSink)

        Log.info("📡 SSE client connected: $clientId")

        // Send initial connection confirmation
        try {
            val welcomeEvent = sse.newEventBuilder()
                .name("connected")
                .data("""{"message":"Connected to event stream","clientId":"$clientId"}""")
                .build()

            sseEventSink.send(welcomeEvent)
        } catch (e: Exception) {
            Log.warn("Failed to send welcome event to client $clientId", e)
            broadcaster.unregister(clientId)
        }

        // Note: SSE client disconnection is automatically handled by the broadcaster
        // when send() fails. The broadcaster removes dead clients automatically.
    }

    /**
     * Get the current number of connected SSE clients
     */
    @GET
    @Path("/clients/count")
    @Produces(MediaType.APPLICATION_JSON)
    fun getClientCount(): Map<String, Int> {
        return mapOf("count" to broadcaster.getClientCount())
    }

    /**
     * Health check endpoint for SSE
     */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    fun health(): Map<String, Any> {
        return mapOf(
            "status" to "ok",
            "connectedClients" to broadcaster.getClientCount()
        )
    }
}
