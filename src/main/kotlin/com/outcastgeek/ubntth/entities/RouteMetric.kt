package com.outcastgeek.ubntth.entities

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Tracks aggregate metrics for a route.
 * Used for dashboard display and monitoring.
 */
@Entity
@Table(name = "route_metrics")
class RouteMetric(
    @Id
    @Column(name = "route_id")
    var routeId: String,

    @Column(nullable = false)
    var status: String = "RUNNING",

    @Column(name = "total_count")
    var totalCount: Long = 0,

    @Column(name = "success_count")
    var successCount: Long = 0,

    @Column(name = "failure_count")
    var failureCount: Long = 0,

    @Column(name = "last_updated")
    var lastUpdated: LocalDateTime = LocalDateTime.now()
) : PanacheEntityBase {

    companion object : PanacheCompanion<RouteMetric> {
        /**
         * Find or create a route metric entry
         * Note: Caller must ensure active transaction context if creating
         */
        fun findOrCreate(routeId: String): RouteMetric {
            return find("routeId", routeId).firstResult()
                ?: RouteMetric(routeId = routeId).also { it.persist() }
        }

        /**
         * Find all route metrics as a list
         */
        fun findAllMetrics(): List<RouteMetric> = listAll()
    }
}
