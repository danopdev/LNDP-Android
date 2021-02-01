package com.dan.lndpandroid

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.dan.lndpandroid.databinding.ActivityMainBinding
import io.ktor.application.call
import io.ktor.response.respondText
import io.ktor.http.ContentType
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

class MainActivity : AppCompatActivity() {

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(mBinding.root)
        startServer()
    }

    private fun startServer() {
        val server = embeddedServer(CIO, port = 8080) {
            routing {
                get("/") {
                    call.respondText("Hello World!", ContentType.Text.Plain)
                }
                get("/demo") {
                    call.respondText("HELLO WORLD!")
                }
            }
        }
        server.start()
    }
}