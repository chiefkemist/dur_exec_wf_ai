package com.outcastgeek.ubntth.entities

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Audit trail for route events.
 * Useful for debugging and learning purposes.
 */
@Entity
@Table(name = "route_logs")
class RouteLog(
    // Use IDENTITY strategy (SQLite AUTOINCREMENT) instead of table-based sequences
    // This prevents SQLITE_BUSY errors from concurrent hibernate_sequences access
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "route_id", nullable = false)
    var routeId: String = "",

    @Column(name = "exchange_id", nullable = false)
    var exchangeId: String = "",

    @Column(name = "event_type", nullable = false)
    var eventType: String = "",

    @Column(columnDefinition = "TEXT")
    var message: String? = null,

    @Column(nullable = false)
    var timestamp: LocalDateTime = LocalDateTime.now()
) : PanacheEntityBase {

    companion object : PanacheCompanion<RouteLog> {
        /**
         * Log a route event
         * Note: Caller must ensure active transaction context
         */
        fun logEvent(
            routeId: String,
            exchangeId: String,
            eventType: String,
            message: String? = null
        ) {
            RouteLog(
                routeId = routeId,
                exchangeId = exchangeId,
                eventType = eventType,
                message = message
            ).persist()
        }

        /**
         * Get recent logs for a route
         */
        fun getRecentLogs(routeId: String, limit: Int = 100): List<RouteLog> =
            find("routeId = ?1 ORDER BY timestamp DESC", routeId)
                .page(0, limit)
                .list()

        /**
         * Get logs for a specific exchange
         */
        fun getExchangeLogs(exchangeId: String): List<RouteLog> =
            list("exchangeId = ?1 ORDER BY timestamp ASC", exchangeId)
    }
}
