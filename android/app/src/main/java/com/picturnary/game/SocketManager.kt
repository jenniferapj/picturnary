package com.picturnary.game

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

object SocketManager {
    private var socket: Socket? = null

    fun connect(serverUrl: String) {
        val opts = IO.Options().apply {
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionAttempts = 5
            reconnectionDelay = 1000
        }
        socket = IO.socket(serverUrl, opts)
        socket?.connect()
    }

    fun joinGame() {
        socket?.emit("join_game")
    }

    fun sendStroke(x: Float, y: Float, newPath: Boolean, color: Int) {
        val data = JSONObject()
            .put("x", x)
            .put("y", y)
            .put("new_path", newPath)
            .put("color", color)
        socket?.emit("draw_stroke", data)
    }

    fun sendClearCanvas() {
        socket?.emit("clear_canvas")
    }

    fun submitGuess(guess: String) {
        val data = JSONObject().put("guess", guess)
        socket?.emit("submit_guess", data)
    }

    fun on(event: String, listener: (Array<Any>) -> Unit) {
        socket?.on(event) { args -> listener(args) }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
