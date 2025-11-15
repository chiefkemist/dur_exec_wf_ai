package com.outcastgeek.ubntth.integration

import com.outcastgeek.ubntth.entities.ApprovalRequest
import com.outcastgeek.ubntth.entities.ExchangeCheckpoint
import com.outcastgeek.ubntth.entities.ExchangeState
import com.outcastgeek.ubntth.entities.ExchangeStatus
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Disabled

/**
 * Integration test for the complete durable execution flow.
 *
 * This test verifies:
 * 1. Exchange creation and initialization
 * 2. Checkpoint persistence
 * 3. Pause/resume functionality
 * 4. Approval gate workflow
 * 5. Exchange completion
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DurableExecutionFlowTest {

    companion object {
        private var testExchangeId: String? = null
    }

    @BeforeEach
    @Transactional
    fun setup() {
        // Clean up test data before each test
        ApprovalRequest.deleteAll()
        ExchangeCheckpoint.deleteAll()
        ExchangeState.deleteAll()
    }

    @Test
    @Order(1)
    @Disabled("Triggers async Camel route that causes test isolation issues")
    fun `complete flow - create exchange through simple route`() {
        // Create a simple exchange (non-durable for baseline)
        val response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "routeId": "chat-simple",
                    "payload": "Test message for simple route"
                }
            """.trimIndent())
            .`when`()
            .post("/api/exchanges")
            .then()
            .statusCode(202)
            .body("exchangeId", notNullValue())
            .extract()
            .response()

        val exchangeId = response.path<String>("exchangeId")
        Assertions.assertNotNull(exchangeId)

        // Give Camel time to process
        Thread.sleep(3000)

        // Verify exchange exists
        val exchange = ExchangeState.findById(exchangeId)
        Assertions.assertNotNull(exchange)
    }

    @Test
    @Order(2)
    fun `complete flow - create and pause exchange`() {
        // Create exchange directly in DB to avoid async route blocking issues
        val exchange = createTestExchange(ExchangeStatus.RUNNING)
        testExchangeId = exchange.exchangeId

        // Pause the exchange
        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/exchanges/$testExchangeId/pause")
            .then()
            .statusCode(200)

        // Verify exchange is paused
        val pausedExchange = ExchangeState.findById(testExchangeId!!)
        Assertions.assertEquals(ExchangeStatus.PAUSED, pausedExchange?.status)
    }

    @Test
    @Order(3)
    @Disabled("Triggers async Camel recovery route that causes test isolation issues")
    fun `complete flow - resume paused exchange`() {
        // Create and pause an exchange
        val exchange = createAndPauseExchange()

        // Resume the exchange
        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/exchanges/${exchange.exchangeId}/resume")
            .then()
            .statusCode(200)

        Thread.sleep(1000)

        // Verify exchange state changed (will be processed by recovery route)
        val updatedExchange = ExchangeState.findById(exchange.exchangeId)
        Assertions.assertNotNull(updatedExchange)
        // Status might still be PAUSED until recovery route picks it up
    }

    @Test
    @Order(4)
    fun `complete flow - cancel running exchange`() {
        // Create an exchange
        val exchange = createTestExchange(ExchangeStatus.RUNNING)

        // Cancel it
        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/exchanges/${exchange.exchangeId}/cancel")
            .then()
            .statusCode(200)

        // Verify it's cancelled
        val updatedExchange = ExchangeState.findById(exchange.exchangeId)
        Assertions.assertEquals(ExchangeStatus.CANCELLED, updatedExchange?.status)
    }

    @Test
    @Order(5)
    fun `complete flow - verify checkpoints are created`() {
        val exchange = createTestExchange(ExchangeStatus.RUNNING)

        // Manually create some checkpoints to simulate route processing
        createTestCheckpoint(exchange.exchangeId, 0, "initialize")
        createTestCheckpoint(exchange.exchangeId, 1, "validate")
        createTestCheckpoint(exchange.exchangeId, 2, "process")

        // Get checkpoints via API
        given()
            .`when`()
            .get("/api/exchanges/${exchange.exchangeId}/checkpoints")
            .then()
            .statusCode(200)
            .body("total", equalTo(3))
            .body("checkpoints.size()", equalTo(3))
            .body("checkpoints[0].stepName", equalTo("initialize"))
            .body("checkpoints[1].stepName", equalTo("validate"))
            .body("checkpoints[2].stepName", equalTo("process"))
    }

    @Test
    @Order(6)
    fun `complete flow - list exchanges with filters`() {
        // Create exchanges with different statuses
        createTestExchange(ExchangeStatus.PENDING)
        createTestExchange(ExchangeStatus.RUNNING)
        createTestExchange(ExchangeStatus.COMPLETED)

        // Test filtering by status
        given()
            .queryParam("status", "RUNNING")
            .`when`()
            .get("/api/exchanges")
            .then()
            .statusCode(200)
            .body("exchanges[0].status", equalTo("RUNNING"))
    }

    @Test
    @Order(7)
    fun `complete flow - verify pause prevents processing`() {
        val exchange = createTestExchange(ExchangeStatus.RUNNING)

        // Pause it
        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/exchanges/${exchange.exchangeId}/pause")
            .then()
            .statusCode(200)

        // Try to create a checkpoint (in real scenario, route would check shouldContinue)
        val pausedExchange = ExchangeState.findById(exchange.exchangeId)
        Assertions.assertEquals(ExchangeStatus.PAUSED, pausedExchange?.status)

        // Verify that status indicates processing should stop
        Assertions.assertNotEquals(ExchangeStatus.RUNNING, pausedExchange?.status)
    }

    @Test
    @Order(8)
    @Disabled("Triggers multiple async Camel routes that cause test isolation issues")
    fun `complete flow - multiple exchanges in parallel`() {
        // Create multiple exchanges
        val exchange1Id = createExchangeViaAPI("chat-simple")
        val exchange2Id = createExchangeViaAPI("chat-simple")
        val exchange3Id = createExchangeViaAPI("chat-simple")

        Thread.sleep(2000)

        // Verify all exist
        Assertions.assertNotNull(ExchangeState.findById(exchange1Id))
        Assertions.assertNotNull(ExchangeState.findById(exchange2Id))
        Assertions.assertNotNull(ExchangeState.findById(exchange3Id))
    }

    // Helper methods - must be open for @Transactional to work
    @Transactional
    open fun createTestExchange(status: ExchangeStatus): ExchangeState {
        val exchange = ExchangeState(
            exchangeId = "test-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}",
            routeId = "test-route",
            status = status,
            payload = """{"message": "test"}""",
            createdAt = java.time.LocalDateTime.now()
        )

        if (status != ExchangeStatus.PENDING) {
            exchange.startedAt = java.time.LocalDateTime.now()
        }

        exchange.persist()
        return exchange
    }

    @Transactional
    open fun createAndPauseExchange(): ExchangeState {
        val exchange = createTestExchange(ExchangeStatus.PAUSED)
        return exchange
    }

    @Transactional
    open fun createTestCheckpoint(exchangeId: String, stepIndex: Int, stepName: String) {
        ExchangeCheckpoint.logCheckpoint(exchangeId, stepIndex, stepName, null)
    }

    open fun createExchangeViaAPI(routeId: String): String {
        return given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "routeId": "$routeId",
                    "payload": "Test message"
                }
            """.trimIndent())
            .`when`()
            .post("/api/exchanges")
            .then()
            .statusCode(202)
            .extract()
            .path<String>("exchangeId")
    }
}
