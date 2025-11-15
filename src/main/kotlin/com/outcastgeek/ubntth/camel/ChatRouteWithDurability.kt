package com.outcastgeek.ubntth.camel

import com.outcastgeek.ubntth.entities.RouteLog
import com.outcastgeek.ubntth.entities.RouteMetric
import com.outcastgeek.ubntth.services.ExchangeStateManager
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.OnCompletionDefinition

/**
 * Sample Camel route demonstrating durable execution with Gemini chat integration.
 *
 * This route demonstrates:
 * - Durable execution with checkpointing
 * - Human-in-the-loop approval gates
 * - Crash recovery
 * - Pause/resume functionality
 * - Multi-step processing with state persistence
 *
 * Flow:
 * 1. Receive chat message
 * 2. Initialize durable execution
 * 3. Validate input
 * 4. Request approval (blocks here)
 * 5. Call Gemini API
 * 6. Process response
 * 7. Complete execution
 */
@ApplicationScoped
@Named("chatRouteWithDurability")
class ChatRouteWithDurability(
    @Named("geminiChatModel") private val chatModel: dev.langchain4j.model.chat.ChatModel
) : RouteBuilder() {

    override fun configure() {
        // Global error handler for all routes
        errorHandler(
            defaultErrorHandler()
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .onExceptionOccurred { exchange ->
                    val exchangeId = exchange.getDurableExchangeId()
                    Log.error("Error in exchange $exchangeId: ${exchange.exception.message}")
                }
        )

        // Global exception handler to mark exchanges as FAILED after retries exhausted
        onException(Exception::class.java)
            .maximumRedeliveries(0) // Already handled by error handler above
            .handled(false) // Let it propagate
            .process { exchange ->
                val exchangeId = exchange.getDurableExchangeId()
                if (exchangeId != null) {
                    val stateManager = exchange.context.registry.lookupByName("exchangeStateManager") as? ExchangeStateManager
                    if (stateManager != null) {
                        try {
                            val error = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception::class.java)
                            val errorMsg = error?.message ?: "Unknown error"
                            stateManager.failExchange(exchangeId, errorMsg)
                            Log.error("❌ Marked exchange as FAILED: $exchangeId - $errorMsg")
                        } catch (e: Exception) {
                            Log.error("Could not mark exchange as failed: ${e.message}", e)
                        }
                    }
                }
            }

        // Main chat route with durable execution
        from("direct:chat-durable")
            .routeId("chat-durable-route")
            .log("📨 Received chat message: \${body}")

            // Initialize durable execution (only if not recovering)
            .choice()
                .`when`(header(DurableRouteProcessor.IS_RECOVERY_HEADER).isNull())
                    .setHeader(DurableRouteProcessor.ROUTE_ID_HEADER, constant("chat-durable-route"))
                    .process { exchange ->
                        val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                        processor.process(exchange)
                    }
            .end()

            // Step 1: Validate input
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                if (processor.shouldContinue(exchange)) {
                    processor.checkpoint(exchange, "validate-input")
                } else {
                    exchange.setProperty(Exchange.ROUTE_STOP, true)
                }
            }
            .to("direct:validate-chat-input")

            // Step 2: Log to audit trail
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                if (processor.shouldContinue(exchange)) {
                    processor.checkpoint(exchange, "log-request")
                } else {
                    exchange.setProperty(Exchange.ROUTE_STOP, true)
                }
            }
            .to("direct:log-chat-request")

            // Step 3: Request approval (BLOCKS HERE until approved/rejected)
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                if (processor.shouldContinue(exchange)) {
                    processor.checkpoint(exchange, "before-approval")
                } else {
                    exchange.setProperty(Exchange.ROUTE_STOP, true)
                }
            }
            .bean("approvalGateProcessor")
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                processor.checkpoint(exchange, "after-approval")
            }

            // Step 4: Call Gemini API
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                if (processor.shouldContinue(exchange)) {
                    processor.checkpoint(exchange, "call-gemini")
                } else {
                    exchange.setProperty(Exchange.ROUTE_STOP, true)
                }
            }
            .to("direct:call-gemini-api")

            // Step 5: Process response
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                if (processor.shouldContinue(exchange)) {
                    // Save the actual response in the checkpoint
                    val responseBody = exchange.getIn().body?.toString() ?: ""
                    processor.checkpoint(exchange, "process-response", responseBody)
                } else {
                    exchange.setProperty(Exchange.ROUTE_STOP, true)
                }
            }
            .to("direct:process-chat-response")

            // Step 6: Update metrics
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                if (processor.shouldContinue(exchange)) {
                    processor.checkpoint(exchange, "update-metrics")
                } else {
                    exchange.setProperty(Exchange.ROUTE_STOP, true)
                }
            }
            .to("direct:update-route-metrics")

            // Complete the durable exchange
            .process { exchange ->
                val processor = exchange.context.registry.lookupByName("durableRouteProcessor") as DurableRouteProcessor
                processor.complete(exchange)
            }
            .log("✅ Chat exchange completed: \${header.CamelDurableExchangeId}")

        // Recovery route for crashed exchanges
        from("direct:recover-exchange")
            .routeId("recovery-route")
            .log("🔄 Recovering exchange: \${header.exchangeId}")

            // Set recovery flag
            .setHeader(DurableRouteProcessor.IS_RECOVERY_HEADER, constant(true))
            .setHeader(DurableRouteProcessor.EXCHANGE_ID_HEADER, simple("\${header.exchangeId}"))
            .setHeader(DurableRouteProcessor.ROUTE_ID_HEADER, simple("\${header.routeId}"))
            .setHeader(DurableRouteProcessor.CURRENT_STEP_HEADER, simple("\${header.currentStep}"))

            // Restore context
            .process { exchange ->
                val context = exchange.getIn().getHeader("context", String::class.java)
                if (context != null) {
                    // Parse and restore context headers
                    Log.info("Restoring context for exchange: ${exchange.getDurableExchangeId()}")
                }
            }

            // Send to the original route for processing
            .to("direct:chat-durable")

        // Input validation sub-route
        from("direct:validate-chat-input")
            .routeId("validate-input-route")
            .process { exchange ->
                val body = exchange.getIn().body?.toString()

                if (body.isNullOrBlank()) {
                    throw IllegalArgumentException("Chat message cannot be empty")
                }

                // Increased limit to 50000 characters to support code review scenarios
                if (body.length > 50000) {
                    throw IllegalArgumentException("Chat message too long (max 50000 characters)")
                }

                Log.info("✓ Input validated for exchange: ${exchange.getDurableExchangeId()} (${body.length} chars)")
            }

        // Log request sub-route
        from("direct:log-chat-request")
            .routeId("log-request-route")
            .process { exchange ->
                val exchangeId = exchange.getDurableExchangeId() ?: "unknown"
                val routeId = exchange.getDurableRouteId() ?: "unknown"
                val body = exchange.getIn().body?.toString() ?: ""

                // Skip logging in non-transactional context (e.g., test environment)
                try {
                    RouteLog.logEvent(
                        routeId = routeId,
                        exchangeId = exchangeId,
                        eventType = "CHAT_REQUEST",
                        message = "Request: ${body.take(100)}"
                    )
                } catch (e: Exception) {
                    Log.warn("Could not log event: ${e.message}")
                }

                Log.info("📝 Logged request for exchange: $exchangeId")
            }

        // Gemini API call sub-route
        from("direct:call-gemini-api")
            .routeId("gemini-api-route")
            .process { exchange ->
                val message = exchange.getIn().body?.toString() ?: ""
                val exchangeId = exchange.getDurableExchangeId()

                Log.info("🤖 Calling Gemini API for exchange: $exchangeId")

                try {
                    val response = chatModel.chat(message)
                    exchange.getIn().body = response
                    Log.info("✓ Gemini API call completed for exchange: $exchangeId")
                } catch (e: Exception) {
                    Log.error("❌ Gemini API call failed for exchange: $exchangeId", e)
                    throw e
                }
            }

        // Process response sub-route
        from("direct:process-chat-response")
            .routeId("process-response-route")
            .process { exchange ->
                val response = exchange.getIn().body?.toString() ?: ""
                val exchangeId = exchange.getDurableExchangeId() ?: "unknown"
                val routeId = exchange.getDurableRouteId() ?: "unknown"

                // Log the response - skip if no transaction context
                try {
                    RouteLog.logEvent(
                        routeId = routeId,
                        exchangeId = exchangeId,
                        eventType = "CHAT_RESPONSE",
                        message = "Response: ${response.take(100)}"
                    )
                } catch (e: Exception) {
                    Log.warn("Could not log response: ${e.message}")
                }

                Log.info("📝 Processed response for exchange: $exchangeId")
            }

        // Update metrics sub-route
        from("direct:update-route-metrics")
            .routeId("update-metrics-route")
            .process { exchange ->
                val routeId = exchange.getDurableRouteId() ?: "unknown"

                // Update metrics - skip if no transaction context
                try {
                    val metric = RouteMetric.findOrCreate(routeId)
                    metric.totalCount++
                    metric.successCount++
                    metric.lastUpdated = java.time.LocalDateTime.now()
                    metric.persist()
                } catch (e: Exception) {
                    Log.warn("Could not update metrics: ${e.message}")
                }

                Log.info("📊 Updated metrics for route: $routeId")
            }

        // Simple non-durable chat route (still updates exchange state for tracking)
        from("direct:chat-simple")
            .routeId("chat-simple-route")
            .log("📨 Simple chat message: \${body}")

            // Handle errors for simple route
            .onException(Exception::class.java)
                .process { exchange ->
                    val exchangeId = exchange.getIn().getHeader(DurableRouteProcessor.EXCHANGE_ID_HEADER, String::class.java)
                    if (exchangeId != null) {
                        val stateManager = exchange.context.registry.lookupByName("exchangeStateManager") as ExchangeStateManager
                        try {
                            val error = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception::class.java)?.message ?: "Unknown error"
                            stateManager.failExchange(exchangeId, error)
                            Log.error("❌ Failed simple exchange: $exchangeId - $error")
                        } catch (e: Exception) {
                            Log.error("Could not mark exchange as failed: ${e.message}", e)
                        }
                    }
                }
                .handled(false)
            .end()

            // Start the exchange if it was pre-created
            .process { exchange ->
                val exchangeId = exchange.getIn().getHeader(DurableRouteProcessor.EXCHANGE_ID_HEADER, String::class.java)
                if (exchangeId != null) {
                    val stateManager = exchange.context.registry.lookupByName("exchangeStateManager") as ExchangeStateManager
                    try {
                        val status = stateManager.getExchangeStatus(exchangeId)
                        if (status == com.outcastgeek.ubntth.entities.ExchangeStatus.PENDING) {
                            stateManager.startExchange(exchangeId)
                            Log.info("▶️ Started simple exchange: $exchangeId")
                        }
                    } catch (e: Exception) {
                        Log.warn("Could not start exchange state: ${e.message}")
                    }
                }
            }

            .to("direct:call-gemini-api")

            // Complete the exchange and store the response
            .process { exchange ->
                val exchangeId = exchange.getIn().getHeader(DurableRouteProcessor.EXCHANGE_ID_HEADER, String::class.java)
                if (exchangeId != null) {
                    val stateManager = exchange.context.registry.lookupByName("exchangeStateManager") as ExchangeStateManager
                    try {
                        val result = exchange.getIn().body?.toString()
                        stateManager.completeExchange(exchangeId, result)
                        Log.info("✅ Completed simple exchange: $exchangeId")
                    } catch (e: Exception) {
                        Log.error("Could not complete exchange state: ${e.message}", e)
                    }
                }
            }

            .log("✅ Simple chat completed: \${body}")
    }
}
