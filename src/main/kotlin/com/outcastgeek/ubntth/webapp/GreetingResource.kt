package com.outcastgeek.ubntth.webapp

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/")
class GreetingResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/hello")
    fun hello() = "Hello from Quarkus REST"

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/greet/{name}")
    fun greet(name: String) = "Hello, $name!"
}