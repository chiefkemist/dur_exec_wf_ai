package com.outcastgeek.ubntth.camel

import com.outcastgeek.ubntth.services.EventBroadcaster
import com.outcastgeek.ubntth.services.RouteEvent
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.apache.camel.CamelContext
import org.apache.camel.spi.CamelEvent
import org.apache.camel.support.EventNotifierSupport

/**
 * Camel event notifier that broadcasts Camel events to connected SSE clients.
 *
 * This notifier captures significant Camel events and broadcasts them in real-time
 * to the frontend for monitoring and debugging purposes.
 *
 * Events captured:
 * - Exchange created
 * - Exchange completed
 * - Exchange failed
 * - Exchange sent to endpoint
 * - Route started/stopped
 */
@ApplicationScoped
class CamelEventNotifier : EventNotifierSupport() {

    @Inject
    lateinit var broadcaster: EventBroadcaster

    @Inject
    lateinit var context: CamelContext

    /**
     * Register this notifier with Camel on startup
     */
    fun onStartup(@Observes event: StartupEvent) {
        try {
            context.managementStrategy.addEventNotifier(this)
            Log.info("📡 CamelEventNotifier registered successfully")
        } catch (e: Exception) {
            Log.error("❌ Failed to register CamelEventNotifier", e)
        }
    }

    /**
     * Handle Camel events
     */
    override fun notify(event: CamelEvent) {
        try {
            when (event) {
                is CamelEvent.ExchangeCreatedEvent -> handleExchangeCreated(event)
                is CamelEvent.ExchangeCompletedEvent -> handleExchangeCompleted(event)
                is CamelEvent.ExchangeFailedEvent -> handleExchangeFailed(event)
                is CamelEvent.ExchangeSendingEvent -> handleExchangeSending(event)
                is CamelEvent.ExchangeSentEvent -> handleExchangeSent(event)
                is CamelEvent.RouteStartedEvent -> handleRouteStarted(event)
                is CamelEvent.RouteStoppedEvent -> handleRouteStopped(event)
                else -> {
                    // Ignore other events
                }
            }
        } catch (e: Exception) {
            Log.error("Error handling Camel event: ${event.javaClass.simpleName}", e)
        }
    }

    /**
     * Handle exchange created event
     */
    private fun handleExchangeCreated(event: CamelEvent.ExchangeCreatedEvent) {
        val exchange = event.exchange
        val exchangeId = exchange.getDurableExchangeId() ?: return
        val routeId = exchange.getDurableRouteId() ?: exchange.fromRouteId ?: "unknown"

        broadcaster.broadcast(RouteEvent(
            type = "CAMEL_EXCHANGE_CREATED",
            routeId = routeId,
            exchangeId = exchangeId
        ))

        Log.debug("📨 Camel exchange created: $exchangeId")
    }

    /**
     * Handle exchange completed event
     */
    private fun handleExchangeCompleted(event: CamelEvent.ExchangeCompletedEvent) {
        val exchange = event.exchange
        val exchangeId = exchange.getDurableExchangeId() ?: return
        val routeId = exchange.getDurableRouteId() ?: exchange.fromRouteId ?: "unknown"

        broadcaster.broadcast(RouteEvent(
            type = "CAMEL_EXCHANGE_COMPLETED",
            routeId = routeId,
            exchangeId = exchangeId,
            data = mapOf("duration" to exchange.created.toString())
        ))

        Log.debug("✅ Camel exchange completed: $exchangeId")
    }

    /**
     * Handle exchange failed event
     */
    private fun handleExchangeFailed(event: CamelEvent.ExchangeFailedEvent) {
        val exchange = event.exchange
        val exchangeId = exchange.getDurableExchangeId() ?: return
        val routeId = exchange.getDurableRouteId() ?: exchange.fromRouteId ?: "unknown"
        val error = exchange.exception?.message ?: "Unknown error"

        broadcaster.broadcast(RouteEvent(
            type = "CAMEL_EXCHANGE_FAILED",
            routeId = routeId,
            exchangeId = exchangeId,
            data = mapOf("error" to error)
        ))

        Log.warn("❌ Camel exchange failed: $exchangeId - $error")
    }

    /**
     * Handle exchange sending event
     */
    private fun handleExchangeSending(event: CamelEvent.ExchangeSendingEvent) {
        val exchange = event.exchange
        val exchangeId = exchange.getDurableExchangeId() ?: return
        val routeId = exchange.getDurableRouteId() ?: exchange.fromRouteId ?: "unknown"
        val endpoint = event.endpoint.endpointUri

        broadcaster.broadcast(RouteEvent(
            type = "CAMEL_EXCHANGE_SENDING",
            routeId = routeId,
            exchangeId = exchangeId,
            data = mapOf("endpoint" to endpoint)
        ))

        Log.debug("📤 Sending to endpoint: $endpoint (exchange: $exchangeId)")
    }

    /**
     * Handle exchange sent event
     */
    private fun handleExchangeSent(event: CamelEvent.ExchangeSentEvent) {
        val exchange = event.exchange
        val exchangeId = exchange.getDurableExchangeId() ?: return
        val routeId = exchange.getDurableRouteId() ?: exchange.fromRouteId ?: "unknown"
        val endpoint = event.endpoint.endpointUri
        val timeTaken = event.timeTaken

        broadcaster.broadcast(RouteEvent(
            type = "CAMEL_EXCHANGE_SENT",
            routeId = routeId,
            exchangeId = exchangeId,
            data = mapOf(
                "endpoint" to endpoint,
                "timeTaken" to timeTaken.toString()
            )
        ))

        Log.debug("✓ Sent to endpoint: $endpoint in ${timeTaken}ms (exchange: $exchangeId)")
    }

    /**
     * Handle route started event
     */
    private fun handleRouteStarted(event: CamelEvent.RouteStartedEvent) {
        val routeId = event.route.routeId

        broadcaster.broadcast(RouteEvent(
            type = "ROUTE_STARTED",
            routeId = routeId
        ))

        Log.info("▶️  Route started: $routeId")
    }

    /**
     * Handle route stopped event
     */
    private fun handleRouteStopped(event: CamelEvent.RouteStoppedEvent) {
        val routeId = event.route.routeId

        broadcaster.broadcast(RouteEvent(
            type = "ROUTE_STOPPED",
            routeId = routeId
        ))

        Log.info("⏹️  Route stopped: $routeId")
    }

    /**
     * Specify which events to listen to
     */
    override fun isEnabled(event: CamelEvent): Boolean {
        return when (event) {
            is CamelEvent.ExchangeCreatedEvent,
            is CamelEvent.ExchangeCompletedEvent,
            is CamelEvent.ExchangeFailedEvent,
            is CamelEvent.ExchangeSendingEvent,
            is CamelEvent.ExchangeSentEvent,
            is CamelEvent.RouteStartedEvent,
            is CamelEvent.RouteStoppedEvent -> true
            else -> false
        }
    }
}
