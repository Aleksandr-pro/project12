package com.example.project12

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.ArrayList
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private lateinit var pbMicLevel: ProgressBar
    private lateinit var tvPartial: TextView
    private lateinit var tvDiagnostic: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSpeakOnce: Button
    private lateinit var switchDirectMode: SwitchCompat
    private lateinit var tvModeDescription: TextView
    private lateinit var etCommand: EditText
    private lateinit var btnSendText: Button
    private lateinit var tvHistory: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var tvCommandsGuide: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private lateinit var localCommandProcessor: CommandProcessor
    private var pendingActionAfterPermission: (() -> Unit)? = null
    private var waitingForSystemSpeechDialog = false

    private val busListener: () -> Unit = {
        renderState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantStateBus.initPrefs(this)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        pbMicLevel = findViewById(R.id.pbMicLevel)
        tvPartial = findViewById(R.id.tvPartial)
        tvDiagnostic = findViewById(R.id.tvDiagnostic)
        btnToggle = findViewById(R.id.btnToggle)
        btnSpeakOnce = findViewById(R.id.btnSpeakOnce)
        switchDirectMode = findViewById(R.id.switchDirectMode)
        tvModeDescription = findViewById(R.id.tvModeDescription)
        etCommand = findViewById(R.id.etCommand)
        btnSendText = findViewById(R.id.btnSendText)
        tvHistory = findViewById(R.id.tvHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        tvCommandsGuide = findViewById(R.id.tvCommandsGuide)

        localTts = TextToSpeech(this, this)
        localCommandProcessor = CommandProcessor(this) { userText, asyncReply ->
            AssistantStateBus.addDialogue(userText, asyncReply)
            speakLocally(asyncReply)
        }

        switchDirectMode.isChecked = AssistantStateBus.directMode
        updateModeHint(AssistantStateBus.directMode)

        switchDirectMode.setOnCheckedChangeListener { _, isChecked ->
            if (AssistantStateBus.directMode != isChecked) {
                AssistantStateBus.setDirectMode(this, isChecked)
            }
            updateModeHint(isChecked)
        }

        btnToggle.setOnClickListener {
            if (AssistantStateBus.isServiceRunning) {
                stopAssistantService()
            } else {
                ensureMicPermission {
                    startAssistantService()
                }
            }
        }

        btnSpeakOnce.setOnClickListener {
            ensureMicPermission {
                openSystemSpeechDialog()
            }
        }

        btnSendText.setOnClickListener {
            submitTypedCommand()
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitTypedCommand()
                true
            } else {
                false
            }
        }

        btnClearHistory.setOnClickListener {
            AssistantStateBus.clearHistory()
        }

        tvCommandsGuide.text = buildCommandsReference()
        renderState()
    }

    override fun onStart() {
        super.onStart()
        AssistantStateBus.addListener(busListener)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        AssistantStateBus.setActiveActivity(this)
        if (waitingForSystemSpeechDialog) {
            // Returned from dialog if onActivityResult already handled or failed
            waitingForSystemSpeechDialog = false
        }
    }

    override fun onPause() {
        AssistantStateBus.setActiveActivity(null)
        if (AssistantStateBus.isServiceRunning && !waitingForSystemSpeechDialog) {
            startAssistantService(action = VoiceAssistantService.ACTION_ACTIVITY_PAUSED)
        }
        super.onPause()
    }

    override fun onStop() {
        AssistantStateBus.removeListener(busListener)
        super.onStop()
    }

    override fun onDestroy() {
        AssistantStateBus.setActiveActivity(null)
        mainHandler.removeCallbacksAndMessages(null)
        try {
            localTts?.stop()
            localTts?.shutdown()
        } catch (_: Exception) {
        }
        localTts = null
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTts?.language = Locale("ru", "RU")
            localTtsReady = true
        }
    }

    private fun speakLocally(text: String) {
        if (localTtsReady && !AssistantStateBus.isServiceRunning) {
            localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "local_utt")
        }
    }

    private fun startAssistantService(
        action: String? = null,
        extraText: String? = null,
        candidates: ArrayList<String>? = null
    ) {
        val intent = Intent(this, VoiceAssistantService::class.java).apply {
            if (action != null) this.action = action
            if (extraText != null) putExtra(VoiceAssistantService.EXTRA_COMMAND_TEXT, extraText)
            if (candidates != null) putStringArrayListExtra(VoiceAssistantService.EXTRA_CANDIDATES_LIST, candidates)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAssistantService() {
        val intent = Intent(this, VoiceAssistantService::class.java)
        stopService(intent)
    }

    @Suppress("DEPRECATION")
    private fun openSystemSpeechDialog() {
        try {
            localTts?.stop()
        } catch (_: Exception) {
        }

        val launchDialog = Runnable {
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите команду (например: включи фонарик, погода)…")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            try {
                waitingForSystemSpeechDialog = true
                startActivityForResult(speechIntent, REQ_SPEECH_ONCE)
            } catch (_: Exception) {
                waitingForSystemSpeechDialog = false
                // Fallback if ROM has no system speech dialog Activity
                startAssistantService(action = VoiceAssistantService.ACTION_FORCE_LISTEN)
            }
        }

        if (AssistantStateBus.isServiceRunning) {
            // Pause background recognizer first so the microphone isn't held by two sessions
            startAssistantService(action = VoiceAssistantService.ACTION_PAUSE_FOR_DIALOG)
            mainHandler.postDelayed(launchDialog, 200L)
        } else {
            launchDialog.run()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH_ONCE) {
            waitingForSystemSpeechDialog = false
            val matches = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (resultCode == Activity.RESULT_OK && matches.isNotEmpty()) {
                if (AssistantStateBus.isServiceRunning) {
                    startAssistantService(
                        action = VoiceAssistantService.ACTION_EXECUTE_TEXT,
                        candidates = ArrayList(matches)
                    )
                } else {
                    executeLocalCandidates(matches)
                }
            } else if (AssistantStateBus.isServiceRunning) {
                startAssistantService(action = VoiceAssistantService.ACTION_RESUME_LISTENING)
            }
        }
    }

    private fun submitTypedCommand() {
        val cmd = etCommand.text?.toString()?.trim().orEmpty()
        if (cmd.isEmpty()) return
        etCommand.setText("")

        if (AssistantStateBus.isServiceRunning) {
            startAssistantService(
                action = VoiceAssistantService.ACTION_EXECUTE_TEXT,
                extraText = cmd
            )
        } else {
            executeLocalCandidates(listOf(cmd))
        }
    }

    private fun executeLocalCandidates(candidates: List<String>) {
        if (candidates.isEmpty()) return
        val primary = candidates.first()
        val (_, stripped) = localCommandProcessor.stripWakeWordPrefix(primary)
        val effective = stripped.ifEmpty { primary }

        var res = localCommandProcessor.process(effective)
        var chosenDisplay = primary

        if (!res.matched && candidates.size > 1) {
            for (alt in candidates.drop(1)) {
                val (_, altStripped) = localCommandProcessor.stripWakeWordPrefix(alt)
                val altCmd = altStripped.ifEmpty { alt }
                val altRes = localCommandProcessor.process(altCmd)
                if (altRes.matched) {
                    res = altRes
                    chosenDisplay = alt
                    break
                }
            }
        }

        res.speechRate?.let { localTts?.setSpeechRate(it) }
        if (res.stopSpeakingOnly) {
            localTts?.stop()
            AssistantStateBus.addDialogue(chosenDisplay, "Озвучка остановлена.")
            return
        }
        val reply = res.spokenResponse
        if (!reply.isNullOrBlank()) {
            AssistantStateBus.addDialogue(chosenDisplay, reply)
            speakLocally(reply)
        }
    }

    private fun updateModeHint(direct: Boolean) {
        tvModeDescription.text = if (direct) {
            "ВКЛ: говорите команды сразу («включи фонарик», «погода», «таймер на 5 минут»)."
        } else {
            "ВЫКЛ: начинайте со слова «Помощник» (например: «Помощник, включи фонарик»)."
        }
    }

    private fun renderState() {
        val running = AssistantStateBus.isServiceRunning
        val status = AssistantStateBus.statusText.ifEmpty {
            if (running) getString(R.string.status_running) else getString(R.string.status_stopped)
        }
        tvStatus.text = status
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.statusRunning else R.color.statusStopped
            )
        )
        btnToggle.text = if (running) getString(R.string.btn_stop) else getString(R.string.btn_start)

        pbMicLevel.progress = if (running && AssistantStateBus.isListeningNow) {
            AssistantStateBus.micLevelPercent
        } else {
            0
        }

        val partial = AssistantStateBus.partialSpeech
        tvPartial.text = when {
            partial.isNotEmpty() -> "🎤 Слышу: «$partial»"
            running && AssistantStateBus.isListeningNow -> "Говорите в микрофон — полоса выше покажет уровень голоса"
            running -> "Обработка…"
            else -> "Нажмите зелёную кнопку «🎤 Нажать и сказать команду» или включите постоянный режим"
        }

        val diag = AssistantStateBus.diagnosticInfo
        if (diag.isNotBlank() && running) {
            tvDiagnostic.visibility = View.VISIBLE
            tvDiagnostic.text = diag
        } else {
            tvDiagnostic.visibility = View.GONE
        }

        if (switchDirectMode.isChecked != AssistantStateBus.directMode) {
            switchDirectMode.isChecked = AssistantStateBus.directMode
            updateModeHint(AssistantStateBus.directMode)
        }

        val entries = AssistantStateBus.history
        if (entries.isEmpty()) {
            tvHistory.text = "Пока нет выполненных команд."
        } else {
            tvHistory.text = entries.take(10).joinToString("\n\n") { e ->
                "[${e.time}] Вы: ${e.userText}\n🤖 ${e.assistantText}"
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureMicPermission(onGranted: () -> Unit) {
        if (hasMicPermission()) {
            onGranted()
            return
        }
        pendingActionAfterPermission = onGranted
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val action = pendingActionAfterPermission
            pendingActionAfterPermission = null
            if (hasMicPermission()) {
                action?.invoke()
            } else {
                tvStatus.text = getString(R.string.error_permissions)
            }
        }
    }

    private fun buildCommandsReference(): String = """
🔦 УПРАВЛЕНИЕ ТЕЛЕФОНОМ
• «Включи фонарик» / «Выключи фонарик»
• «Громче» / «Тише» / «Громкость на 50 процентов»
• «Звук на максимум» / «Без звука» / «Включи звук»
• «Сколько заряда» / «Батарея»
• «Есть ли интернет» / «Какая модель телефона»

⏰ БУДИЛЬНИКИ, ТАЙМЕРЫ, КАЛЕНДАРЬ
• «Поставь будильник на 7:30» / «Разбуди меня в 8 утра»
• «Покажи будильники» / «Секундомер»
• «Поставь таймер на 5 минут» / «Засеки 30 секунд» / «Таймер на полчаса»
• «Добавь в календарь встреча с врачом» / «Открой календарь»

📱 ПРИЛОЖЕНИЯ, КАМЕРА И НАСТРОЙКИ
• «Открой WhatsApp / Telegram / YouTube / Kaspi / 2ГИС / Instagram» (и любое другое приложение на телефоне!)
• «Открой камеру» / «Сделай фото» / «Сними видео» / «Открой галерею»
• «Открой калькулятор» / «Открой контакты» / «Открой браузер»
• «Настройки Wi-Fi» / «Настройки Bluetooth» / «Настройки звука / экрана / геолокации»

🌤 ПОГОДА, ПОИСК, КАРТЫ, ПЕРЕВОД
• «Какая погода» / «Погода в Алматы» / «Мой город Актобе»
• «Найди в интернете …» / «Загугли …» / «Что такое …»
• «Википедия солнечная система»
• «Включи на Ютубе музыку для работы» / «Включи песню …»
• «Покажи на карте …» / «Маршрут до …» / «Ближайшая аптека / магазин / АЗС»
• «Переведи на английский привет как дела»

🧮 УСТНЫЙ СЧЁТ, ВРЕМЯ И ДАТА
• «Сколько будет 25 умножить на 14» / «1500 разделить на 3»
• «20 процентов от 4500» / «Корень из 144» / «5 в квадрате»
• «Сколько времени» / «Какая сегодня дата» / «Какой завтра день недели»
• «Сколько дней до Нового года»

📝 ЗАМЕТКИ, ПОКУПКИ, ЗВОНКИ И СМС
• «Запомни код домофона 42» / «Прочитай заметки» / «Очисти заметки»
• «Добавь в список покупок молоко» / «Прочитай список покупок»
• «Позвони на номер 87771234567» / «Напиши СМС скоро буду»
• «Скопируй текст …» / «Поделись текстом …»

🎲 РАЗВЛЕЧЕНИЯ И УПРАВЛЕНИЕ ПОМОЩНИКОМ
• «Подбрось монетку» / «Брось кубик» / «Случайное число от 1 до 50»
• «Расскажи анекдот» / «Интересный факт» / «Дай совет»
• «Говори быстрее» / «Говори медленнее» / «Обычная скорость»
• «Включи прямой режим» / «Включи режим ключевого слова»
• «Замолчи» / «Останови помощника»
    """.trimIndent()

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_SPEECH_ONCE = 1002
    }
}
