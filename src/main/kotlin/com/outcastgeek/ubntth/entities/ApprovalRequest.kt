package com.outcastgeek.ubntth.entities

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.*

/**
 * Represents a human-in-the-loop approval request.
 * Simplified version with only basic approve/reject functionality.
 */
@Entity
@Table(name = "approval_requests")
class ApprovalRequest(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "exchange_id", nullable = false)
    var exchangeId: String,

    @Column(name = "route_id", nullable = false)
    var routeId: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var payload: String,

    @Column(nullable = false)
    var status: String = "PENDING", // PENDING, APPROVED, REJECTED

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null
) : PanacheEntityBase {

    companion object : PanacheCompanion<ApprovalRequest> {
        /**
         * Find all pending approval requests, ordered by creation time
         */
        fun findPending(): List<ApprovalRequest> =
            list("status = ?1 ORDER BY createdAt ASC", "PENDING")

        /**
         * Find approval request by ID (custom String-based ID)
         */
        fun findByApprovalId(id: String): ApprovalRequest? =
            find("id", id).firstResult()

        /**
         * Alias for findByApprovalId
         */
        fun findById(id: String): ApprovalRequest? =
            findByApprovalId(id)

        /**
         * Find approval request by exchange ID
         */
        fun findByExchangeId(exchangeId: String): ApprovalRequest? =
            find("exchangeId", exchangeId).firstResult()
    }
}
