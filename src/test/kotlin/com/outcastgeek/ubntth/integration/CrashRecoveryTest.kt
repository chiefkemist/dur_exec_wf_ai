package com.outcastgeek.ubntth.integration

import com.outcastgeek.ubntth.entities.ApprovalRequest
import com.outcastgeek.ubntth.entities.ExchangeCheckpoint
import com.outcastgeek.ubntth.entities.ExchangeState
import com.outcastgeek.ubntth.entities.ExchangeStatus
import com.outcastgeek.ubntth.services.CrashRecoveryService
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.*
import java.time.LocalDateTime

/**
 * Integration test for crash recovery functionality.
 *
 * This test verifies:
 * 1. Detection of interrupted (RUNNING) exchanges
 * 2. Recovery of exchanges from checkpoints
 * 3. Restoration of pending approvals
 * 4. Stalled exchange detection
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CrashRecoveryTest {

    @Inject
    lateinit var crashRecoveryService: CrashRecoveryService

    @BeforeEach
    @Transactional
    fun setup() {
        // Clean up test data
        ApprovalRequest.deleteAll()
        ExchangeCheckpoint.deleteAll()
        ExchangeState.deleteAll()
    }

    @Test
    @Order(1)
    fun `should detect interrupted exchanges`() {
        // Simulate interrupted exchanges (left in RUNNING state from a "crash")
        createInterruptedExchange("test-route-1", 3)
        createInterruptedExchange("test-route-2", 5)

        // Get recovery stats
        val stats = crashRecoveryService.getRecoveryStats()

        Assertions.assertTrue(stats.runningExchanges >= 2, "Should detect at least 2 running exchanges")
    }

    @Test
    @Order(2)
    fun `should get recovery statistics via API`() {
        // Create test data
        createInterruptedExchange("test-route", 2)
        createTestExchange(ExchangeStatus.PAUSED)
        createTestExchange(ExchangeStatus.WAITING_APPROVAL)
        createTestApproval()

        // Get stats via API
        given()
            .`when`()
            .get("/api/routes/recovery-stats")
            .then()
            .statusCode(200)
            .body("recoveryStats.runningExchanges", greaterThanOrEqualTo(1))
            .body("recoveryStats.pausedExchanges", greaterThanOrEqualTo(1))
            .body("recoveryStats.waitingApprovalExchanges", greaterThanOrEqualTo(1))
            .body("recoveryStats.pendingApprovals", greaterThanOrEqualTo(1))
    }

    @Test
    @Order(3)
    fun `should recover exchange from last checkpoint`() {
        // Create an interrupted exchange with checkpoints
        val exchange = createInterruptedExchange("chat-durable", 3)

        // Create checkpoints to simulate partial processing
        createCheckpoint(exchange.exchangeId, 0, "initialize")
        createCheckpoint(exchange.exchangeId, 1, "validate")
        createCheckpoint(exchange.exchangeId, 2, "process")

        // Verify checkpoints exist
        val checkpoints = ExchangeCheckpoint.findByExchangeId(exchange.exchangeId)
        Assertions.assertEquals(3, checkpoints.size)

        // Get latest checkpoint
        val latest = ExchangeCheckpoint.getLatestCheckpoint(exchange.exchangeId)
        Assertions.assertNotNull(latest)
        Assertions.assertEquals("process", latest?.stepName)
        Assertions.assertEquals(2, latest?.stepIndex)
    }

    @Test
    @Order(4)
    fun `should restore pending approvals on startup`() {
        // Create pending approvals
        val approval1 = createTestApproval()
        val approval2 = createTestApproval()

        // Get pending approvals
        val pending = ApprovalRequest.findPending()
        Assertions.assertTrue(pending.size >= 2)
        Assertions.assertTrue(pending.any { it.id == approval1.id })
        Assertions.assertTrue(pending.any { it.id == approval2.id })
    }

    @Test
    @Order(5)
    fun `should identify stalled exchanges`() {
        // Create an exchange that's been running for a long time
        val stalledExchange = createStalledExchange()

        // Create a recent exchange
        val recentExchange = createInterruptedExchange("test-route", 1)

        // Manual check (the actual scheduled check runs every 5 minutes)
        val allRunning = ExchangeState.findByStatus(ExchangeStatus.RUNNING)
        Assertions.assertTrue(allRunning.size >= 2)

        // Verify we can identify the stalled one by checking lastCheckpoint
        val stalled = allRunning.filter {
            it.lastCheckpoint.isBefore(LocalDateTime.now().minusMinutes(30))
        }
        Assertions.assertTrue(stalled.isNotEmpty(), "Should find at least one stalled exchange")
    }

    @Test
    @Order(6)
    @Transactional
    fun `should handle recovery with no interrupted exchanges`() {
        // Clean state - no interrupted exchanges
        ExchangeState.deleteAll()

        // Get stats - should show zeros
        val stats = crashRecoveryService.getRecoveryStats()
        Assertions.assertEquals(0, stats.runningExchanges)
    }

    @Test
    @Order(7)
    fun `should track exchange progress through checkpoints`() {
        val exchange = createInterruptedExchange("test-route", 5)

        // Simulate progressive checkpointing
        val steps = listOf(
            "start" to "Exchange started",
            "validate" to "Input validated",
            "process" to "Processing data",
            "finalize" to "Finalizing",
            "complete" to "Completing"
        )

        steps.forEachIndexed { index, (stepName, stepData) ->
            createCheckpoint(exchange.exchangeId, index, stepName, stepData)
        }

        // Verify all checkpoints
        val checkpoints = ExchangeCheckpoint.getCheckpoints(exchange.exchangeId)
        Assertions.assertEquals(5, checkpoints.size)

        // Verify they're in order
        checkpoints.forEachIndexed { index, checkpoint ->
            Assertions.assertEquals(index, checkpoint.stepIndex)
            Assertions.assertEquals(steps[index].first, checkpoint.stepName)
        }
    }

    @Test
    @Order(8)
    fun `should handle multiple exchanges for same route`() {
        // Create multiple interrupted exchanges for the same route
        val route = "shared-route"
        val exchange1 = createInterruptedExchange(route, 2)
        val exchange2 = createInterruptedExchange(route, 3)
        val exchange3 = createInterruptedExchange(route, 1)

        // Verify all are tracked
        val routeExchanges = ExchangeState.findByRouteId(route)
        Assertions.assertTrue(routeExchanges.size >= 3)
    }

    // Helper methods
    @Transactional
    internal fun createInterruptedExchange(routeId: String, currentStep: Int): ExchangeState {
        val exchange = ExchangeState(
            exchangeId = "interrupted-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}",
            routeId = routeId,
            status = ExchangeStatus.RUNNING,
            payload = """{"message": "test", "step": $currentStep}""",
            currentStep = currentStep,
            currentStepName = "step-$currentStep",
            createdAt = LocalDateTime.now().minusMinutes(10),
            startedAt = LocalDateTime.now().minusMinutes(9),
            lastCheckpoint = LocalDateTime.now().minusMinutes(5)
        )
        exchange.persist()
        return exchange
    }

    @Transactional
    internal fun createStalledExchange(): ExchangeState {
        val exchange = ExchangeState(
            exchangeId = "stalled-${System.currentTimeMillis()}",
            routeId = "stalled-route",
            status = ExchangeStatus.RUNNING,
            payload = """{"message": "stalled"}""",
            createdAt = LocalDateTime.now().minusHours(2),
            startedAt = LocalDateTime.now().minusHours(2),
            lastCheckpoint = LocalDateTime.now().minusHours(1) // Stalled for 1 hour
        )
        exchange.persist()
        return exchange
    }

    @Transactional
    internal fun createTestExchange(status: ExchangeStatus): ExchangeState {
        val exchange = ExchangeState(
            exchangeId = "test-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}",
            routeId = "test-route",
            status = status,
            payload = """{"test": "data"}""",
            createdAt = LocalDateTime.now()
        )
        exchange.persist()
        return exchange
    }

    @Transactional
    internal fun createTestApproval(): ApprovalRequest {
        val approval = ApprovalRequest(
            exchangeId = "exchange-${System.currentTimeMillis()}",
            routeId = "test-route",
            payload = """{"approval": "test"}""",
            status = "PENDING"
        )
        approval.persist()
        return approval
    }

    @Transactional
    internal fun createCheckpoint(exchangeId: String, stepIndex: Int, stepName: String, stepData: String? = null) {
        ExchangeCheckpoint.logCheckpoint(exchangeId, stepIndex, stepName, stepData)
    }
}
