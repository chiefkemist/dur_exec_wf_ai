package com.outcastgeek.ubntth.resources

import com.outcastgeek.ubntth.entities.ApprovalRequest
import com.outcastgeek.ubntth.entities.ExchangeState
import com.outcastgeek.ubntth.entities.ExchangeStatus
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.*
import org.junit.jupiter.api.*
import java.time.LocalDateTime

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApprovalResourceTest {

    @BeforeEach
    @Transactional
    fun setup() {
        // Clean up test data
        ApprovalRequest.deleteAll()
        ExchangeState.deleteAll()
    }

    @Test
    @Order(1)
    fun `should list all pending approvals`() {
        // Create test approvals
        createTestApproval("exchange-1", "PENDING")
        createTestApproval("exchange-2", "PENDING")
        createTestApproval("exchange-3", "APPROVED") // Should not be in list

        given()
            .`when`()
            .get("/api/approvals")
            .then()
            .statusCode(200)
            .body("total", equalTo(2))
            .body("approvals.size()", equalTo(2))
            .body("approvals[0].status", equalTo("PENDING"))
            .body("approvals[1].status", equalTo("PENDING"))
    }

    @Test
    @Order(2)
    fun `should get approval by ID`() {
        val approval = createTestApproval("exchange-1", "PENDING")

        given()
            .`when`()
            .get("/api/approvals/${approval.id}")
            .then()
            .statusCode(200)
            .body("id", equalTo(approval.id))
            .body("exchangeId", equalTo(approval.exchangeId))
            .body("status", equalTo("PENDING"))
    }

    @Test
    @Order(3)
    fun `should return 404 for non-existent approval`() {
        given()
            .`when`()
            .get("/api/approvals/non-existent-id")
            .then()
            .statusCode(404)
            .body("error", containsString("Approval not found"))
    }

    @Test
    @Order(4)
    fun `should approve a pending approval request`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.WAITING_APPROVAL)
        val approval = createTestApproval(exchange.exchangeId, "PENDING")

        given()
            .contentType(ContentType.JSON)
            .body("""{"response": "Approved by tester"}""")
            .`when`()
            .post("/api/approvals/${approval.id}/approve")
            .then()
            .statusCode(200)
            .body("message", equalTo("Approval granted successfully"))

        // Verify approval status changed
        val updatedApproval = ApprovalRequest.findById(approval.id)
        Assertions.assertEquals("APPROVED", updatedApproval?.status)
        Assertions.assertNotNull(updatedApproval?.completedAt)
    }

    @Test
    @Order(5)
    fun `should reject a pending approval request`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.WAITING_APPROVAL)
        val approval = createTestApproval(exchange.exchangeId, "PENDING")

        given()
            .contentType(ContentType.JSON)
            .body("""{"reason": "Rejected for testing"}""")
            .`when`()
            .post("/api/approvals/${approval.id}/reject")
            .then()
            .statusCode(200)
            .body("message", equalTo("Approval rejected successfully"))
            .body("reason", equalTo("Rejected for testing"))

        // Verify approval status changed
        val updatedApproval = ApprovalRequest.findById(approval.id)
        Assertions.assertEquals("REJECTED", updatedApproval?.status)
        Assertions.assertNotNull(updatedApproval?.completedAt)
    }

    @Test
    @Order(6)
    fun `should fail to approve already approved request`() {
        val approval = createTestApproval("exchange-1", "APPROVED")

        given()
            .contentType(ContentType.JSON)
            .body("""{"response": "Trying to approve again"}""")
            .`when`()
            .post("/api/approvals/${approval.id}/approve")
            .then()
            .statusCode(400)
            .body("error", containsString("not pending"))
    }

    @Test
    @Order(7)
    fun `should fail to reject already rejected request`() {
        val approval = createTestApproval("exchange-1", "REJECTED")

        given()
            .contentType(ContentType.JSON)
            .body("""{"reason": "Trying to reject again"}""")
            .`when`()
            .post("/api/approvals/${approval.id}/reject")
            .then()
            .statusCode(400)
            .body("error", containsString("not pending"))
    }

    @Test
    @Order(8)
    fun `should get approval by exchange ID`() {
        val exchangeId = "test-exchange-123"
        val approval = createTestApproval(exchangeId, "PENDING")

        given()
            .`when`()
            .get("/api/approvals/by-exchange/$exchangeId")
            .then()
            .statusCode(200)
            .body("id", equalTo(approval.id))
            .body("exchangeId", equalTo(exchangeId))
    }

    @Test
    @Order(9)
    fun `should return 404 when no approval found for exchange`() {
        given()
            .`when`()
            .get("/api/approvals/by-exchange/non-existent-exchange")
            .then()
            .statusCode(404)
            .body("error", containsString("No approval found"))
    }

    @Test
    @Order(10)
    fun `should approve without response body`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.WAITING_APPROVAL)
        val approval = createTestApproval(exchange.exchangeId, "PENDING")

        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/approvals/${approval.id}/approve")
            .then()
            .statusCode(200)
            .body("message", equalTo("Approval granted successfully"))
    }

    @Test
    @Order(11)
    fun `should reject without reason`() {
        val exchange = createTestExchange("test-route", ExchangeStatus.WAITING_APPROVAL)
        val approval = createTestApproval(exchange.exchangeId, "PENDING")

        given()
            .contentType(ContentType.JSON)
            .`when`()
            .post("/api/approvals/${approval.id}/reject")
            .then()
            .statusCode(200)
            .body("message", equalTo("Approval rejected successfully"))
    }

    // Helper methods - must be open for @Transactional to work
    @Transactional
    open fun createTestApproval(exchangeId: String, status: String): ApprovalRequest {
        val approval = ApprovalRequest(
            exchangeId = exchangeId,
            routeId = "test-route",
            payload = """{"test": "approval"}""",
            status = status,
            createdAt = LocalDateTime.now()
        )

        if (status != "PENDING") {
            approval.completedAt = LocalDateTime.now()
        }

        approval.persist()
        return approval
    }

    @Transactional
    open fun createTestExchange(routeId: String, status: ExchangeStatus): ExchangeState {
        val exchange = ExchangeState(
            exchangeId = "test-exchange-${System.currentTimeMillis()}",
            routeId = routeId,
            status = status,
            payload = """{"test": "data"}""",
            createdAt = LocalDateTime.now()
        )
        exchange.persist()
        return exchange
    }
}
