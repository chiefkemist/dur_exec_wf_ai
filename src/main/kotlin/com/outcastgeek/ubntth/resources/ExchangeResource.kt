package com.outcastgeek.ubntth.resources

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.outcastgeek.ubntth.camel.DurableRouteProcessor
import com.outcastgeek.ubntth.entities.ExchangeCheckpoint
import com.outcastgeek.ubntth.entities.ExchangeState
import com.outcastgeek.ubntth.entities.ExchangeStatus
import com.outcastgeek.ubntth.services.ExchangeStateManager
import io.quarkus.logging.Log
import jakarta.inject.Inject
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.apache.camel.CamelContext
import org.apache.camel.ProducerTemplate
import java.util.*

/**
 * REST API for managing durable exchanges.
 *
 * Endpoints:
 * - POST /api/exchanges - Create and trigger a new exchange
 * - GET /api/exchanges - List all exchanges
 * - GET /api/exchanges/{id} - Get exchange details
 * - POST /api/exchanges/{id}/pause - Pause an exchange
 * - POST /api/exchanges/{id}/resume - Resume an exchange
 * - POST /api/exchanges/{id}/cancel - Cancel an exchange
 * - GET /api/exchanges/{id}/checkpoints - Get checkpoints for an exchange
 */
@Path("/api/exchanges")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
class ExchangeResource {

    @Inject
    lateinit var exchangeStateManager: ExchangeStateManager

    @Inject
    lateinit var camelContext: CamelContext

