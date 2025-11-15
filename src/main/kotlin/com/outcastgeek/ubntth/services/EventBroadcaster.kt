package com.outcastgeek.ubntth.services

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.sse.Sse
import jakarta.ws.rs.sse.SseEventSink
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages Server-Sent Events (SSE) connections and broadcasts route events
 * to all connected frontend clients in real-time.
 *
 * Features:
 * - Queues events during startup when SSE is not yet initialized
 * - Automatically replays queued events when first client connects
 * - Thread-safe event broadcasting
 */
@ApplicationScoped
class EventBroadcaster {

    private val clients = ConcurrentHashMap<String, SseEventSink>()
    private lateinit var sse: Sse

    // Queue for events that occur before SSE is initialized
    private val eventQueue = ConcurrentLinkedQueue<RouteEvent>()
    private val MAX_QUEUE_SIZE = 1000 // Prevent unbounded growth
    private var sseInitialized = false

    /**
     * Initialize SSE (called from EventStreamResource)
     */
    fun setSse(sse: Sse) {
        this.sse = sse
        sseInitialized = true
        Log.info("📡 SSE broadcaster initialized")
    }

    /**
     * Register a new SSE client
     */
    fun register(clientId: String, sink: SseEventSink) {
        clients[clientId] = sink
        Log.info("📡 SSE client registered: $clientId (total: ${clients.size})")

        // Replay queued events to the new client
        if (eventQueue.isNotEmpty()) {
            replayQueuedEvents()
        }
    }

    /**
     * Unregister an SSE client
     */
    fun unregister(clientId: String) {
        clients.remove(clientId)?.close()
        Log.info("📡 SSE client unregistered: $clientId (remaining: ${clients.size})")
    }

    /**
     * Replay all queued events to connected clients
     */
    private fun replayQueuedEvents() {
        if (!sseInitialized || clients.isEmpty()) return

        val queueSize = eventQueue.size
        if (queueSize == 0) return

        Log.info("📡 Replaying $queueSize queued events to ${clients.size} clients")

        var replayedCount = 0
        while (eventQueue.isNotEmpty()) {
            val event = eventQueue.poll() ?: break
            broadcastInternal(event)
            replayedCount++
        }

        Log.info("📡 Replayed $replayedCount events")
    }

    /**
     * Broadcast an event to all connected clients.
     * If SSE is not initialized, queue the event for later replay.
     */
    fun broadcast(event: RouteEvent) {
        if (!sseInitialized) {
            // Queue the event for later
            if (eventQueue.size < MAX_QUEUE_SIZE) {
                eventQueue.offer(event)
                Log.debug("📥 Queued event ${event.type} (queue size: ${eventQueue.size})")
            } else {
                Log.warn("⚠️ Event queue full, dropping event: ${event.type}")
            }
            return
        }

        broadcastInternal(event)
    }

    /**
     * Internal broadcast method that assumes SSE is initialized
     */
    private fun broadcastInternal(event: RouteEvent) {
        if (!::sse.isInitialized) {
            Log.warn("SSE not initialized in broadcastInternal, this shouldn't happen")
            return
        }

        val json = Json.encodeToString(event)
        val sseEvent = sse.newEventBuilder()
            .name(event.type)
            .data(json)
            .build()

        clients.values.removeIf { sink ->
            try {
                sink.send(sseEvent)
                false // Keep in list
            } catch (e: Exception) {
                Log.warn("Failed to send SSE to client: ${e.message}")
                true // Remove from list
            }
        }

        Log.debug("📨 Broadcasted event ${event.type} to ${clients.size} clients")
    }

    /**
     * Get count of connected clients
     */
    fun getClientCount(): Int = clients.size

    /**
     * Get count of queued events
     */
    fun getQueuedEventCount(): Int = eventQueue.size

    /**
     * Check if SSE is initialized
     */
    fun isInitialized(): Boolean = sseInitialized
}

/**
 * Event data structure for route events
 */
@Serializable
data class RouteEvent(
    val type: String,
    val routeId: String,
    val exchangeId: String? = null,
    val data: Map<String, String> = emptyMap()
)
