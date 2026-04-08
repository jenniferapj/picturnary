package com.picturnary.game

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.socket.client.Socket
import org.json.JSONObject

enum class GameState {
    CONNECTING,
    WAITING,
    DRAWING,       // I am the drawer this round
    GUESSING,      // I am the guesser this round
    ROUND_RESULT,
    GAME_OVER
}

data class RoundStartData(
    val round: Int,
    val totalRounds: Int,
    val role: String,
    val word: String?,     // only present when role == "drawer"
    val duration: Int,
    val yourScore: Int,
    val opponentScore: Int,
    val yourName: String,
    val opponentName: String
)

data class RoundEndData(
    val round: Int,
    val totalRounds: Int,
    val word: String,
    val correct: Boolean,
    val yourScore: Int,
    val opponentScore: Int
)

data class GameOverData(
    val winnerName: String?,
    val yourScore: Int,
    val opponentScore: Int,
    val isTie: Boolean
)

class GameViewModel : ViewModel() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _gameState = MutableLiveData(GameState.CONNECTING)
    val gameState: LiveData<GameState> = _gameState

    private val _statusMessage = MutableLiveData("Connecting to server...")
    val statusMessage: LiveData<String> = _statusMessage

    private val _teamName = MutableLiveData("")
    val teamName: LiveData<String> = _teamName

    private val _opponentName = MutableLiveData("")
    val opponentName: LiveData<String> = _opponentName

    private val _roundStartData = MutableLiveData<RoundStartData?>(null)
    val roundStartData: LiveData<RoundStartData?> = _roundStartData

    private val _roundEndData = MutableLiveData<RoundEndData?>(null)
    val roundEndData: LiveData<RoundEndData?> = _roundEndData

    private val _gameOverData = MutableLiveData<GameOverData?>(null)
    val gameOverData: LiveData<GameOverData?> = _gameOverData

    private val _yourScore = MutableLiveData(0)
    val yourScore: LiveData<Int> = _yourScore

    private val _opponentScore = MutableLiveData(0)
    val opponentScore: LiveData<Int> = _opponentScore

    // Last guess attempt text shown to drawer ("They guessed: X")
    private val _guessAttempt = MutableLiveData<String?>(null)
    val guessAttempt: LiveData<String?> = _guessAttempt

    // Wrong guess feedback for guesser
    private val _guessWrong = MutableLiveData<String?>(null)
    val guessWrong: LiveData<String?> = _guessWrong

    // Real-time drawing callbacks — using callbacks instead of LiveData to avoid
    // coalescing rapid stroke events that would drop drawn points
    var onRemoteStrokeCallback: ((x: Float, y: Float, newPath: Boolean, color: Int) -> Unit)? = null
    var onClearCanvasCallback: (() -> Unit)? = null

    fun connectToServer(serverUrl: String) {
        SocketManager.connect(serverUrl)

        SocketManager.on(Socket.EVENT_CONNECT) {
            _statusMessage.postValue("Connected! Finding an opponent...")
            SocketManager.joinGame()
        }

        SocketManager.on(Socket.EVENT_CONNECT_ERROR) {
            _statusMessage.postValue("Connection failed. Check the server address.")
            _gameState.postValue(GameState.CONNECTING)
        }

        SocketManager.on("waiting") {
            _statusMessage.postValue("Waiting for an opponent...")
            _gameState.postValue(GameState.WAITING)
        }

        SocketManager.on("game_start") { args ->
            val data = args[0] as JSONObject
            _teamName.postValue(data.getString("team_name"))
            _opponentName.postValue(data.getString("opponent_name"))
            _yourScore.postValue(0)
            _opponentScore.postValue(0)
            _statusMessage.postValue("Opponent found! Get ready...")
            _gameState.postValue(GameState.WAITING)
        }

        SocketManager.on("round_start") { args ->
            val data = args[0] as JSONObject
            val role = data.getString("role")
            val roundData = RoundStartData(
                round = data.getInt("round"),
                totalRounds = data.getInt("total_rounds"),
                role = role,
                word = if (role == "drawer") data.getString("word") else null,
                duration = data.getInt("duration"),
                yourScore = data.getInt("your_score"),
                opponentScore = data.getInt("opponent_score"),
                yourName = data.getString("your_name"),
                opponentName = data.getString("opponent_name")
            )
            _yourScore.postValue(roundData.yourScore)
            _opponentScore.postValue(roundData.opponentScore)
            _guessAttempt.postValue(null)
            _guessWrong.postValue(null)
            _roundEndData.postValue(null)
            // Clear canvases via callback before transitioning
            mainHandler.post { onClearCanvasCallback?.invoke() }
            _roundStartData.postValue(roundData)
            _gameState.postValue(if (role == "drawer") GameState.DRAWING else GameState.GUESSING)
        }

        SocketManager.on("draw_stroke") { args ->
            val data = args[0] as JSONObject
            val x = data.getDouble("x").toFloat()
            val y = data.getDouble("y").toFloat()
            val newPath = data.getBoolean("new_path")
            val color = data.getInt("color")
            // Post directly to main thread — avoids LiveData coalescing dropped stroke points
            mainHandler.post {
                onRemoteStrokeCallback?.invoke(x, y, newPath, color)
            }
        }

        SocketManager.on("clear_canvas") {
            mainHandler.post { onClearCanvasCallback?.invoke() }
        }

        SocketManager.on("guess_attempt") { args ->
            val data = args[0] as JSONObject
            _guessAttempt.postValue(data.getString("guess"))
        }

        SocketManager.on("guess_wrong") { args ->
            val data = args[0] as JSONObject
            _guessWrong.postValue(data.getString("guess"))
        }

        SocketManager.on("round_end") { args ->
            val data = args[0] as JSONObject
            val result = RoundEndData(
                round = data.getInt("round"),
                totalRounds = data.getInt("total_rounds"),
                word = data.getString("word"),
                correct = data.getBoolean("correct"),
                yourScore = data.getInt("your_score"),
                opponentScore = data.getInt("opponent_score")
            )
            _yourScore.postValue(result.yourScore)
            _opponentScore.postValue(result.opponentScore)
            _roundEndData.postValue(result)
            _gameState.postValue(GameState.ROUND_RESULT)
        }

        SocketManager.on("game_over") { args ->
            val data = args[0] as JSONObject
            val isTie = data.getBoolean("is_tie")
            val gameOver = GameOverData(
                winnerName = if (isTie) null else data.getString("winner_name"),
                yourScore = data.getInt("your_score"),
                opponentScore = data.getInt("opponent_score"),
                isTie = isTie
            )
            _gameOverData.postValue(gameOver)
            _gameState.postValue(GameState.GAME_OVER)
        }

        SocketManager.on("opponent_disconnected") {
            _statusMessage.postValue("Opponent disconnected! Finding a new one...")
            _yourScore.postValue(0)
            _opponentScore.postValue(0)
            _gameState.postValue(GameState.WAITING)
            SocketManager.joinGame()
        }
    }

    fun sendStroke(x: Float, y: Float, newPath: Boolean, color: Int) {
        SocketManager.sendStroke(x, y, newPath, color)
    }

    fun sendClearCanvas() {
        SocketManager.sendClearCanvas()
    }

    fun submitGuess(guess: String) {
        SocketManager.submitGuess(guess)
    }

    fun playAgain() {
        _gameOverData.postValue(null)
        _yourScore.postValue(0)
        _opponentScore.postValue(0)
        _statusMessage.postValue("Finding a new opponent...")
        _gameState.postValue(GameState.WAITING)
        SocketManager.joinGame()
    }

    override fun onCleared() {
        super.onCleared()
        SocketManager.disconnect()
    }
}