    /**
     * Create and trigger a new durable exchange
     */
    @POST
    fun createExchange(request: CreateExchangeRequest): Response {
        Log.info("🔥 [BACKEND] POST /api/exchanges received")
        Log.info("🔥 [BACKEND] Request body: routeId=${request.routeId}, payload=${request.payload.take(100)}")

        try {
            val exchangeId = UUID.randomUUID().toString()

            Log.info("📨 [BACKEND] Creating new exchange: $exchangeId for route: ${request.routeId}")

            // Create the exchange state in the database first
            Log.info("💾 [BACKEND] Saving exchange state to database...")
            exchangeStateManager.createExchange(
                exchangeId = exchangeId,
                routeId = request.routeId,
                payload = request.payload
            )
            Log.info("✅ [BACKEND] Exchange state saved to database")

            // Send to the appropriate route with headers
            val headers = mutableMapOf<String, Any>(
                DurableRouteProcessor.EXCHANGE_ID_HEADER to exchangeId,
                DurableRouteProcessor.ROUTE_ID_HEADER to request.routeId
            )
            Log.info("🔧 [BACKEND] Headers prepared: $headers")

            // Add custom headers if provided
            request.headers?.let { headers.putAll(it) }

            // Send the message asynchronously to avoid blocking using FluentProducerTemplate
            Log.info("🚀 [BACKEND] Triggering Camel route: direct:${request.routeId}")
            try {
                camelContext.createFluentProducerTemplate()
                    .withBody(request.payload)
                    .withHeaders(headers)
                    .to("direct:${request.routeId}")
                    .asyncSend()

                Log.info("✅ [BACKEND] Exchange $exchangeId triggered successfully via direct:${request.routeId}")
            } catch (e: Exception) {
                Log.error("⚠️ [BACKEND] Could not trigger route ${request.routeId}: ${e.message}", e)
                // Continue even if route trigger fails - exchange is still created
            }

            val responseBody = mapOf(
                "exchangeId" to exchangeId,
                "routeId" to request.routeId,
                "message" to "Exchange created and triggered"
            )
            Log.info("📤 [BACKEND] Returning response: $responseBody")

            return Response.accepted().entity(responseBody).build()

        } catch (e: Exception) {
            Log.error("❌ [BACKEND] Failed to create exchange", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * List all exchanges with optional filtering
     */
    @GET
    fun listExchanges(
        @QueryParam("status") status: String?,
        @QueryParam("routeId") routeId: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int
    ): Response {
        try {
            val exchanges = if (status != null) {
                val exchangeStatus = ExchangeStatus.valueOf(status.uppercase())
                ExchangeState.findByStatus(exchangeStatus)
            } else if (routeId != null) {
                ExchangeState.findByRouteId(routeId)
            } else {
                ExchangeState.findAllSorted()
            }

            // Apply pagination
            val paginatedExchanges = exchanges
                .drop(offset)
                .take(limit)

            return Response.ok(mapOf(
                "exchanges" to paginatedExchanges,
                "total" to exchanges.size,
                "limit" to limit,
                "offset" to offset
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to list exchanges", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get details of a specific exchange
     */
    @GET
    @Path("/{id}")
    fun getExchange(@PathParam("id") exchangeId: String): Response {
        try {
            val exchange = ExchangeState.findById(exchangeId)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "Exchange not found: $exchangeId"))
                    .build()

            return Response.ok(exchange).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get exchange: $exchangeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Pause a running exchange
     */
    @POST
    @Path("/{id}/pause")
    fun pauseExchange(@PathParam("id") exchangeId: String): Response {
        try {
            exchangeStateManager.pauseExchange(exchangeId)

            Log.info("⏸️  Paused exchange: $exchangeId")

            return Response.ok(mapOf(
                "exchangeId" to exchangeId,
                "message" to "Exchange paused successfully"
            )).build()

        } catch (e: IllegalStateException) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to e.message))
                .build()
        } catch (e: Exception) {
            Log.error("❌ Failed to pause exchange: $exchangeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Resume a paused exchange
     */
    @POST
    @Path("/{id}/resume")
    fun resumeExchange(@PathParam("id") exchangeId: String): Response {
        try {
            exchangeStateManager.resumeExchange(exchangeId)

            // Trigger recovery to resume the exchange
            val exchange = ExchangeState.findById(exchangeId)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "Exchange not found: $exchangeId"))
                    .build()

            try {
                camelContext.createFluentProducerTemplate()
                    .withBody(exchange.payload)
                    .withHeaders(mapOf(
                        "exchangeId" to exchange.exchangeId,
                        "routeId" to exchange.routeId,
                        "currentStep" to exchange.currentStep,
                        "context" to (exchange.context ?: "")
                    ))
                    .to("direct:recover-exchange")
                    .asyncSend()

                Log.info("▶️  Resumed exchange: $exchangeId")
            } catch (e: Exception) {
                Log.warn("⚠️ Could not trigger recovery route: ${e.message}")
                // Continue even if recovery trigger fails - status is still updated
            }

            return Response.ok(mapOf(
                "exchangeId" to exchangeId,
                "message" to "Exchange resumed successfully"
            )).build()

        } catch (e: IllegalStateException) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to e.message))
                .build()
        } catch (e: Exception) {
            Log.error("❌ Failed to resume exchange: $exchangeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Cancel an exchange
     */
    @POST
    @Path("/{id}/cancel")
    fun cancelExchange(@PathParam("id") exchangeId: String): Response {
        try {
            exchangeStateManager.cancelExchange(exchangeId)

            Log.info("❌ Cancelled exchange: $exchangeId")

            return Response.ok(mapOf(
                "exchangeId" to exchangeId,
                "message" to "Exchange cancelled successfully"
            )).build()

        } catch (e: IllegalStateException) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to e.message))
                .build()
        } catch (e: Exception) {
            Log.error("❌ Failed to cancel exchange: $exchangeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get checkpoints for an exchange
     */
    @GET
    @Path("/{id}/checkpoints")
    fun getCheckpoints(@PathParam("id") exchangeId: String): Response {
        try {
            val checkpoints = ExchangeCheckpoint.findByExchangeId(exchangeId)

            return Response.ok(mapOf(
                "exchangeId" to exchangeId,
                "checkpoints" to checkpoints,
                "total" to checkpoints.size
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get checkpoints for exchange: $exchangeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }
}

/**
 * Request body for creating a new exchange
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CreateExchangeRequest(
    val routeId: String,
    val payload: String,
    val headers: Map<String, Any>? = null
)
