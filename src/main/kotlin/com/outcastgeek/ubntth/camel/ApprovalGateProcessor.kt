package com.outcastgeek.ubntth.camel

import com.outcastgeek.ubntth.services.ApprovalService
import com.outcastgeek.ubntth.services.ApprovalRejectedException
import com.outcastgeek.ubntth.services.ApprovalTimeoutException
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import org.apache.camel.Exchange
import org.apache.camel.Processor

/**
 * Camel processor that implements human-in-the-loop approval gates.
 *
 * This processor:
 * - Blocks the exchange until approval is received
 * - Creates an approval request via ApprovalService
 * - Handles approval, rejection, and timeout scenarios
 * - Works with DurableRouteProcessor for checkpointing
 *
 * Usage in a Camel route:
 * ```
 * from("direct:myRoute")
 *   .bean(durableRouteProcessor, "checkpoint(${exchange}, 'before-approval')")
 *   .bean(approvalGateProcessor, "requestApproval")
 *   .bean(durableRouteProcessor, "checkpoint(${exchange}, 'after-approval')")
 *   .to("...")
 * ```
 */
@ApplicationScoped
@Named("approvalGateProcessor")
class ApprovalGateProcessor(
    private val approvalService: ApprovalService,
    private val durableRouteProcessor: DurableRouteProcessor
) : Processor {

    companion object {
        const val APPROVAL_DECISION_HEADER = "CamelApprovalDecision"
        const val APPROVAL_RESPONSE_HEADER = "CamelApprovalResponse"
        const val APPROVAL_ID_HEADER = "CamelApprovalId"
    }

    /**
     * Process an exchange with an approval gate
     */
    override fun process(exchange: Exchange) {
        Log.info("⏸️ [APPROVAL] ApprovalGateProcessor.process() called")
        val exchangeId = exchange.getDurableExchangeId()
            ?: throw IllegalStateException("Durable exchange ID not found. Did you forget to use DurableRouteProcessor?")

        val routeId = exchange.getDurableRouteId()
            ?: throw IllegalStateException("Durable route ID not found. Did you forget to use DurableRouteProcessor?")

        Log.info("⏸️ [APPROVAL] Exchange: $exchangeId, Route: $routeId")
        requestApproval(exchange, exchangeId, routeId)
    }

    /**
     * Request approval for the exchange and block until received.
     *
     * IMPORTANT: With REQUIRES_NEW transactions, each database operation has its own
     * short-lived transaction. The blocking wait does NOT hold any database locks.
     * This is critical for avoiding transaction timeout issues.
     *
     * For truly non-blocking approvals, use requestApprovalNonBlocking() instead.
     */
    fun requestApproval(
        exchange: Exchange,
        exchangeId: String = exchange.getDurableExchangeId() ?: "",
        routeId: String = exchange.getDurableRouteId() ?: "",
        timeoutMinutes: Long = 60
    ) {
        val payload = exchange.getIn().body?.toString() ?: ""

        Log.info("🔔 [APPROVAL] Requesting approval for exchange $exchangeId")
        Log.info("🔔 [APPROVAL] Payload: ${payload.take(100)}")
        Log.info("🔔 [APPROVAL] Timeout: $timeoutMinutes minutes")

        // Save checkpoint before approval (REQUIRES_NEW transaction, commits immediately)
        Log.info("💾 [APPROVAL] Saving checkpoint: waiting-for-approval")
        durableRouteProcessor.checkpoint(exchange, "waiting-for-approval", payload)

        try {
            // This will BLOCK until approval is received or timeout occurs
            // NOTE: No transaction is held during this wait due to REQUIRES_NEW pattern
            Log.info("⏳ [APPROVAL] Calling approvalService.requestApproval() - THIS WILL BLOCK")
            Log.info("⏳ [APPROVAL] No database transaction held during wait (REQUIRES_NEW pattern)")
            val decision = approvalService.requestApproval(
                exchangeId = exchangeId,
                routeId = routeId,
                payload = payload,
                timeoutMinutes = timeoutMinutes
            )

            // Approval granted!
            Log.info("✅ [APPROVAL] Approval granted for exchange $exchangeId")
            Log.info("✅ [APPROVAL] Response: ${decision.response}")

            // Set headers with approval decision
            exchange.getIn().setHeader(APPROVAL_DECISION_HEADER, true)
            exchange.getIn().setHeader(APPROVAL_RESPONSE_HEADER, decision.response)

            // Save checkpoint after approval (REQUIRES_NEW transaction, commits immediately)
            durableRouteProcessor.checkpoint(
                exchange,
                "approval-granted",
                decision.response
            )

        } catch (e: ApprovalRejectedException) {
            // Approval rejected
            Log.warn("❌ Approval rejected for exchange $exchangeId: ${e.message}")

            exchange.getIn().setHeader(APPROVAL_DECISION_HEADER, false)
            exchange.getIn().setHeader(APPROVAL_RESPONSE_HEADER, e.message)

            // Mark the exchange as failed
            durableRouteProcessor.fail(exchange, e)

            throw e

        } catch (e: ApprovalTimeoutException) {
            // Approval timed out
            Log.warn("⏱️ Approval timed out for exchange $exchangeId: ${e.message}")

            exchange.getIn().setHeader(APPROVAL_DECISION_HEADER, false)
            exchange.getIn().setHeader(APPROVAL_RESPONSE_HEADER, "Approval timeout")

            // Mark the exchange as failed
            durableRouteProcessor.fail(exchange, e)

            throw e

        } catch (e: Exception) {
            // Unexpected error
            Log.error("❌ Unexpected error during approval for exchange $exchangeId", e)

            exchange.getIn().setHeader(APPROVAL_DECISION_HEADER, false)
            exchange.getIn().setHeader(APPROVAL_RESPONSE_HEADER, e.message)

            // Mark the exchange as failed
            durableRouteProcessor.fail(exchange, e)

            throw e
        }
    }

    /**
     * Request approval in a non-blocking manner.
     * This method creates the approval request and immediately returns,
     * allowing the route to be stopped. The exchange can be resumed later
     * after approval is granted via CrashRecoveryService or a polling mechanism.
     *
     * Use this when you don't want to hold Camel threads during approval.
     *
     * @return The approval request ID
     */
    fun requestApprovalNonBlocking(
        exchange: Exchange,
        exchangeId: String = exchange.getDurableExchangeId() ?: "",
        routeId: String = exchange.getDurableRouteId() ?: ""
    ): String {
        val payload = exchange.getIn().body?.toString() ?: ""

        Log.info("🔔 [APPROVAL] Non-blocking approval request for exchange $exchangeId")

        // Save checkpoint before approval
        durableRouteProcessor.checkpoint(exchange, "waiting-for-approval", payload)

        // Create the approval request in database (this commits immediately with REQUIRES_NEW)
        val approvalId = approvalService.createApprovalRequest(exchangeId, routeId, payload)

        Log.info("📋 [APPROVAL] Created non-blocking approval request $approvalId")
        Log.info("📋 [APPROVAL] Exchange $exchangeId will wait for approval (no thread blocked)")

        // Set header so route knows this is non-blocking
        exchange.getIn().setHeader(APPROVAL_ID_HEADER, approvalId)
        exchange.getIn().setHeader("CamelApprovalNonBlocking", true)

        // Stop the route execution - exchange stays in WAITING_APPROVAL state
        exchange.setProperty(org.apache.camel.Exchange.ROUTE_STOP, true)

        return approvalId
    }

    /**
     * Request approval with custom timeout
     */
    fun requestApprovalWithTimeout(
        exchange: Exchange,
        timeoutMinutes: Long
    ) {
        val exchangeId = exchange.getDurableExchangeId()
            ?: throw IllegalStateException("Durable exchange ID not found")
        val routeId = exchange.getDurableRouteId()
            ?: throw IllegalStateException("Durable route ID not found")

        requestApproval(exchange, exchangeId, routeId, timeoutMinutes)
    }

    /**
     * Request approval with custom payload
     */
    fun requestApprovalWithPayload(
        exchange: Exchange,
        customPayload: String,
        timeoutMinutes: Long = 60
    ) {
        val exchangeId = exchange.getDurableExchangeId()
            ?: throw IllegalStateException("Durable exchange ID not found")
        val routeId = exchange.getDurableRouteId()
            ?: throw IllegalStateException("Durable route ID not found")

        Log.info("🔔 Requesting approval for exchange $exchangeId with custom payload")

        // Save checkpoint before approval
        durableRouteProcessor.checkpoint(exchange, "waiting-for-approval", customPayload)

        try {
            val decision = approvalService.requestApproval(
                exchangeId = exchangeId,
                routeId = routeId,
                payload = customPayload,
                timeoutMinutes = timeoutMinutes
            )

            Log.info("✅ Approval granted for exchange $exchangeId")

            exchange.getIn().setHeader(APPROVAL_DECISION_HEADER, true)
            exchange.getIn().setHeader(APPROVAL_RESPONSE_HEADER, decision.response)

            durableRouteProcessor.checkpoint(
                exchange,
                "approval-granted",
                decision.response
            )

        } catch (e: Exception) {
            Log.error("❌ Approval failed for exchange $exchangeId", e)
            exchange.getIn().setHeader(APPROVAL_DECISION_HEADER, false)
            exchange.getIn().setHeader(APPROVAL_RESPONSE_HEADER, e.message)
            durableRouteProcessor.fail(exchange, e)
            throw e
        }
    }
}
