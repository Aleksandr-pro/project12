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
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var etFilterCommands: EditText
    private lateinit var tvCommandsGuide: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private lateinit var localCommandProcessor: CommandProcessor
    private var pendingActionAfterPermission: (() -> Unit)? = null
    private var waitingForSystemSpeechDialog = false
    private lateinit var fullGuideSections: List<String>

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
        etFilterCommands = findViewById(R.id.etFilterCommands)
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
                ensureAppPermissions {
                    startAssistantService()
                }
            }
        }

        btnSpeakOnce.setOnClickListener {
            ensureAppPermissions {
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

        fullGuideSections = buildGuideSections()
        tvCommandsGuide.text = fullGuideSections.joinToString("\n\n")

        etFilterCommands.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCommandsGuide(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderState()
    }

    private fun filterCommandsGuide(rawQuery: String) {
        val q = rawQuery.trim().lowercase(Locale("ru", "RU")).replace('ё', 'е')
        if (q.isEmpty()) {
            tvCommandsGuide.text = fullGuideSections.joinToString("\n\n")
            return
        }
        val matchedBlocks = fullGuideSections.mapNotNull { section ->
            val lines = section.lines()
            val header = lines.firstOrNull().orEmpty()
            val headerMatches = header.lowercase(Locale("ru", "RU")).replace('ё', 'е').contains(q)
            if (headerMatches) {
                section
            } else {
                val matchingItems = lines.drop(1).filter {
                    it.lowercase(Locale("ru", "RU")).replace('ё', 'е').contains(q)
                }
                if (matchingItems.isNotEmpty()) {
                    (listOf(header) + matchingItems).joinToString("\n")
                } else {
                    null
                }
            }
        }
        tvCommandsGuide.text = if (matchedBlocks.isEmpty()) {
            "По фильтру «$rawQuery» примеров не найдено — но вы всё равно можете сказать это Нелли своими словами!"
        } else {
            matchedBlocks.joinToString("\n\n")
        }
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
            localTts?.let { engine ->
                engine.language = Locale("ru", "RU")
                VoiceAssistantService.selectFemaleRussianVoice(engine)
            }
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
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите Нелли (например: позвони маме, включи инстаграм, ватсап)…")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            try {
                waitingForSystemSpeechDialog = true
                startActivityForResult(speechIntent, REQ_SPEECH_ONCE)
            } catch (_: Exception) {
                waitingForSystemSpeechDialog = false
                startAssistantService(action = VoiceAssistantService.ACTION_FORCE_LISTEN)
            }
        }

        if (AssistantStateBus.isServiceRunning) {
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
        val (chosenDisplay, res) = localCommandProcessor.processCandidates(candidates)
        if (chosenDisplay.isEmpty()) return

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
            "ВКЛ: говорите сразу («позвони маме», «включи инстаграм», «ватсап», «брать зонт?»)."
        } else {
            "ВЫКЛ: начинайте с имени «Нелли» (например: «Нелли, позвони маме» или «Нелли, включи ютуб»)."
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
            partial.isNotEmpty() -> "🎤 Нелли слышит: «$partial»"
            running && AssistantStateBus.isListeningNow -> "Говорите Нелли — шкала выше показывает уровень вашего голоса"
            running -> "Нелли обрабатывает запрос…"
            else -> "Скажите: «Позвони маме», «Включи Ютуб / Ватсап / Телеграм / Инстаграм» — нажмите 🎤"
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
            tvHistory.text = entries.take(12).joinToString("\n\n") { e ->
                "[${e.time}] Вы: ${e.userText}\n👩 Нелли: ${e.assistantText}"
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureAppPermissions(onReady: () -> Unit) {
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED &&
            !prefsPromptedContactsOnce()
        ) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED &&
            !prefsPromptedContactsOnce()
        ) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !prefsPromptedContactsOnce()
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isEmpty()) {
            onReady()
            return
        }

        markPromptedContactsOnce()
        pendingActionAfterPermission = onReady
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun prefsPromptedContactsOnce(): Boolean =
        getSharedPreferences("assistant_prefs", MODE_PRIVATE).getBoolean("prompted_call_perms", false)

    private fun markPromptedContactsOnce() {
        getSharedPreferences("assistant_prefs", MODE_PRIVATE).edit()
            .putBoolean("prompted_call_perms", true)
            .apply()
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

    private fun buildGuideSections(): List<String> = listOf(
        """
📞 ЗВОНКИ КОНТАКТАМ И ЗАПУСК ПРИЛОЖЕНИЙ С ПОНИМАНИЕМ ПРОИЗНОШЕНИЯ
• Звонки по имени из телефонной книги: «Позвони маме», «Набери папу», «Включи звонок Саше», «Позвони Арману»
• Понимает склонения и ласковые/полные имена: «Саше» ↔ «Александр», «Диме» ↔ «Дмитрий», «Лене» ↔ «Елена», «Мамочке» ↔ «Мама»
• Связь в WhatsApp по имени контакта: «Позвони маме по Ватсапу», «Напиши Саше в Ватсап скоро приеду»
• Умное открытие приложений (понимает «открой», «включи», «запусти», «зайди в» или просто название, даже при неточном произношении!):
  — YouTube («включи ютуб / ютюб / youtube»)
  — WhatsApp («открой ватсап / вотсап / вацап / вотсаб»)
  — Telegram («включи телеграм / телегу / тилеграм»)
  — Instagram («открой инстаграм / инсту / инстограмм»)
  — Kaspi.kz, Halyk, 2ГИС, TikTok, ВК, Яндекс Go, inDrive, Wildberries, Ozon, OLX, Колёса, Крыша и любые установленные у вас приложения!
        """.trimIndent(),
        """
👩 ОБРАЩЕНИЕ К НЕЛЛИ И ЖИВОЙ КОНТЕКСТ (НЕ НУЖНО УЧИТЬ КОМАНДЫ!)
• Помощницу зовут Нелли — можете обращаться «Нелли, позвони маме» или говорить команды сразу
• Бытовые ситуации: «Тут совсем темно / уронил ключи» → включит фонарик; «Дети спят / я на совещании» → выключит звук
• «Брать зонт? / холодно на улице? / как одеться?» → проверит погоду и подскажет одежду
• «Телефон садится?» → проверит батарею; «Варить пельмени 8 минут» → поставит таймер
• «Хочу есть / кончается бензин / болит голова / снять наличку» → найдёт рядом на карте
• Память диалога: «А завтра?», «А в Астане?», «Выключи», «Наоборот», «Ещё», «Повтори», «Раздели пополам»
        """.trimIndent(),
        """
🔦 ФОНАРИК, ЗВУК, ПЛЕЕР И ВИБРАЦИЯ
• «Включи / выключи фонарик», «Помигай фонариком», «Сигнал СОС», «Проверь вибрацию»
• «Громче», «Тише», «Громкость на 40 процентов», «На максимум», «Без звука», «Включи звук»
• «Какая сейчас громкость», «Звонок громче / тише», «Будильник громче / тише»
• Плеер: «Пауза», «Продолжи музыку», «Следующий трек», «Предыдущий трек»
        """.trimIndent(),
        """
📊 ПОЛНАЯ ДИАГНОСТИКА УСТРОЙСТВА
• «Диагностика телефона / полный отчёт», «Сколько заряда», «Температура батареи»
• «Сколько свободной памяти», «Оперативная память (ОЗУ)», «Есть ли интернет», «Мой IP-адрес»
• «Сколько работает без перезагрузки», «Модель телефона», «Часовой пояс»
        """.trimIndent(),
        """
⏰ БУДИЛЬНИКИ, ТАЙМЕРЫ, ГОЛОСОВОЙ СЕКУНДОМЕР
• «Поставь будильник на 7:30», «Разбуди через 40 минут», «Покажи будильники»
• «Поставь таймер на 10 минут», «Засеки полторы минуты», «Покажи таймеры»
• Встроенный секундомер: «Засеки время» → «Сколько прошло» → «Останови секундомер»
• Напоминание вслух: «Скажи через 30 секунд проверить чайник», «Добавь в календарь …»
        """.trimIndent(),
        """
💱 ЖИВЫЕ КУРСЫ ВАЛЮТ, СЧЁТ, СКИДКИ И КОНВЕРТЕР ЕДИНИЦ
• Валюты онлайн: «Курс доллара», «Курс евро», «Сколько будет 150 долларов в тенге», «25000 тенге в рублях»
• Финансы: «Скидка 30 процентов от 14000», «Чаевые 10 процентов от 8500», «Раздели счёт 16000 на четверых»
• Устный счёт + цепочка: «245 умножить на 18», а следом «Минус 15 процентов»!
• Конвертер: мили ↔ км, дюймы ↔ см, футы ↔ м, фунты ↔ кг, галлоны ↔ литры, °F ↔ °C, м/с ↔ км/ч
        """.trimIndent(),
        """
🌤 ПОГОДА, МИРОВОЕ ВРЕМЯ, ЗАМЕТКИ, ПОКУПКИ И ИГРЫ
• «Какая погода», «Погода завтра», «Погода в Алматы / Актобе / Астане», «Сколько времени в Токио / Лондоне / Москве»
• Заметки, покупки, задачи: «Запомни …», «Добавь в покупки …», «Вычеркни из покупок …», «Добавь задачу …», «Сгенерируй пароль»
• Энциклопедия и игры: «Кто такой Альберт Эйнштейн», «Сыграем в города!», «Загадай загадку», «Пицца или суши?»
        """.trimIndent()
    )

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_SPEECH_ONCE = 1002
    }
}
