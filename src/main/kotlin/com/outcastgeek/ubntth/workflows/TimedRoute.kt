package com.outcastgeek.ubntth.workflows

import org.apache.camel.builder.RouteBuilder

class TimedRoute: RouteBuilder() {

    override fun configure() {
        from("timer:myTimer?period=5000")
//            .log("Timer triggered at ${'$'}{heade}")
//            .log("Timer triggered at ${'$'}{header.firedTime}")
            .log("Timer triggered at ${'$'}{date:now:yyyy-MM-dd HH:mm:ss}")
    }
}
