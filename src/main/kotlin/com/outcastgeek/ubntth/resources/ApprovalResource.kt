package com.outcastgeek.ubntth.resources

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.outcastgeek.ubntth.entities.ApprovalRequest
import com.outcastgeek.ubntth.services.ApprovalService
import io.quarkus.logging.Log
import jakarta.inject.Inject
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * REST API for managing approval requests (human-in-the-loop).
 *
 * Endpoints:
 * - GET /api/approvals - List all pending approvals
 * - GET /api/approvals/{id} - Get approval details
 * - POST /api/approvals/{id}/approve - Approve a request
 * - POST /api/approvals/{id}/reject - Reject a request
 */
@Path("/api/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
class ApprovalResource {

    @Inject
    lateinit var approvalService: ApprovalService

    /**
     * List all pending approval requests
     */
    @GET
    fun listPendingApprovals(): Response {
        try {
            val approvals = approvalService.getPendingApprovals()

            return Response.ok(mapOf(
                "approvals" to approvals,
                "total" to approvals.size
            )).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to list approvals", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get details of a specific approval request
     */
    @GET
    @Path("/{id}")
    fun getApproval(@PathParam("id") approvalId: String): Response {
        try {
            val approval = approvalService.getApprovalById(approvalId)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "Approval not found: $approvalId"))
                    .build()

            return Response.ok(approval).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get approval: $approvalId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Approve an approval request
     */
    @POST
    @Path("/{id}/approve")
    fun approveRequest(
        @PathParam("id") approvalId: String,
        request: ApprovalDecisionRequest?
    ): Response {
        try {
            val success = approvalService.approve(
                approvalId = approvalId,
                response = request?.response
            )

            if (success) {
                Log.info("✅ Approved request: $approvalId")

                return Response.ok(mapOf(
                    "approvalId" to approvalId,
                    "message" to "Approval granted successfully"
                )).build()
            } else {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(mapOf("error" to "Failed to approve request"))
                    .build()
            }

        } catch (e: IllegalArgumentException) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to e.message))
                .build()
        } catch (e: IllegalStateException) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to e.message))
                .build()
        } catch (e: Exception) {
            Log.error("❌ Failed to approve request: $approvalId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Reject an approval request
     */
    @POST
    @Path("/{id}/reject")
    fun rejectRequest(
        @PathParam("id") approvalId: String,
        request: ApprovalDecisionRequest?
    ): Response {
        try {
            val success = approvalService.reject(
                approvalId = approvalId,
                reason = request?.reason
            )

            if (success) {
                Log.info("❌ Rejected request: $approvalId")

                return Response.ok(mapOf(
                    "approvalId" to approvalId,
                    "message" to "Approval rejected successfully",
                    "reason" to (request?.reason ?: "No reason provided")
                )).build()
            } else {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(mapOf("error" to "Failed to reject request"))
                    .build()
            }

        } catch (e: IllegalArgumentException) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to e.message))
                .build()
        } catch (e: IllegalStateException) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to e.message))
                .build()
        } catch (e: Exception) {
            Log.error("❌ Failed to reject request: $approvalId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }

    /**
     * Get approval request by exchange ID
     */
    @GET
    @Path("/by-exchange/{exchangeId}")
    fun getApprovalByExchangeId(@PathParam("exchangeId") exchangeId: String): Response {
        try {
            val approval = ApprovalRequest.findByExchangeId(exchangeId)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "No approval found for exchange: $exchangeId"))
                    .build()

            return Response.ok(approval).build()

        } catch (e: Exception) {
            Log.error("❌ Failed to get approval for exchange: $exchangeId", e)
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Unknown error")))
                .build()
        }
    }
}

/**
 * Request body for approval decisions
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApprovalDecisionRequest(
    val response: String? = null,
    val reason: String? = null
)
