package com.outcastgeek.ubntth.resources

import com.outcastgeek.ubntth.entities.RouteLog
import com.outcastgeek.ubntth.entities.RouteMetric
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.*
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.*
import java.time.LocalDateTime

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RouteResourceTest {

    @BeforeEach
    @Transactional
    fun setup() {
        // Clean up test data
        RouteLog.deleteAll()
        RouteMetric.deleteAll()
    }

    @Test
    @Order(1)
    fun `should list all Camel routes`() {
        given()
            .`when`()
            .get("/api/routes")
            .then()
            .statusCode(200)
            .body("routes", notNullValue())
            .body("total", greaterThan(0))
            .body("routes[0].routeId", notNullValue())
            .body("routes[0].status", notNullValue())
    }

    @Test
    @Order(2)
    fun `should verify durable route exists in routes list`() {
        given()
            .`when`()
            .get("/api/routes")
            .then()
            .statusCode(200)
            .body("routes.routeId", hasItem("chat-durable-route"))
    }

    @Test
    @Order(3)
    fun `should get metrics for a specific route`() {
        val routeId = "test-route"
        createTestMetric(routeId, 10, 8, 2)

        given()
            .`when`()
            .get("/api/routes/$routeId/metrics")
            .then()
            .statusCode(200)
            .body("routeId", equalTo(routeId))
            .body("metrics.totalCount", equalTo(10))
            .body("metrics.successCount", equalTo(8))
            .body("metrics.failureCount", equalTo(2))
    }

    @Test
    @Order(4)
    fun `should get or create metrics for non-existent route`() {
        val routeId = "new-test-route"

        given()
            .`when`()
            .get("/api/routes/$routeId/metrics")
            .then()
            .statusCode(200)
            .body("routeId", equalTo(routeId))
            .body("metrics.totalCount", equalTo(0))
            .body("metrics.successCount", equalTo(0))
            .body("metrics.failureCount", equalTo(0))
    }

    @Test
    @Order(5)
    fun `should get logs for a specific route`() {
        val routeId = "test-route"
        val exchangeId = "test-exchange-1"

        // Create test logs
        createTestLog(routeId, exchangeId, "CHAT_REQUEST", "Test request 1")
        createTestLog(routeId, exchangeId, "CHAT_RESPONSE", "Test response 1")
        createTestLog(routeId, "test-exchange-2", "CHAT_REQUEST", "Test request 2")

        given()
            .`when`()
            .get("/api/routes/$routeId/logs")
            .then()
            .statusCode(200)
            .body("routeId", equalTo(routeId))
            .body("logs.size()", equalTo(3))
            .body("total", equalTo(3))
    }

    @Test
    @Order(6)
    fun `should respect logs limit parameter`() {
        val routeId = "test-route"

        // Create multiple logs
        repeat(10) { i ->
            createTestLog(routeId, "exchange-$i", "TEST_EVENT", "Message $i")
        }

        given()
            .queryParam("limit", 5)
            .`when`()
            .get("/api/routes/$routeId/logs")
            .then()
            .statusCode(200)
            .body("logs.size()", equalTo(5))
    }

    @Test
    @Order(7)
    fun `should get status of a specific route`() {
        // Use a route we know exists from ChatRouteWithDurability
        val routeId = "chat-durable-route"

        given()
            .`when`()
            .get("/api/routes/$routeId/status")
            .then()
            .statusCode(200)
            .body("routeId", equalTo(routeId))
            .body("status", notNullValue())
            .body("description", notNullValue())
    }

    @Test
    @Order(8)
    fun `should return 404 for non-existent route status`() {
        given()
            .`when`()
            .get("/api/routes/non-existent-route/status")
            .then()
            .statusCode(404)
            .body("error", containsString("Route not found"))
    }

    @Test
    @Order(9)
    fun `should get all route metrics`() {
        // Create test metrics for multiple routes
        createTestMetric("route-1", 10, 8, 2)
        createTestMetric("route-2", 20, 18, 2)
        createTestMetric("route-3", 5, 5, 0)

        given()
            .`when`()
            .get("/api/routes/metrics")
            .then()
            .statusCode(200)
            .body("metrics.size()", greaterThanOrEqualTo(3))
            .body("total", greaterThanOrEqualTo(3))
    }

    @Test
    @Order(10)
    fun `should get recovery statistics`() {
        given()
            .`when`()
            .get("/api/routes/recovery-stats")
            .then()
            .statusCode(200)
            .body("recoveryStats.runningExchanges", notNullValue())
            .body("recoveryStats.pausedExchanges", notNullValue())
            .body("recoveryStats.waitingApprovalExchanges", notNullValue())
            .body("recoveryStats.pendingApprovals", notNullValue())
    }

    @Test
    @Order(11)
    fun `should get logs for a specific exchange`() {
        val routeId = "test-route"
        val exchangeId = "specific-exchange-123"

        // Create logs for this exchange
        createTestLog(routeId, exchangeId, "START", "Exchange started")
        createTestLog(routeId, exchangeId, "PROCESS", "Processing")
        createTestLog(routeId, exchangeId, "COMPLETE", "Exchange completed")

        // Create logs for other exchanges
        createTestLog(routeId, "other-exchange", "START", "Other exchange")

        given()
            .`when`()
            .get("/api/routes/logs/exchange/$exchangeId")
            .then()
            .statusCode(200)
            .body("exchangeId", equalTo(exchangeId))
            .body("logs.size()", equalTo(3))
            .body("total", equalTo(3))
    }

    @Test
    @Order(12)
    fun `should return empty list for exchange with no logs`() {
        given()
            .`when`()
            .get("/api/routes/logs/exchange/no-logs-exchange")
            .then()
            .statusCode(200)
            .body("logs.size()", equalTo(0))
            .body("total", equalTo(0))
    }

    // Helper methods
    @Transactional
    internal fun createTestMetric(
        routeId: String,
        total: Long,
        success: Long,
        failure: Long
    ): RouteMetric {
        val metric = RouteMetric(
            routeId = routeId,
            totalCount = total,
            successCount = success,
            failureCount = failure,
            lastUpdated = LocalDateTime.now()
        )
        metric.persist()
        return metric
    }

    @Transactional
    internal fun createTestLog(
        routeId: String,
        exchangeId: String,
        eventType: String,
        message: String
    ): RouteLog {
        val log = RouteLog(
            routeId = routeId,
            exchangeId = exchangeId,
            eventType = eventType,
            message = message,
            timestamp = LocalDateTime.now()
        )
        log.persist()
        return log
    }
}
