package com.outcastgeek.ubntth.services

import com.outcastgeek.ubntth.entities.ApprovalRequest
import com.outcastgeek.ubntth.entities.ExchangeState
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import jakarta.transaction.Transactional
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Service for managing human-in-the-loop approvals.
 * Uses CompletableFuture to block exchange processing until approval is received.
 */
@ApplicationScoped
@Named("approvalService")
class ApprovalService(
    private val exchangeStateManager: ExchangeStateManager,
    private val broadcaster: EventBroadcaster
) {

    // Maps approval request ID to CompletableFuture for blocking/unblocking
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<ApprovalDecision>>()

    /**
     * Create an approval request and block until it's resolved.
     * This method is called from within a Camel route.
     *
     * @return ApprovalDecision containing the approval status and optional response
     * @throws ApprovalRejectedException if the request is rejected
     * @throws ApprovalTimeoutException if the request times out
     */
    fun requestApproval(
        exchangeId: String,
        routeId: String,
        payload: String,
        timeoutMinutes: Long = 60
    ): ApprovalDecision {
        Log.info("🔔 [APPROVAL_SVC] requestApproval() called")
        Log.info("🔔 [APPROVAL_SVC] exchangeId=$exchangeId, routeId=$routeId")

        // Create approval request in separate transaction
        val approvalId = createApprovalRequest(exchangeId, routeId, payload)

        // Create a future to block on
        Log.info("⏳ [APPROVAL_SVC] Creating CompletableFuture for approval $approvalId")
        val future = CompletableFuture<ApprovalDecision>()
        pendingApprovals[approvalId] = future
        Log.info("⏳ [APPROVAL_SVC] Pending approvals count: ${pendingApprovals.size}")

        return try {
            // Block until approval is received or timeout
            Log.info("🚫 [APPROVAL_SVC] NOW BLOCKING on future.get() for $timeoutMinutes minutes...")
            val decision = future.get(timeoutMinutes, TimeUnit.MINUTES)
            Log.info("✅ [APPROVAL_SVC] Future completed! Decision: $decision")
            decision
        } catch (e: Exception) {
            Log.error("⏱️ [APPROVAL_SVC] Approval request $approvalId timed out or was interrupted", e)
            pendingApprovals.remove(approvalId)

            // Mark approval as rejected due to timeout
            markApprovalTimedOut(approvalId)

            throw ApprovalTimeoutException("Approval request timed out after $timeoutMinutes minutes")
        }
    }

    @Transactional
    fun createApprovalRequest(exchangeId: String, routeId: String, payload: String): String {
        Log.info("💾 [APPROVAL_SVC] Creating approval request entity in DB...")
        val approval = ApprovalRequest(
            exchangeId = exchangeId,
            routeId = routeId,
            payload = payload
        )
        approval.persist()

        Log.info("💾 [APPROVAL_SVC] Created approval request ${approval.id} for exchange $exchangeId")

        // Mark exchange as waiting for approval
        Log.info("💾 [APPROVAL_SVC] Marking exchange as waiting for approval...")
        exchangeStateManager.markWaitingApproval(exchangeId)

        // Broadcast approval request event
        Log.info("📡 [APPROVAL_SVC] Broadcasting APPROVAL_REQUESTED event...")
        broadcaster.broadcast(RouteEvent(
            type = "APPROVAL_REQUESTED",
            routeId = routeId,
            exchangeId = exchangeId,
            data = mapOf("approvalId" to approval.id)
        ))

        return approval.id
    }

    @Transactional
    fun markApprovalTimedOut(approvalId: String) {
        val approval = ApprovalRequest.findById(approvalId)
        if (approval != null) {
            approval.status = "REJECTED"
            approval.completedAt = LocalDateTime.now()
        }
    }

    /**
     * Approve a pending approval request.
     * This method commits the database changes FIRST, then unblocks the waiting thread.
     */
    fun approve(approvalId: String, response: String? = null): Boolean {
        Log.info("✅ [APPROVAL_SVC] approve() called for $approvalId")
        Log.info("✅ [APPROVAL_SVC] Response: $response")

        // Step 1: Update database in its own transaction (will commit before we return)
        val approval = updateApprovalStatus(approvalId)

        Log.info("✅ [APPROVAL_SVC] Database transaction committed for $approvalId")

        // Step 2: Resume the exchange status (separate transaction)
        exchangeStateManager.resumeAfterApproval(approval.exchangeId)

        // Step 3: Broadcast approval event
        broadcaster.broadcast(RouteEvent(
            type = "APPROVAL_GRANTED",
            routeId = approval.routeId,
            exchangeId = approval.exchangeId,
            data = mapOf("approvalId" to approvalId)
        ))

        // Step 4: AFTER transaction is committed, complete the future to unblock the waiting exchange
        // This ensures the Camel thread won't see SQLITE_BUSY because our transaction is done
        Log.info("🔓 [APPROVAL_SVC] Looking for pending future for $approvalId...")
        Log.info("🔓 [APPROVAL_SVC] Pending approvals: ${pendingApprovals.keys}")
        val future = pendingApprovals.remove(approvalId)
        if (future != null) {
            Log.info("🔓 [APPROVAL_SVC] Found future, completing it to UNBLOCK waiting thread...")
            future.complete(ApprovalDecision(approved = true, response = response))
            Log.info("🔓 [APPROVAL_SVC] Future completed! Waiting thread should resume now.")
        } else {
            Log.warn("⚠️ [APPROVAL_SVC] No pending future found for $approvalId - thread may not be waiting?")
        }

        return true
    }

    /**
     * Update the approval status in the database.
     */
    @Transactional
    fun updateApprovalStatus(approvalId: String): ApprovalRequest {
        val approval = ApprovalRequest.findById(approvalId)
            ?: throw IllegalArgumentException("Approval request not found: $approvalId")

        Log.info("✅ [APPROVAL_SVC] Found approval: ${approval.id}, status=${approval.status}")

        if (approval.status != "PENDING") {
            throw IllegalStateException("Approval request is not pending: ${approval.status}")
        }

        approval.status = "APPROVED"
        approval.completedAt = LocalDateTime.now()

        Log.info("✅ [APPROVAL_SVC] Updated approval status to APPROVED")

        return approval
    }

    /**
     * Reject a pending approval request
     */
    @Transactional
    fun reject(approvalId: String, reason: String? = null): Boolean {
        val approval = ApprovalRequest.findById(approvalId)
            ?: throw IllegalArgumentException("Approval request not found: $approvalId")

        if (approval.status != "PENDING") {
            throw IllegalStateException("Approval request is not pending: ${approval.status}")
        }

        approval.status = "REJECTED"
        approval.completedAt = LocalDateTime.now()

        Log.info("❌ Rejected request $approvalId for exchange ${approval.exchangeId}")

        // Complete the future with rejection to unblock the waiting exchange
        val future = pendingApprovals.remove(approvalId)
        future?.completeExceptionally(ApprovalRejectedException(reason ?: "Approval rejected"))

        // Broadcast rejection event
        broadcaster.broadcast(RouteEvent(
            type = "APPROVAL_REJECTED",
            routeId = approval.routeId,
            exchangeId = approval.exchangeId,
            data = mapOf("approvalId" to approvalId, "reason" to (reason ?: ""))
        ))

        // Mark the exchange as failed
        exchangeStateManager.failExchange(approval.exchangeId, "Approval rejected: ${reason ?: "No reason provided"}")

        return true
    }

    /**
     * Get all pending approval requests
     */
    fun getPendingApprovals(): List<ApprovalRequest> {
        return ApprovalRequest.findPending()
    }

    /**
     * Get approval request by ID
     */
    fun getApprovalById(approvalId: String): ApprovalRequest? {
        return ApprovalRequest.findById(approvalId)
    }

    /**
     * Resume pending approvals after crash recovery.
     * Called by CrashRecoveryService on startup.
     */
    fun restorePendingApprovals() {
        val pending = ApprovalRequest.findPending()

        pending.forEach { approval ->
            // Recreate the CompletableFuture for each pending approval
            val future = CompletableFuture<ApprovalDecision>()
            pendingApprovals[approval.id] = future

            Log.info("🔄 Restored pending approval ${approval.id} for exchange ${approval.exchangeId}")
        }

        Log.info("🔄 Restored ${pending.size} pending approvals")
    }
}

/**
 * Represents the decision from an approval request
 */
data class ApprovalDecision(
    val approved: Boolean,
    val response: String? = null
)

/**
 * Exception thrown when an approval is rejected
 */
class ApprovalRejectedException(message: String) : Exception(message)

/**
 * Exception thrown when an approval times out
 */
class ApprovalTimeoutException(message: String) : Exception(message)
