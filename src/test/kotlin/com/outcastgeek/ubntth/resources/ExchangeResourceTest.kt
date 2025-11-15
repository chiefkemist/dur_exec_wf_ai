package com.outcastgeek.ubntth.resources

import com.outcastgeek.ubntth.entities.ExchangeState
import com.outcastgeek.ubntth.entities.ExchangeStatus
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.*
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.lessThanOrEqualTo
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Disabled
import java.time.LocalDateTime

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExchangeResourceTest {

    // Use chat-simple route which doesn't have blocking approval gates
    private val testRouteId = "chat-simple"
    private var createdExchangeId: String? = null

    @BeforeEach
    @Transactional
    fun setup() {
        // Clean up any existing test data
        ExchangeState.deleteAll()
    }

    @Test
    @Order(1)
    @Disabled("Triggers async Camel route that causes test isolation issues")
    fun `should create a new exchange`() {
        val payload = """Test message"""

        createdExchangeId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "routeId": "$testRouteId",
                    "payload": "$payload",
                    "headers": {}
                }
            """.trimIndent())
            .`when`()
            .post("/api/exchanges")
            .then()
            .statusCode(202) // Accepted
            .body("routeId", equalTo(testRouteId))
            .body("exchangeId", notNullValue())
            .body("message", equalTo("Exchange created and triggered"))
            .extract()
            .path<String>("exchangeId")

        // Verify exchange was created in database
        Thread.sleep(500) // Give Camel time to process

        val exchange = ExchangeState.findById(createdExchangeId!!)
        Assertions.assertNotNull(exchange)
        Assertions.assertEquals(testRouteId, exchange?.routeId)
    }

    @Test
    @Order(2)
    fun `should list all exchanges`() {
        // Create test exchanges
        createTestExchange("test-route-1", ExchangeStatus.PENDING)
        createTestExchange("test-route-2", ExchangeStatus.RUNNING)
        createTestExchange("test-route-3", ExchangeStatus.COMPLETED)

        given()
            .`when`()
            .get("/api/exchanges")
            .then()
            .statusCode(200)
            .body("total", greaterThanOrEqualTo(3))
            .body("exchanges", notNullValue())
            .body("exchanges.size()", greaterThanOrEqualTo(3))
    }

    @Test
    @Order(3)
    fun `should filter exchanges by status`() {
        // Create test exchanges with different statuses
        createTestExchange("test-route", ExchangeStatus.PENDING)
        createTestExchange("test-route", ExchangeStatus.RUNNING)
        createTestExchange("test-route", ExchangeStatus.COMPLETED)

        given()
            .queryParam("status", "RUNNING")
            .`when`()
            .get("/api/exchanges")
            .then()
            .statusCode(200)
            .body("exchanges.size()", greaterThanOrEqualTo(1))
            .body("exchanges[0].status", equalTo("RUNNING"))
    }

    @Test
    @Order(4)
    fun `should filter exchanges by route ID`() {
        val specificRouteId = "specific-test-route"
        createTestExchange(specificRouteId, ExchangeStatus.PENDING)
        createTestExchange("other-route", ExchangeStatus.PENDING)

        given()
            .queryParam("routeId", specificRouteId)
            .`when`()
            .get("/api/exchanges")
            .then()
            .statusCode(200)
            .body("exchanges.size()", greaterThanOrEqualTo(1))
            .body("exchanges[0].routeId", equalTo(specificRouteId))
    }

    @Test
    @Order(5)
    fun `should get exchange by ID`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.PENDING)

        given()
            .`when`()
            .get("/api/exchanges/${exchange.exchangeId}")
            .then()
            .statusCode(200)
            .body("exchangeId", equalTo(exchange.exchangeId))
            .body("routeId", equalTo(exchange.routeId))
            .body("status", equalTo("PENDING"))
    }

    @Test
    @Order(6)
    fun `should return 404 for non-existent exchange`() {
        given()
            .`when`()
            .get("/api/exchanges/non-existent-id")
            .then()
            .statusCode(404)
            .body("error", containsString("Exchange not found"))
    }

    @Test
    @Order(7)
    fun `should pause a running exchange`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.RUNNING)

        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/exchanges/${exchange.exchangeId}/pause")
            .then()
            .statusCode(200)
            .body("message", equalTo("Exchange paused successfully"))

        // Verify status changed to PAUSED
        val updatedExchange = ExchangeState.findById(exchange.exchangeId)
        Assertions.assertEquals(ExchangeStatus.PAUSED, updatedExchange?.status)
    }

    @Test
    @Order(8)
    @Disabled("Triggers async Camel recovery route that causes test isolation issues")
    fun `should resume a paused exchange`() {
        // Use chat-simple route which doesn't have blocking approval gates
        val exchange = createTestExchange("chat-simple", ExchangeStatus.PAUSED)

        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/exchanges/${exchange.exchangeId}/resume")
            .then()
            .statusCode(200)
            .body("message", equalTo("Exchange resumed successfully"))

        // Note: Status will change when recovery route processes it
    }

    @Test
    @Order(9)
    fun `should cancel an exchange`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.RUNNING)

        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/exchanges/${exchange.exchangeId}/cancel")
            .then()
            .statusCode(200)
            .body("message", equalTo("Exchange cancelled successfully"))

        // Verify status changed to CANCELLED
        val updatedExchange = ExchangeState.findById(exchange.exchangeId)
        Assertions.assertEquals(ExchangeStatus.CANCELLED, updatedExchange?.status)
    }

    @Test
    @Order(10)
    fun `should get checkpoints for an exchange`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.RUNNING)

        // Note: In real scenario, checkpoints would be created by the route
        // For this test, we're just verifying the endpoint works
        given()
            .`when`()
            .get("/api/exchanges/${exchange.exchangeId}/checkpoints")
            .then()
            .statusCode(200)
            .body("exchangeId", equalTo(exchange.exchangeId))
            .body("checkpoints", notNullValue())
            .body("total", equalTo(0)) // No checkpoints created yet
    }

    @Test
    @Order(11)
    fun `should validate pagination parameters`() {
        // Create multiple exchanges
        repeat(10) {
            createTestExchange("test-route", ExchangeStatus.PENDING)
        }

        given()
            .queryParam("limit", 5)
            .queryParam("offset", 0)
            .`when`()
            .get("/api/exchanges")
            .then()
            .statusCode(200)
            .body("limit", equalTo(5))
            .body("offset", equalTo(0))
            .body("exchanges.size()", lessThanOrEqualTo(5))
    }

    // Helper method to create test exchanges
    // Note: Must be open for @Transactional to work
    @Transactional
    open fun createTestExchange(routeId: String, status: ExchangeStatus): ExchangeState {
        val exchange = ExchangeState(
            exchangeId = "test-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}",
            routeId = routeId,
            status = status,
            payload = """{"test": "data"}""",
            createdAt = LocalDateTime.now()
        )

        if (status == ExchangeStatus.RUNNING) {
            exchange.startedAt = LocalDateTime.now()
        } else if (status == ExchangeStatus.COMPLETED) {
            exchange.startedAt = LocalDateTime.now()
            exchange.completedAt = LocalDateTime.now()
        }

        exchange.persist()
        return exchange
    }
}
