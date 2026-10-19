package com.myth.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: Long
)

fun Route.healthRoutes() {
    get("/") {
        call.respondText(
            contentType = ContentType.Text.Html,
            text = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Myth Messenger - Backend API</title>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                            background: #0d1117;
                            color: #c9d1d9;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            min-height: 100vh;
                            margin: 0;
                            padding: 20px;
                            box-sizing: border-box;
                        }
                        .card {
                            background: #161b22;
                            border: 1px solid #30363d;
                            border-radius: 12px;
                            padding: 32px;
                            max-width: 520px;
                            width: 100%;
                            box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                            text-align: center;
                        }
                        .badge {
                            display: inline-flex;
                            align-items: center;
                            gap: 8px;
                            background: rgba(46, 160, 67, 0.15);
                            color: #3fb950;
                            border: 1px solid rgba(46, 160, 67, 0.4);
                            padding: 6px 14px;
                            border-radius: 20px;
                            font-size: 14px;
                            font-weight: 600;
                            margin-bottom: 20px;
                        }
                        .dot {
                            width: 8px;
                            height: 8px;
                            background: #3fb950;
                            border-radius: 50%;
                            box-shadow: 0 0 8px #3fb950;
                        }
                        h1 {
                            margin: 0 0 10px 0;
                            font-size: 28px;
                            color: #f0f6fc;
                        }
                        p {
                            color: #8b949e;
                            line-height: 1.6;
                            margin: 0 0 24px 0;
                        }
                        .endpoints {
                            text-align: left;
                            background: #0d1117;
                            border: 1px solid #21262d;
                            border-radius: 8px;
                            padding: 16px;
                            font-family: monospace;
                            font-size: 13px;
                            color: #58a6ff;
                        }
                        .endpoint-row {
                            display: flex;
                            justify-content: space-between;
                            margin-bottom: 8px;
                        }
                        .endpoint-row:last-child {
                            margin-bottom: 0;
                        }
                        .method {
                            color: #7ee787;
                            font-weight: bold;
                        }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="badge">
                            <span class="dot"></span>
                            All Systems Operational
                        </div>
                        <h1>Myth Messenger Server</h1>
                        <p>End-to-End Encrypted & Zero-Persistence Messaging Cloud Backend is live and ready for Android clients.</p>
                        <div class="endpoints">
                            <div class="endpoint-row"><span class="method">GET</span> /health</div>
                            <div class="endpoint-row"><span class="method">POST</span> /api/v1/auth/register</div>
                            <div class="endpoint-row"><span class="method">POST</span> /api/v1/auth/login</div>
                            <div class="endpoint-row"><span class="method">WSS</span> /ws/v1/messaging</div>
                        </div>
                    </div>
                </body>
                </html>
            """.trimIndent()
        )
    }

    get("/health") {
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "healthy",
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
