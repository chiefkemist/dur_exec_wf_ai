package com.outcastgeek.ubntth.resources

import com.outcastgeek.ubntth.entities.RouteLog
import com.outcastgeek.ubntth.entities.RouteMetric
import com.outcastgeek.ubntth.services.CrashRecoveryService
import io.quarkus.logging.Log
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.apache.camel.CamelContext

/**
 * REST API for monitoring routes and viewing metrics.
 *
 * Endpoints:
 * - GET /api/routes - List all available routes
 * - GET /api/routes/{id}/metrics - Get metrics for a route
 * - GET /api/routes/{id}/logs - Get logs for a route
 * - GET /api/routes/{id}/status - Get status of a route
 * - GET /api/routes/recovery-stats - Get crash recovery statistics
 */
@Path("/api/routes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
class RouteResource {

    @Inject
    lateinit var camelContext: CamelContext

    @Inject
    lateinit var crashRecoveryService: CrashRecoveryService

    /**
     * List all available Camel routes
     */
    @GET
    fun listRoutes(): Response {
        try {
            val routeController = camelContext.routeController
            val routes = camelContext.routes.map { route ->
                val status = routeController.getRouteStatus(route.routeId)
                mapOf(
                    "routeId" to route.routeId,
                    "description" to (route.description ?: ""),
                    "status" to status.name,
                    "uptime" to route.uptime
                )
            }

            return Response.ok(mapOf(
                "routes" to routes,
                "total" to routes.size
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to list routes", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get metrics for a specific route
     */
    @GET
    @Path("/{id}/metrics")
    @Transactional
    fun getRouteMetrics(@PathParam("id") routeId: String): Response {
        try {
            val metric = RouteMetric.findOrCreate(routeId)

            return Response.ok(mapOf(
                "routeId" to routeId,
                "metrics" to metric
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get metrics for route: $routeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get logs for a specific route
     */
    @GET
    @Path("/{id}/logs")
    fun getRouteLogs(
        @PathParam("id") routeId: String,
        @QueryParam("limit") @DefaultValue("100") limit: Int
    ): Response {
        try {
            val logs = RouteLog.getRecentLogs(routeId, limit)

            return Response.ok(mapOf(
                "routeId" to routeId,
                "logs" to logs,
                "total" to logs.size
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get logs for route: $routeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get status of a specific route
     */
    @GET
    @Path("/{id}/status")
    fun getRouteStatus(@PathParam("id") routeId: String): Response {
        try {
            val route = camelContext.getRoute(routeId)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "Route not found: $routeId"))
                    .build()

            val routeController = camelContext.routeController
            val status = routeController.getRouteStatus(routeId)

            return Response.ok(mapOf(
                "routeId" to routeId,
                "status" to status.name,
                "description" to (route.description ?: ""),
                "uptime" to route.uptime
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get status for route: $routeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get all route metrics
     */
    @GET
    @Path("/metrics")
    fun getAllMetrics(): Response {
        try {
            val metrics = RouteMetric.findAllMetrics()

            return Response.ok(mapOf(
                "metrics" to metrics,
                "total" to metrics.size
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get all metrics", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get crash recovery statistics
     */
    @GET
    @Path("/recovery-stats")
    fun getRecoveryStats(): Response {
        try {
            val stats = crashRecoveryService.getRecoveryStats()

            return Response.ok(mapOf(
                "recoveryStats" to stats
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get recovery stats", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get logs for a specific exchange
     */
    @GET
    @Path("/logs/exchange/{exchangeId}")
    fun getExchangeLogs(@PathParam("exchangeId") exchangeId: String): Response {
        try {
            val logs = RouteLog.getExchangeLogs(exchangeId)

            return Response.ok(mapOf(
                "exchangeId" to exchangeId,
                "logs" to logs,
                "total" to logs.size
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get logs for exchange: $exchangeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }
}
