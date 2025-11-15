package com.outcastgeek.ubntth.services

import com.outcastgeek.ubntth.entities.ExchangeState
import com.outcastgeek.ubntth.entities.ExchangeStatus
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.transaction.Transactional
import org.apache.camel.CamelContext
import java.time.LocalDateTime

/**
 * Service responsible for crash recovery and monitoring.
 * - Recovers interrupted exchanges on startup
 * - Periodically monitors for stalled exchanges
 * - Restores pending approvals
 */
@ApplicationScoped
@Named("crashRecoveryService")
class CrashRecoveryService {

    @Inject
    lateinit var camelContext: CamelContext

    @Inject
    lateinit var exchangeStateManager: ExchangeStateManager

    @Inject
    lateinit var approvalService: ApprovalService

    @Inject
    lateinit var broadcaster: EventBroadcaster

    /**
     * Run crash recovery on application startup
     */
    fun onStartup(@Observes event: StartupEvent) {
        Log.info("🔄 Starting crash recovery...")

        try {
            recoverInterruptedExchanges()
            restorePendingApprovals()
            Log.info("✅ Crash recovery completed successfully")
        } catch (e: Exception) {
            Log.error("❌ Crash recovery failed", e)
        }
    }

    /**
     * Recover exchanges that were interrupted by a crash or restart
     */
    @Transactional
    fun recoverInterruptedExchanges() {
        val runningExchanges = ExchangeState.findByStatus(ExchangeStatus.RUNNING)

        if (runningExchanges.isEmpty()) {
            Log.info("No interrupted exchanges found")
            return
        }

        Log.info("Found ${runningExchanges.size} interrupted exchanges to recover")

        runningExchanges.forEach { exchange ->
            try {
                Log.info("🔄 Recovering exchange ${exchange.exchangeId} from step ${exchange.currentStep} (${exchange.currentStepName})")

                // Broadcast recovery event
                broadcaster.broadcast(RouteEvent(
                    type = "EXCHANGE_RECOVERING",
                    routeId = exchange.routeId,
                    exchangeId = exchange.exchangeId,
                    data = mapOf(
                        "step" to exchange.currentStep.toString(),
                        "stepName" to (exchange.currentStepName ?: "unknown")
                    )
                ))

                // Resume the exchange by sending it back to Camel
                // The DurableRouteProcessor will pick it up and continue from the checkpoint
                resumeExchange(exchange)

            } catch (e: Exception) {
                Log.error("❌ Failed to recover exchange ${exchange.exchangeId}", e)
                exchangeStateManager.failExchange(
                    exchange.exchangeId,
                    "Recovery failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Restore pending approvals from the database
     */
    @Transactional
    fun restorePendingApprovals() {
        try {
            approvalService.restorePendingApprovals()
        } catch (e: Exception) {
            Log.error("❌ Failed to restore pending approvals", e)
        }
    }

    /**
     * Resume an exchange by sending it back to Camel
     */
    private fun resumeExchange(exchange: ExchangeState) {
        // We'll send the exchange to a special recovery endpoint
        // The route will be implemented in the Camel configuration
        val producerTemplate = camelContext.createProducerTemplate()

        producerTemplate.sendBodyAndHeaders(
            "direct:recover-exchange",
            exchange.payload,
            mapOf(
                "exchangeId" to exchange.exchangeId,
                "routeId" to exchange.routeId,
                "currentStep" to exchange.currentStep,
                "context" to exchange.context
            )
        )

        Log.info("🔄 Sent exchange ${exchange.exchangeId} to recovery route")
    }

    /**
     * Periodically check for stalled exchanges (every 5 minutes)
     * An exchange is considered stalled if it's been in RUNNING state
     * for more than 30 minutes without a checkpoint update
     */
    @Scheduled(every = "5m")
    @Transactional
    fun checkForStalledExchanges() {
        val stalledThresholdMinutes = 30L
        val threshold = LocalDateTime.now().minusMinutes(stalledThresholdMinutes)

        val stalledExchanges = ExchangeState.list(
            "status = ?1 and lastCheckpoint < ?2",
            ExchangeStatus.RUNNING,
            threshold
        )

        if (stalledExchanges.isEmpty()) {
            return
        }

        Log.warn("⚠️ Found ${stalledExchanges.size} stalled exchanges")

        stalledExchanges.forEach { exchange ->
            Log.warn(
                "⚠️ Exchange ${exchange.exchangeId} has been stalled since ${exchange.lastCheckpoint} " +
                        "(last step: ${exchange.currentStep} - ${exchange.currentStepName})"
            )

            broadcaster.broadcast(RouteEvent(
                type = "EXCHANGE_STALLED",
                routeId = exchange.routeId,
                exchangeId = exchange.exchangeId,
                data = mapOf(
                    "lastCheckpoint" to exchange.lastCheckpoint.toString(),
                    "currentStep" to exchange.currentStep.toString(),
                    "stepName" to (exchange.currentStepName ?: "unknown")
                )
            ))

            // Optionally, you could automatically fail stalled exchanges after a certain time
            // For now, we just log and broadcast the event
        }
    }

    /**
     * Periodically check for timed-out approvals (every 10 minutes)
     * An approval is considered timed out if it's been pending for more than 60 minutes
     */
    @Scheduled(every = "10m")
    @Transactional
    fun checkForTimedOutApprovals() {
        val timeoutThresholdMinutes = 60L
        val threshold = LocalDateTime.now().minusMinutes(timeoutThresholdMinutes)

        val timedOutApprovals = approvalService.getPendingApprovals()
            .filter { it.createdAt.isBefore(threshold) }

        if (timedOutApprovals.isEmpty()) {
            return
        }

        Log.warn("⚠️ Found ${timedOutApprovals.size} timed-out approvals")

        timedOutApprovals.forEach { approval ->
            try {
                Log.warn("⏱️ Auto-rejecting timed-out approval ${approval.id} for exchange ${approval.exchangeId}")
                approvalService.reject(approval.id, "Approval timed out after $timeoutThresholdMinutes minutes")
            } catch (e: Exception) {
                Log.error("❌ Failed to auto-reject timed-out approval ${approval.id}", e)
            }
        }
    }

    /**
     * Periodically check for exchanges that were approved and need resumption
     * This supports the non-blocking approval pattern
     */
    @Scheduled(every = "30s")
    fun checkForApprovedExchanges() {
        try {
            resumeApprovedExchanges()
        } catch (e: Exception) {
            Log.error("❌ Error checking for approved exchanges", e)
        }
    }

    /**
     * Resume exchanges that have been approved but are still in WAITING_APPROVAL state
     * This handles the case where non-blocking approval was used
     */
    @Transactional
    fun resumeApprovedExchanges() {
        // Find exchanges that are waiting for approval
        val waitingExchanges = ExchangeState.findByStatus(ExchangeStatus.WAITING_APPROVAL)

        if (waitingExchanges.isEmpty()) {
            return
        }

        // Check each one to see if approval was granted
        val approvedExchanges = waitingExchanges.filter { exchange ->
            val pendingApprovals = approvalService.getPendingApprovals()
            // If there's NO pending approval for this exchange, it was either approved or rejected
            // We check the approval status to determine which
            val hasPendingApproval = pendingApprovals.any { it.exchangeId == exchange.exchangeId }

            if (!hasPendingApproval) {
                // Check if it was approved (not rejected) by looking at the approval history
                val approval = com.outcastgeek.ubntth.entities.ApprovalRequest.list(
                    "exchangeId = ?1 AND status = ?2",
                    exchange.exchangeId,
                    "APPROVED"
                ).firstOrNull<com.outcastgeek.ubntth.entities.ApprovalRequest>()

                approval != null
            } else {
                false
            }
        }

        if (approvedExchanges.isEmpty()) {
            return
        }

        Log.info("🔄 Found ${approvedExchanges.size} approved exchanges to resume")

        approvedExchanges.forEach { exchange ->
            try {
                Log.info("▶️ Resuming approved exchange ${exchange.exchangeId}")

                // Mark as RUNNING
                exchangeStateManager.resumeAfterApproval(exchange.exchangeId)

                // Send to recovery route to continue processing
                resumeExchange(exchange)

            } catch (e: Exception) {
                Log.error("❌ Failed to resume approved exchange ${exchange.exchangeId}", e)
            }
        }
    }

    /**
     * Get statistics about recoverable exchanges
     */
    @Transactional
    fun getRecoveryStats(): RecoveryStats {
        val runningCount = ExchangeState.count("status", ExchangeStatus.RUNNING)
        val pausedCount = ExchangeState.count("status", ExchangeStatus.PAUSED)
        val waitingApprovalCount = ExchangeState.count("status", ExchangeStatus.WAITING_APPROVAL)
        val pendingApprovalsCount = approvalService.getPendingApprovals().size.toLong()

        return RecoveryStats(
            runningExchanges = runningCount,
            pausedExchanges = pausedCount,
            waitingApprovalExchanges = waitingApprovalCount,
            pendingApprovals = pendingApprovalsCount
        )
    }
}

/**
 * Statistics about the recovery system
 */
data class RecoveryStats(
    val runningExchanges: Long,
    val pausedExchanges: Long,
    val waitingApprovalExchanges: Long,
    val pendingApprovals: Long
)
