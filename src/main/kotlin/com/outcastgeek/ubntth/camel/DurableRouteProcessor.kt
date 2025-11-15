package com.outcastgeek.ubntth.camel

import com.outcastgeek.ubntth.services.ExchangeStateManager
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import org.apache.camel.Exchange
import org.apache.camel.Processor
import java.util.*

/**
 * Core processor that wraps Camel route execution with durability features.
 *
 * This processor:
 * - Creates durable exchange state before processing
 * - Saves checkpoints at each step
 * - Checks if execution should continue (handles pause/cancel)
 * - Handles errors and marks exchanges as failed
 *
 * Usage in a Camel route:
 * ```
 * from("direct:myRoute")
 *   .bean(durableRouteProcessor, "wrapDurable(${exchangeProperty.routeId})")
 *   .to("...")
 * ```
 */
@ApplicationScoped
@Named("durableRouteProcessor")
class DurableRouteProcessor(
    private val exchangeStateManager: ExchangeStateManager
) : Processor {

    companion object {
        const val EXCHANGE_ID_HEADER = "CamelDurableExchangeId"
        const val ROUTE_ID_HEADER = "CamelDurableRouteId"
        const val CURRENT_STEP_HEADER = "CamelDurableCurrentStep"
        const val IS_RECOVERY_HEADER = "CamelDurableIsRecovery"
    }

    /**
     * Process an exchange with durability
     */
    override fun process(exchange: Exchange) {
        val exchangeId = getOrCreateExchangeId(exchange)
        val routeId = getRouteId(exchange)
        val isRecovery = exchange.getIn().getHeader(IS_RECOVERY_HEADER, false, Boolean::class.java)

        try {
            if (!isRecovery) {
                // Check if exchange state already exists (created by REST endpoint)
                val existingStatus = exchangeStateManager.getExchangeStatus(exchangeId)
                if (existingStatus == null) {
                    // Create new durable exchange state
                    createDurableExchange(exchange, exchangeId, routeId)
                } else if (existingStatus == com.outcastgeek.ubntth.entities.ExchangeStatus.PENDING) {
                    // Exchange already created by REST, just start it
                    exchangeStateManager.startExchange(exchangeId)
                    Log.info("▶️ Started pre-created exchange: $exchangeId")
                }
            } else {
                Log.info("🔄 Resuming recovered exchange: $exchangeId")
            }

            // Set headers for downstream processors
            exchange.getIn().setHeader(EXCHANGE_ID_HEADER, exchangeId)
            exchange.getIn().setHeader(ROUTE_ID_HEADER, routeId)

        } catch (e: Exception) {
            Log.error("❌ Failed to initialize durable exchange: $exchangeId", e)
            try {
                exchangeStateManager.failExchange(exchangeId, "Initialization failed: ${e.message}")
            } catch (fe: Exception) {
                Log.error("❌ Could not mark exchange as failed: $exchangeId", fe)
            }
            throw e
        }
    }

    /**
     * Create a new durable exchange state
     */
    private fun createDurableExchange(exchange: Exchange, exchangeId: String, routeId: String) {
        val body = exchange.getIn().body?.toString() ?: ""
        val context = extractContext(exchange)

        val state = exchangeStateManager.createExchange(
            exchangeId = exchangeId,
            routeId = routeId,
            payload = body,
            context = context
        )

        // Start the exchange
        exchangeStateManager.startExchange(exchangeId)

        Log.info("📝 Created durable exchange: $exchangeId for route: $routeId")
    }

    /**
     * Save a checkpoint for the current step
     */
    fun checkpoint(
        exchange: Exchange,
        stepName: String,
        stepData: String? = null
    ) {
        val exchangeId = getExchangeId(exchange)
        val currentStep = getCurrentStep(exchange)

        try {
            exchangeStateManager.checkpoint(exchangeId, currentStep, stepName, stepData)

            // Increment step counter
            exchange.getIn().setHeader(CURRENT_STEP_HEADER, currentStep + 1)

            Log.debug("✓ Checkpoint saved for exchange $exchangeId at step $currentStep ($stepName)")

        } catch (e: Exception) {
            Log.error("❌ Failed to save checkpoint for exchange $exchangeId", e)
            throw e
        }
    }

    /**
     * Check if the exchange should continue processing
     * Returns false if the exchange is paused or cancelled
     */
    fun shouldContinue(exchange: Exchange): Boolean {
        val exchangeId = getExchangeId(exchange)

        return try {
            val shouldContinue = exchangeStateManager.shouldContinue(exchangeId)

            if (!shouldContinue) {
                Log.info("⏸️ Exchange $exchangeId should not continue (paused or cancelled)")
            }

            shouldContinue
        } catch (e: Exception) {
            Log.error("❌ Failed to check if exchange $exchangeId should continue", e)
            false
        }
    }

    /**
     * Mark the exchange as completed
     */
    fun complete(exchange: Exchange) {
        val exchangeId = getExchangeId(exchange)

        try {
            // Get the final result from the exchange body
            val result = exchange.getIn().body?.toString()
            exchangeStateManager.completeExchange(exchangeId, result)
            Log.info("✅ Completed exchange: $exchangeId")
        } catch (e: Exception) {
            Log.error("❌ Failed to complete exchange $exchangeId", e)
            throw e
        }
    }

    /**
     * Mark the exchange as failed
     */
    fun fail(exchange: Exchange, error: Throwable) {
        val exchangeId = getExchangeId(exchange)

        try {
            exchangeStateManager.failExchange(exchangeId, error.message ?: "Unknown error")
            Log.error("❌ Failed exchange: $exchangeId - ${error.message}")
        } catch (e: Exception) {
            Log.error("❌ Failed to mark exchange $exchangeId as failed", e)
        }
    }

    /**
     * Get or create an exchange ID
     */
    private fun getOrCreateExchangeId(exchange: Exchange): String {
        var exchangeId = exchange.getIn().getHeader(EXCHANGE_ID_HEADER, String::class.java)

        if (exchangeId == null) {
            exchangeId = UUID.randomUUID().toString()
            exchange.getIn().setHeader(EXCHANGE_ID_HEADER, exchangeId)
        }

        return exchangeId
    }

    /**
     * Get the exchange ID (throws if not found)
     */
    private fun getExchangeId(exchange: Exchange): String {
        return exchange.getIn().getHeader(EXCHANGE_ID_HEADER, String::class.java)
            ?: throw IllegalStateException("Exchange ID not found in headers")
    }

    /**
     * Get the route ID
     */
    private fun getRouteId(exchange: Exchange): String {
        return exchange.getIn().getHeader(ROUTE_ID_HEADER, String::class.java)
            ?: exchange.fromRouteId
            ?: "unknown"
    }

    /**
     * Get the current step counter
     */
    private fun getCurrentStep(exchange: Exchange): Int {
        return exchange.getIn().getHeader(CURRENT_STEP_HEADER, 0, Int::class.java)
    }

    /**
     * Extract context information from the exchange
     */
    private fun extractContext(exchange: Exchange): Map<String, Any> {
        val context = mutableMapOf<String, Any>()

        // Add relevant headers to context
        exchange.getIn().headers.forEach { (key, value) ->
            if (value != null && !key.startsWith("Camel")) {
                context[key] = value
            }
        }

        return context
    }
}

/**
 * Helper extension function to get the durable exchange ID
 */
fun Exchange.getDurableExchangeId(): String? {
    return this.getIn().getHeader(DurableRouteProcessor.EXCHANGE_ID_HEADER, String::class.java)
}

/**
 * Helper extension function to get the durable route ID
 */
fun Exchange.getDurableRouteId(): String? {
    return this.getIn().getHeader(DurableRouteProcessor.ROUTE_ID_HEADER, String::class.java)
}
