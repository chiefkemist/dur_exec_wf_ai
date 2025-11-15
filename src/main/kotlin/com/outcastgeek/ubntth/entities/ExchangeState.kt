package com.outcastgeek.ubntth.entities

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Core entity for durable execution.
 * Represents the persisted state of a Camel exchange that can survive restarts.
 */
@Entity
@Table(name = "exchange_state")
class ExchangeState(
    @Id
    @Column(name = "exchange_id")
    var exchangeId: String,

    @Column(name = "route_id", nullable = false)
    var routeId: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: ExchangeStatus = ExchangeStatus.PENDING,

    @Column(name = "current_step")
    var currentStep: Int = 0,

    @Column(name = "current_step_name")
    var currentStepName: String? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    var payload: String,

    @Column(columnDefinition = "TEXT")
    var context: String? = null, // JSON of headers/properties

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "started_at")
    var startedAt: LocalDateTime? = null,

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @Column(name = "last_checkpoint")
    var lastCheckpoint: LocalDateTime = LocalDateTime.now()
) : PanacheEntityBase {

    companion object : PanacheCompanion<ExchangeState> {
        /**
         * Find all exchanges with a specific status
         */
        fun findByStatus(status: ExchangeStatus): List<ExchangeState> =
            list("status", status)

        /**
         * Find all currently running exchanges (for crash recovery)
         */
        fun findRunning(): List<ExchangeState> =
            findByStatus(ExchangeStatus.RUNNING)

        /**
         * Find all paused exchanges
         */
        fun findPaused(): List<ExchangeState> =
            findByStatus(ExchangeStatus.PAUSED)

        /**
         * Find exchange by ID (custom String-based ID)
         */
        fun findByExchangeId(exchangeId: String): ExchangeState? =
            find("exchangeId", exchangeId).firstResult()

        /**
         * Alias for findByExchangeId
         */
        fun findById(exchangeId: String): ExchangeState? =
            findByExchangeId(exchangeId)

        /**
         * Find exchanges by route ID
         */
        fun findByRouteId(routeId: String): List<ExchangeState> =
            list("routeId", routeId)

        /**
         * Find all exchanges sorted by creation date (most recent first)
         */
        fun findAllSorted(): List<ExchangeState> =
            listAll(io.quarkus.panache.common.Sort.descending("createdAt"))
    }
}

/**
 * Exchange lifecycle states for durable execution
 */
enum class ExchangeStatus {
    PENDING,          // Created, waiting to start
    RUNNING,          // Currently processing
    PAUSED,           // User paused execution
    WAITING_APPROVAL, // Blocked at approval gate
    CANCELLED,        // User cancelled
    COMPLETED,        // Successfully finished
    FAILED            // Error occurred
}
