package com.outcastgeek.ubntth.service

import com.fasterxml.jackson.annotation.JsonProperty
import dev.langchain4j.service.SystemMessage
import dev.langchain4j.service.UserMessage
import io.quarkiverse.langchain4j.RegisterAiService
import jakarta.inject.Named

@RegisterAiService
@Named("codeExplainer")
interface CodeExplanationService {
    @SystemMessage("You are a code analysis expert. Analyze the code and provide a structured explanation.")
    @UserMessage("Explain this code:\n\n{code}")
    fun explainCode(code: String): CodeExplanation
}

data class CodeExplanation(
    @JsonProperty("code") val code: String,
    @JsonProperty("explanation") val explanation: String
)
