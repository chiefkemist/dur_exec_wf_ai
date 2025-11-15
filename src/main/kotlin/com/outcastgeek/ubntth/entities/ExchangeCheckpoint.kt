package com.outcastgeek.ubntth.entities

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Records step-by-step progress of an exchange through a route.
 * Used for detailed tracking and crash recovery.
 */
@Entity
@Table(name = "exchange_checkpoints")
class ExchangeCheckpoint(
    // Use IDENTITY strategy (SQLite AUTOINCREMENT) instead of table-based sequences
    // This prevents SQLITE_BUSY errors from concurrent hibernate_sequences access
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "exchange_id", nullable = false)
    var exchangeId: String = "",

    @Column(name = "step_index", nullable = false)
    var stepIndex: Int = 0,

    @Column(name = "step_name", nullable = false)
    var stepName: String = "",

    @Column(name = "step_data", columnDefinition = "TEXT")
    var stepData: String? = null,

    @Column(nullable = false)
    var timestamp: LocalDateTime = LocalDateTime.now()
) : PanacheEntityBase {

    companion object : PanacheCompanion<ExchangeCheckpoint> {
        /**
         * Log a checkpoint for an exchange with idempotency check.
         * If a checkpoint with the same exchangeId and stepName already exists,
         * it will be skipped (idempotent operation).
         * Note: Caller must ensure active transaction context
         *
         * @return true if checkpoint was created, false if it already existed (idempotent skip)
         */
        fun logCheckpoint(
            exchangeId: String,
            stepIndex: Int,
            stepName: String,
            stepData: String? = null
        ): Boolean {
            // Idempotency check: Skip if this exact step was already checkpointed
            val exists = count("exchangeId = ?1 AND stepName = ?2", exchangeId, stepName) > 0
            if (exists) {
                // Idempotent: checkpoint already exists, skip creation
                return false
            }
            ExchangeCheckpoint(
                exchangeId = exchangeId,
                stepIndex = stepIndex,
                stepName = stepName,
                stepData = stepData
            ).persist()
            return true
        }

        /**
         * Check if a specific step has already been checkpointed (for idempotency)
         */
        fun hasCheckpoint(exchangeId: String, stepName: String): Boolean =
            count("exchangeId = ?1 AND stepName = ?2", exchangeId, stepName) > 0

        /**
         * Get all checkpoints for an exchange, ordered by step
         */
        fun getCheckpoints(exchangeId: String): List<ExchangeCheckpoint> =
            list("exchangeId = ?1 ORDER BY stepIndex", exchangeId)

        /**
         * Alias for getCheckpoints
         */
        fun findByExchangeId(exchangeId: String): List<ExchangeCheckpoint> =
            getCheckpoints(exchangeId)

        /**
         * Get the most recent checkpoint for an exchange
         */
        fun getLatestCheckpoint(exchangeId: String): ExchangeCheckpoint? =
            find("exchangeId = ?1 ORDER BY stepIndex DESC", exchangeId).firstResult()

        /**
         * Get checkpoint by exchange and step name
         */
        fun findByExchangeAndStep(exchangeId: String, stepName: String): ExchangeCheckpoint? =
            find("exchangeId = ?1 AND stepName = ?2", exchangeId, stepName).firstResult()
    }
}
