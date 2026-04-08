package com.picturnary.game

import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val viewModel: GameViewModel by viewModels()
    private lateinit var viewFlipper: ViewFlipper
    private var countDownTimer: CountDownTimer? = null

    // --- Screen 0: Waiting ---
    private lateinit var tvStatus: TextView

    // --- Screen 1: Drawing (I am the drawer) ---
    private lateinit var tvDrawRound: TextView
    private lateinit var tvDrawOpponent: TextView
    private lateinit var tvDrawScore: TextView
    private lateinit var tvDrawTimer: TextView
    private lateinit var tvDrawWord: TextView
    private lateinit var tvGuessAttempt: TextView
    private lateinit var drawingView: DrawingView
    private lateinit var btnColorBlack: Button
    private lateinit var btnColorRed: Button
    private lateinit var btnColorBlue: Button
    private lateinit var btnColorGreen: Button
    private lateinit var btnColorOrange: Button
    private lateinit var btnClear: Button

    // --- Screen 2: Guessing (I am the guesser) ---
    private lateinit var tvGuessRound: TextView
    private lateinit var tvGuessOpponent: TextView
    private lateinit var tvGuessScore: TextView
    private lateinit var tvGuessTimer: TextView
    private lateinit var tvGuessPrompt: TextView
    private lateinit var guessingView: DrawingView
    private lateinit var etGuess: EditText
    private lateinit var btnSubmitGuess: Button
    private lateinit var tvWrongGuess: TextView

    // --- Screen 3: Round Result ---
    private lateinit var tvResultRound: TextView
    private lateinit var tvResultWord: TextView
    private lateinit var tvResultOutcome: TextView
    private lateinit var tvResultScore: TextView
    private lateinit var tvResultNext: TextView

    // --- Screen 4: Game Over ---
    private lateinit var tvGameOverTitle: TextView
    private lateinit var tvGameOverScore: TextView
    private lateinit var btnPlayAgain: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        observeViewModel()
        viewModel.connectToServer("https://web-production-dfd6c.up.railway.app")
    }

    private fun bindViews() {
        viewFlipper = findViewById(R.id.viewFlipper)

        // Screen 0
        tvStatus = findViewById(R.id.tvStatus)

        // Screen 1
        tvDrawRound = findViewById(R.id.tvDrawRound)
        tvDrawOpponent = findViewById(R.id.tvDrawOpponent)
        tvDrawScore = findViewById(R.id.tvDrawScore)
        tvDrawTimer = findViewById(R.id.tvDrawTimer)
        tvDrawWord = findViewById(R.id.tvDrawWord)
        tvGuessAttempt = findViewById(R.id.tvGuessAttempt)
        drawingView = findViewById(R.id.drawingView)
        btnColorBlack = findViewById(R.id.btnColorBlack)
        btnColorRed = findViewById(R.id.btnColorRed)
        btnColorBlue = findViewById(R.id.btnColorBlue)
        btnColorGreen = findViewById(R.id.btnColorGreen)
        btnColorOrange = findViewById(R.id.btnColorOrange)
        btnClear = findViewById(R.id.btnClear)

        // Screen 2
        tvGuessRound = findViewById(R.id.tvGuessRound)
        tvGuessOpponent = findViewById(R.id.tvGuessOpponent)
        tvGuessScore = findViewById(R.id.tvGuessScore)
        tvGuessTimer = findViewById(R.id.tvGuessTimer)
        tvGuessPrompt = findViewById(R.id.tvGuessPrompt)
        guessingView = findViewById(R.id.guessingView)
        etGuess = findViewById(R.id.etGuess)
        btnSubmitGuess = findViewById(R.id.btnSubmitGuess)
        tvWrongGuess = findViewById(R.id.tvWrongGuess)

        // Screen 3
        tvResultRound = findViewById(R.id.tvResultRound)
        tvResultWord = findViewById(R.id.tvResultWord)
        tvResultOutcome = findViewById(R.id.tvResultOutcome)
        tvResultScore = findViewById(R.id.tvResultScore)
        tvResultNext = findViewById(R.id.tvResultNext)

        // Screen 4
        tvGameOverTitle = findViewById(R.id.tvGameOverTitle)
        tvGameOverScore = findViewById(R.id.tvGameOverScore)
        btnPlayAgain = findViewById(R.id.btnPlayAgain)

        // Wire up the drawing view
        drawingView.isDrawingMode = true
        drawingView.onStrokePoint = { x, y, newPath, color ->
            viewModel.sendStroke(x, y, newPath, color)
        }

        // Color palette for drawer
        btnColorBlack.setOnClickListener { selectColor(Color.BLACK) }
        btnColorRed.setOnClickListener { selectColor(Color.RED) }
        btnColorBlue.setOnClickListener { selectColor(Color.BLUE) }
        btnColorGreen.setOnClickListener { selectColor(Color.rgb(22, 199, 154)) }
        btnColorOrange.setOnClickListener { selectColor(Color.rgb(255, 140, 0)) }
        btnClear.setOnClickListener {
            drawingView.clear()
            viewModel.sendClearCanvas()
        }

        // Guess submission
        btnSubmitGuess.setOnClickListener { submitGuess() }
        etGuess.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitGuess(); true } else false
        }

        // Remote drawing callbacks — direct calls avoid LiveData coalescing
        viewModel.onRemoteStrokeCallback = { x, y, newPath, color ->
            guessingView.addRemoteStroke(x, y, newPath, color)
        }
        viewModel.onClearCanvasCallback = {
            guessingView.clear()
            drawingView.clear()
        }

        // Play again
        btnPlayAgain.setOnClickListener { viewModel.playAgain() }
    }

    private fun selectColor(color: Int) {
        drawingView.selectedColor = color
    }

    private fun submitGuess() {
        val guess = etGuess.text.toString().trim()
        if (guess.isNotEmpty()) {
            viewModel.submitGuess(guess)
            etGuess.setText("")
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun observeViewModel() {
        viewModel.gameState.observe(this) { state ->
            countDownTimer?.cancel()
            when (state) {
                GameState.CONNECTING, GameState.WAITING -> {
                    viewFlipper.displayedChild = 0
                }
                GameState.DRAWING -> {
                    tvGuessAttempt.text = ""
                    viewFlipper.displayedChild = 1
                    val duration = viewModel.roundStartData.value?.duration ?: 60
                    startTimer(duration, tvDrawTimer)
                }
                GameState.GUESSING -> {
                    etGuess.setText("")
                    tvWrongGuess.text = ""
                    viewFlipper.displayedChild = 2
                    val duration = viewModel.roundStartData.value?.duration ?: 60
                    startTimer(duration, tvGuessTimer)
                }
                GameState.ROUND_RESULT -> {
                    viewFlipper.displayedChild = 3
                }
                GameState.GAME_OVER -> {
                    viewFlipper.displayedChild = 4
                }
                null -> {}
            }
        }

        viewModel.statusMessage.observe(this) { msg ->
            tvStatus.text = msg
        }

        viewModel.roundStartData.observe(this) { data ->
            data ?: return@observe
            val roundText = "Round ${data.round} / ${data.totalRounds}"
            tvDrawRound.text = roundText
            tvGuessRound.text = roundText
            tvDrawOpponent.text = "vs ${data.opponentName}"
            tvGuessOpponent.text = "vs ${data.opponentName}"
            if (data.role == "drawer") {
                tvDrawWord.text = "Draw: 🎨 ${data.word?.uppercase()}"
            } else {
                tvGuessPrompt.text = "What is ${data.opponentName} drawing?"
            }
        }

        viewModel.yourScore.observe(this) { score ->
            val opp = viewModel.opponentScore.value ?: 0
            val text = "Score: $score - $opp"
            tvDrawScore.text = text
            tvGuessScore.text = text
        }

        viewModel.opponentScore.observe(this) { opp ->
            val score = viewModel.yourScore.value ?: 0
            val text = "Score: $score - $opp"
            tvDrawScore.text = text
            tvGuessScore.text = text
        }

        viewModel.guessAttempt.observe(this) { guess ->
            tvGuessAttempt.text = if (guess != null) "They guessed: \"$guess\"" else ""
        }

        viewModel.guessWrong.observe(this) { guess ->
            tvWrongGuess.text = if (guess != null) "❌ \"$guess\" — keep trying!" else ""
        }

        viewModel.roundEndData.observe(this) { data ->
            data ?: return@observe
            tvResultRound.text = "Round ${data.round} / ${data.totalRounds}"
            tvResultWord.text = "The word was: ${data.word.uppercase()}"
            tvResultOutcome.text = if (data.correct) "Correct guess! 🎉" else "Time's up! ⏱️"
            tvResultScore.text = "Score: ${data.yourScore} - ${data.opponentScore}"
            val isLast = data.round >= data.totalRounds
            tvResultNext.text = if (isLast) "Calculating final score..." else "Next round starting..."
        }

        viewModel.gameOverData.observe(this) { data ->
            data ?: return@observe
            tvGameOverTitle.text = when {
                data.isTie -> "It's a Tie! 🤝"
                data.winnerName == viewModel.teamName.value -> "You Win! 🏆"
                else -> "You Lose! 😭"
            }
            tvGameOverScore.text = "Final Score: ${data.yourScore} - ${data.opponentScore}"
        }
    }

    private fun startTimer(seconds: Int, timerView: TextView) {
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000).toInt()
                timerView.text = "⏱️ $secs"
                timerView.setTextColor(if (secs <= 10) Color.RED else Color.WHITE)
            }
            override fun onFinish() {
                timerView.text = "⏱️ 0"
                timerView.setTextColor(Color.RED)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
