package com.outcastgeek.ubntth.resources

import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * REST API for discovering available workflows and their capabilities.
 *
 * This provides user-friendly information about what workflows are available
 * and how to use them.
 */
@Path("/api/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class WorkflowResource {

    /**
     * Get all available workflows with descriptions
     */
    @GET
    fun getAvailableWorkflows(): Response {
        val workflows = listOf(
            WorkflowInfo(
                id = "chat-durable",
                name = "Durable AI Chat",
                description = "Send a message to an AI with full crash recovery and approval gates. " +
                        "Your conversation is checkpointed at each step, so if the system crashes, " +
                        "it can resume exactly where it left off.",
                category = "AI Chat",
                features = listOf(
                    "Checkpoint-based crash recovery",
                    "Approval gates for sensitive operations",
                    "Full execution history",
                    "Pause/Resume capability"
                ),
                examplePayload = "What are the best practices for securing a REST API?",
                expectedDuration = "10-30 seconds",
                requiresApproval = false,
                isDurable = true
            ),
            WorkflowInfo(
                id = "chat-simple",
                name = "Simple AI Chat",
                description = "Quick AI chat without durability features. Use this for fast, " +
                        "non-critical conversations where crash recovery isn't needed.",
                category = "AI Chat",
                features = listOf(
                    "Fast response times",
                    "No overhead from checkpointing",
                    "Suitable for simple queries"
                ),
                examplePayload = "Explain the difference between REST and GraphQL",
                expectedDuration = "5-15 seconds",
                requiresApproval = false,
                isDurable = false
            )
        )

        return Response.ok(mapOf(
            "workflows" to workflows,
            "total" to workflows.size
        )).build()
    }

    /**
     * Get workflow templates - pre-configured workflows for common use cases
     */
    @GET
    @Path("/templates")
    fun getWorkflowTemplates(): Response {
        val templates = listOf(
            WorkflowTemplate(
                id = "code-review",
                name = "Code Review Assistant",
                description = "Get AI-powered code review with explanations and suggestions",
                workflowId = "chat-durable",
                payload = "Please review this code for potential issues, security vulnerabilities, and best practices:\n\n```\n// Paste your code here\n```",
                tags = listOf("code", "review", "security")
            ),
            WorkflowTemplate(
                id = "api-design",
                name = "API Design Helper",
                description = "Get help designing RESTful API endpoints",
                workflowId = "chat-durable",
                payload = "Help me design a REST API for [describe your use case]. Include endpoint paths, HTTP methods, request/response bodies, and error handling.",
                tags = listOf("api", "design", "rest")
            ),
            WorkflowTemplate(
                id = "debug-assistant",
                name = "Debug Assistant",
                description = "Get help debugging an issue",
                workflowId = "chat-simple",
                payload = "I'm encountering this error: [paste error message]. The relevant code is: [paste code]. What could be causing this?",
                tags = listOf("debug", "error", "troubleshoot")
            ),
            WorkflowTemplate(
                id = "architecture-review",
                name = "Architecture Review",
                description = "Get feedback on your system architecture",
                workflowId = "chat-durable",
                payload = "Please review this architecture decision:\n\nContext: [describe the problem]\nDecision: [describe your solution]\nConsequences: [what are the trade-offs]\n\nIs this a good approach?",
                tags = listOf("architecture", "design", "review")
            ),
            WorkflowTemplate(
                id = "quick-question",
                name = "Quick Question",
                description = "Ask a simple technical question",
                workflowId = "chat-simple",
                payload = "What is [your question]?",
                tags = listOf("quick", "question", "simple")
            )
        )

        return Response.ok(mapOf(
            "templates" to templates,
            "total" to templates.size
        )).build()
    }

    /**
     * Get details about a specific workflow
     */
    @GET
    @Path("/{id}")
    fun getWorkflowDetails(@PathParam("id") workflowId: String): Response {
        val workflow = when (workflowId) {
            "chat-durable" -> WorkflowInfo(
                id = "chat-durable",
                name = "Durable AI Chat",
                description = "Send a message to an AI with full crash recovery and approval gates.",
                category = "AI Chat",
                features = listOf(
                    "Checkpoint-based crash recovery",
                    "Approval gates for sensitive operations",
                    "Full execution history",
                    "Pause/Resume capability"
                ),
                examplePayload = "What are the best practices for securing a REST API?",
                expectedDuration = "10-30 seconds",
                requiresApproval = false,
                isDurable = true
            )
            "chat-simple" -> WorkflowInfo(
                id = "chat-simple",
                name = "Simple AI Chat",
                description = "Quick AI chat without durability features.",
                category = "AI Chat",
                features = listOf(
                    "Fast response times",
                    "No overhead from checkpointing"
                ),
                examplePayload = "Explain the difference between REST and GraphQL",
                expectedDuration = "5-15 seconds",
                requiresApproval = false,
                isDurable = false
            )
            else -> return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Workflow not found: $workflowId"))
                .build()
        }

        return Response.ok(workflow).build()
    }
}

/**
 * Information about an available workflow
 */
data class WorkflowInfo(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val features: List<String>,
    val examplePayload: String,
    val expectedDuration: String,
    val requiresApproval: Boolean,
    val isDurable: Boolean
)

/**
 * Pre-configured workflow template
 */
data class WorkflowTemplate(
    val id: String,
    val name: String,
    val description: String,
    val workflowId: String,
    val payload: String,
    val tags: List<String>
)
