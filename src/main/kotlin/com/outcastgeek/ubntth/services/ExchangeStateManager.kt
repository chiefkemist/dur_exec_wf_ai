package com.outcastgeek.ubntth.services

import com.outcastgeek.ubntth.entities.ExchangeState
import com.outcastgeek.ubntth.entities.ExchangeStatus
import com.outcastgeek.ubntth.entities.ExchangeCheckpoint
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import jakarta.transaction.Transactional
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime

/**
 * Core service for managing durable exchange execution.
 * Handles creation, checkpointing, pause/resume, and lifecycle management.
 */
@ApplicationScoped
@Named("exchangeStateManager")
class ExchangeStateManager(
    private val broadcaster: EventBroadcaster
) {

    /**
     * Create a new exchange state (entry point for durable execution)
     * Note: Uses regular @Transactional (not REQUIRES_NEW) for SQLite compatibility
     */
    @Transactional
    fun createExchange(
        exchangeId: String,
        routeId: String,
        payload: String,
        context: Map<String, Any> = emptyMap()
    ): ExchangeState {
        val state = ExchangeState(
            exchangeId = exchangeId,
            routeId = routeId,
            payload = payload,
            context = Json.encodeToString(JsonObject.serializer(),
                JsonObject(context.mapValues { JsonPrimitive(it.value.toString()) }))
        )
        state.persist()

        Log.info("📝 Created durable exchange state: $exchangeId")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_CREATED",
            routeId = routeId,
            exchangeId = exchangeId
        ))

        return state
    }

    /**
     * Start processing an exchange (PENDING → RUNNING)
     */
    @Transactional
    fun startExchange(exchangeId: String) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        if (state.status != ExchangeStatus.PENDING && state.status != ExchangeStatus.WAITING_APPROVAL) {
            // Allow restart from WAITING_APPROVAL after approval
            if (state.status != ExchangeStatus.PAUSED) {
                throw IllegalStateException("Exchange is not pending: ${state.status}")
            }
        }

        state.status = ExchangeStatus.RUNNING
        state.startedAt = state.startedAt ?: LocalDateTime.now()

        Log.info("▶️  Started exchange: $exchangeId")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_STARTED",
            routeId = state.routeId,
            exchangeId = exchangeId
        ))
    }

    /**
     * Save checkpoint (critical for crash recovery)
     * Includes retry logic for SQLite busy errors and idempotency check
     * Note: Uses regular @Transactional (not REQUIRES_NEW) for SQLite compatibility
     *
     * @return true if checkpoint was created, false if it already existed (idempotent skip)
     */
    @Transactional
    fun checkpoint(exchangeId: String, stepIndex: Int, stepName: String, stepData: String? = null): Boolean {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        // Idempotency check: if this step was already checkpointed, skip it
        if (ExchangeCheckpoint.hasCheckpoint(exchangeId, stepName)) {
            Log.info("⏭️ Idempotent skip: Checkpoint '$stepName' already exists for exchange $exchangeId")
            return false
        }

        state.currentStep = stepIndex
        state.currentStepName = stepName
        state.lastCheckpoint = LocalDateTime.now()

        // Retry logic for SQLite busy errors
        var retries = 3
        var lastError: Exception? = null
        var created = false
        while (retries > 0) {
            try {
                created = ExchangeCheckpoint.logCheckpoint(exchangeId, stepIndex, stepName, stepData)
                break
            } catch (e: Exception) {
                lastError = e
                if (e.message?.contains("SQLITE_BUSY") == true || e.cause?.message?.contains("SQLITE_BUSY") == true) {
                    retries--
                    if (retries > 0) {
                        Log.warn("SQLite busy, retrying checkpoint... ($retries retries left)")
                        Thread.sleep(100) // Brief wait before retry
                    }
                } else {
                    throw e
                }
            }
        }
        if (retries == 0 && lastError != null) {
            throw lastError
        }

        if (created) {
            Log.debug("✓ Checkpoint saved: $exchangeId at step $stepIndex ($stepName)")

            broadcaster.broadcast(RouteEvent(
                type = "EXCHANGE_CHECKPOINT",
                routeId = state.routeId,
                exchangeId = exchangeId,
                data = mapOf("step" to stepIndex.toString(), "stepName" to stepName)
            ))
        }

        return created
    }

    /**
     * Pause an exchange (can be resumed later)
     */
    @Transactional
    fun pauseExchange(exchangeId: String) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        if (state.status != ExchangeStatus.RUNNING) {
            throw IllegalStateException("Can only pause running exchanges")
        }

        state.status = ExchangeStatus.PAUSED

        Log.info("⏸️  Paused exchange: $exchangeId at step ${state.currentStep}")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_PAUSED",
            routeId = state.routeId,
            exchangeId = exchangeId
        ))
    }

    /**
     * Resume a paused exchange
     */
    @Transactional
    fun resumeExchange(exchangeId: String) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        if (state.status != ExchangeStatus.PAUSED) {
            throw IllegalStateException("Can only resume paused exchanges")
        }

        state.status = ExchangeStatus.RUNNING

        Log.info("▶️  Resumed exchange: $exchangeId from step ${state.currentStep}")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_RESUMED",
            routeId = state.routeId,
            exchangeId = exchangeId
        ))
    }

    /**
     * Cancel an exchange (terminal state)
     */
    @Transactional
    fun cancelExchange(exchangeId: String) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        if (state.status == ExchangeStatus.COMPLETED || state.status == ExchangeStatus.FAILED) {
            throw IllegalStateException("Cannot cancel completed/failed exchange")
        }

        state.status = ExchangeStatus.CANCELLED
        state.completedAt = LocalDateTime.now()

        Log.info("❌ Cancelled exchange: $exchangeId")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_CANCELLED",
            routeId = state.routeId,
            exchangeId = exchangeId
        ))
    }

    /**
     * Mark exchange as waiting for approval
     */
    @Transactional
    fun markWaitingApproval(exchangeId: String) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        state.status = ExchangeStatus.WAITING_APPROVAL

        Log.info("⏳ Exchange waiting for approval: $exchangeId")
    }

    /**
     * Resume an exchange after approval is granted
     */
    @Transactional
    fun resumeAfterApproval(exchangeId: String) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        if (state.status != ExchangeStatus.WAITING_APPROVAL) {
            throw IllegalStateException("Exchange is not waiting for approval: ${state.status}")
        }

        state.status = ExchangeStatus.RUNNING

        Log.info("▶️  Resumed exchange after approval: $exchangeId")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_RESUMED",
            routeId = state.routeId,
            exchangeId = exchangeId
        ))
    }

    /**
     * Complete an exchange successfully
     */
    @Transactional
    fun completeExchange(exchangeId: String, result: String? = null) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        state.status = ExchangeStatus.COMPLETED
        state.completedAt = LocalDateTime.now()

        // Store the final result in the context field
        if (result != null) {
            state.context = result
        }

        Log.info("✅ Completed exchange: $exchangeId")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_COMPLETED",
            routeId = state.routeId,
            exchangeId = exchangeId
        ))
    }

    /**
     * Mark exchange as failed
     */
    @Transactional
    fun failExchange(exchangeId: String, error: String) {
        val state = ExchangeState.findById(exchangeId)
            ?: throw IllegalArgumentException("Exchange not found: $exchangeId")

        state.status = ExchangeStatus.FAILED
        state.completedAt = LocalDateTime.now()

        Log.error("❌ Failed exchange: $exchangeId - $error")

        broadcaster.broadcast(RouteEvent(
            type = "EXCHANGE_FAILED",
            routeId = state.routeId,
            exchangeId = exchangeId,
            data = mapOf("error" to error)
        ))
    }

    /**
     * Check if exchange should continue (not paused/cancelled)
     */
    @Transactional
    fun shouldContinue(exchangeId: String): Boolean {
        val state = ExchangeState.findById(exchangeId) ?: return false
        return state.status == ExchangeStatus.RUNNING || state.status == ExchangeStatus.WAITING_APPROVAL
    }

    /**
     * Get the current status of an exchange (for checking if it exists)
     */
    @Transactional
    fun getExchangeStatus(exchangeId: String): ExchangeStatus? {
        return ExchangeState.findById(exchangeId)?.status
    }
}
