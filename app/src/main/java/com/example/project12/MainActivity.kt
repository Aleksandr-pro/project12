package com.example.project12

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var tvScreenControlStatus: TextView
    private lateinit var btnEnableScreenControl: Button
    private lateinit var etCommand: EditText
    private lateinit var btnSendText: Button
    private lateinit var tvHistory: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var etFilterCommands: EditText
    private lateinit var tvCommandsGuide: TextView

    // v5.0 — голос
    private lateinit var tvVoiceStatus: TextView
    private lateinit var btnVoiceSweet: Button
    private lateinit var btnVoiceNatural: Button
    private lateinit var btnVoiceCalm: Button
    private lateinit var btnPickVoice: Button
    private lateinit var btnVoiceSample: Button

    // v5.0 — трейдинг
    private lateinit var tvTradingStatus: TextView
    private lateinit var spinnerAsset: Spinner
    private lateinit var tvSignal: TextView
    private lateinit var btnAnalyzeNow: Button
    private lateinit var btnOpenOlymp: Button
    private lateinit var switchAutoTrade: SwitchCompat
    private lateinit var switchAutoConfirm: SwitchCompat
    private lateinit var switchDemoOnly: SwitchCompat
    private lateinit var tvJournal: TextView
    private lateinit var btnClearJournal: Button

    // v5.0 — разрешения
    private lateinit var btnGrantAllPermissions: Button
    private lateinit var btnBatteryOptimization: Button
    private var updatingTradingUi = false
    private var lastJournalRefreshMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private lateinit var localCommandProcessor: CommandProcessor
    private var pendingActionAfterPermission: (() -> Unit)? = null
    private var waitingForSystemSpeechDialog = false
    private lateinit var fullGuideSections: List<String>

    private val busListener: () -> Unit = {
        runCatching { renderState() }.onFailure { safeSetupFailure("Экран состояния", it) }
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
        tvScreenControlStatus = findViewById(R.id.tvScreenControlStatus)
        btnEnableScreenControl = findViewById(R.id.btnEnableScreenControl)
        etCommand = findViewById(R.id.etCommand)
        btnSendText = findViewById(R.id.btnSendText)
        tvHistory = findViewById(R.id.tvHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        etFilterCommands = findViewById(R.id.etFilterCommands)
        tvCommandsGuide = findViewById(R.id.tvCommandsGuide)

        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        btnVoiceSweet = findViewById(R.id.btnVoiceSweet)
        btnVoiceNatural = findViewById(R.id.btnVoiceNatural)
        btnVoiceCalm = findViewById(R.id.btnVoiceCalm)
        btnPickVoice = findViewById(R.id.btnPickVoice)
        btnVoiceSample = findViewById(R.id.btnVoiceSample)

        tvTradingStatus = findViewById(R.id.tvTradingStatus)
        spinnerAsset = findViewById(R.id.spinnerAsset)
        tvSignal = findViewById(R.id.tvSignal)
        btnAnalyzeNow = findViewById(R.id.btnAnalyzeNow)
        btnOpenOlymp = findViewById(R.id.btnOpenOlymp)
        switchAutoTrade = findViewById(R.id.switchAutoTrade)
        switchAutoConfirm = findViewById(R.id.switchAutoConfirm)
        switchDemoOnly = findViewById(R.id.switchDemoOnly)
        tvJournal = findViewById(R.id.tvJournal)
        btnClearJournal = findViewById(R.id.btnClearJournal)

        btnGrantAllPermissions = findViewById(R.id.btnGrantAllPermissions)
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization)

        VoicePersona.init(this)
        TradingSettings.init(this)

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

        btnEnableScreenControl.setOnClickListener {
            Toast.makeText(
                this,
                "Найдите «Голосовой Помощник (Нелли)» в списке и включите переключатель!",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        updateScreenControlStatus()

        runCatching { setupVoiceCard() }.onFailure { safeSetupFailure("Голос", it) }
        runCatching { setupTradingCard() }.onFailure { safeSetupFailure("Трейдинг", it) }
        runCatching { setupPermissionsCard() }.onFailure { safeSetupFailure("Разрешения", it) }

        mainHandler.postDelayed({ showLastCrashIfAny() }, 700L)

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

    // ============================================================== v5.0 UI

    /** Показывает ошибку предыдущего запуска, чтобы её можно было прислать разработчику. */
    private fun showLastCrashIfAny() {
        if (isFinishing || isDestroyed) return
        val prefsLocal = getSharedPreferences("assistant_prefs", MODE_PRIVATE)
        val crash = prefsLocal.getString("last_crash", null)
        if (crash.isNullOrBlank()) return
        prefsLocal.edit().remove("last_crash").apply()

        try {
            tvDiagnostic.visibility = View.VISIBLE
            tvDiagnostic.text = "🐞 Прошлая ошибка: " + crash.lineSequence().take(3).joinToString(" ")
        } catch (_: Exception) {
        }

        try {
            val textView = TextView(this).apply {
                text = crash.take(6000)
                setTextIsSelectable(true)
                textSize = 11f
                setPadding(28, 28, 28, 28)
            }
            val scroll = android.widget.ScrollView(this).apply { addView(textView) }
            AlertDialog.Builder(this)
                .setTitle("Нелли: ошибка прошлого запуска")
                .setView(scroll)
                .setPositiveButton("Скопировать") { _, _ ->
                    copyTextToClipboard(crash)
                    Toast.makeText(this, "Ошибка скопирована", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        } catch (_: Exception) {
        }
    }

    private fun copyTextToClipboard(text: String) {
        try {
            val manager = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText("Нelly_error", text))
        } catch (_: Exception) {
        }
    }

    private fun safeSetupFailure(section: String, error: Throwable) {
        try {
            tvDiagnostic.visibility = View.VISIBLE
            tvDiagnostic.text = "⚠️ Раздел «$section» не загрузился: " +
                "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}. Остальное работает."
        } catch (_: Exception) {
        }
    }

    private fun setupVoiceCard() {
        updateVoiceUi()
        btnVoiceSweet.setOnClickListener { applyVoiceStyle(VoiceStyle.SWEET) }
        btnVoiceNatural.setOnClickListener { applyVoiceStyle(VoiceStyle.NATURAL) }
        btnVoiceCalm.setOnClickListener { applyVoiceStyle(VoiceStyle.CALM) }
        btnPickVoice.setOnClickListener { showVoicePicker() }
        btnVoiceSample.setOnClickListener {
            speakVoiceSample(
                "Привет! Меня зовут Нелли. Я помогу с маршрутом, сообщением и рынком. " +
                    "Теперь я говорю ${VoicePersona.style.labelRu.lowercase(Locale("ru", "RU"))} голосом."
            )
        }
    }

    private fun applyVoiceStyle(style: VoiceStyle) {
        VoicePersona.style = style
        localTts?.let {
            VoicePersona.selectVoice(it)
            VoicePersona.apply(it)
        }
        updateVoiceUi()
        speakVoiceSample(
            "Хорошо! Теперь у меня ${style.labelRu.lowercase(Locale("ru", "RU"))} голос. ${style.description}."
        )
    }

    private fun updateVoiceUi() {
        if (!this::tvVoiceStatus.isInitialized) return
        val style = VoicePersona.style
        val selectedName = try {
            localTts?.voice?.name
        } catch (_: Exception) {
            null
        } ?: "авто-выбор"
        tvVoiceStatus.text = "Характер: «${style.labelRu}» • тон ${"%.2f".format(Locale.US, style.pitch)} • " +
            "скорость ${"%.2f".format(Locale.US, style.rate)} • голос: $selectedName"
    }

    private fun showVoicePicker() {
        val engine = localTts
        if (engine == null || !localTtsReady) {
            Toast.makeText(this, "Синтезатор речи ещё загружается — попробуйте через секунду.", Toast.LENGTH_SHORT).show()
            return
        }
        val voices = VoicePersona.russianVoices(engine)
        if (voices.isEmpty()) {
            Toast.makeText(
                this,
                "На телефоне не найдено русских голосов. Установите «Речевые сервисы Google» и скачайте русский язык.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = voices.map { v ->
            val kind = if (v.isNetworkConnectionRequired) "сеть" else "офлайн"
            "${v.name}   ($kind)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите женский голос")
            .setItems(labels) { _, which ->
                val voice = voices[which]
                VoicePersona.preferredVoiceName = voice.name
                try {
                    engine.voice = voice
                } catch (_: Exception) {
                }
                VoicePersona.apply(engine)
                updateVoiceUi()
                speakVoiceSample("Так звучит голос ${voice.name}.")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun speakVoiceSample(text: String) {
        val spoken = VoicePersona.prepareTextForSpeech(text)
        if (AssistantStateBus.isServiceRunning) {
            startAssistantService(
                action = VoiceAssistantService.ACTION_SPEAK_TEXT,
                extraText = spoken
            )
            return
        }
        if (localTtsReady) {
            localTts?.let { VoicePersona.apply(it) }
            localTts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "voice_sample")
        } else {
            Toast.makeText(this, "Синтезатор ещё не готов.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------- трейдинг

    private fun setupTradingCard() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            MarketAssets.ALL.map { it.titleRu }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAsset.adapter = adapter
        val index = MarketAssets.ALL.indexOfFirst { it.id == TradingSettings.assetId }.coerceAtLeast(0)
        spinnerAsset.setSelection(index, false)
        spinnerAsset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val asset = MarketAssets.ALL.getOrNull(position) ?: return
                if (TradingSettings.assetId != asset.id) {
                    TradingSettings.assetId = asset.id
                    AssistantStateBus.updateTradingStatus("Актив: ${asset.titleRu}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        btnAnalyzeNow.setOnClickListener { runCommandFromButton("проанализируй рынок") }
        btnOpenOlymp.setOnClickListener { runCommandFromButton("открой олимп трейд") }

        switchAutoTrade.isChecked = TradingSettings.autoTrade
        switchAutoConfirm.isChecked = TradingSettings.autoConfirm
        switchDemoOnly.isChecked = TradingSettings.demoOnly

        switchAutoTrade.setOnCheckedChangeListener { _, checked ->
            if (updatingTradingUi) return@setOnCheckedChangeListener
            TradingSettings.autoTrade = checked
            if (checked) {
                TradingCoordinator.start(this)
            } else {
                TradingCoordinator.stop(this)
            }
        }
        switchAutoConfirm.setOnCheckedChangeListener { _, checked ->
            if (updatingTradingUi) return@setOnCheckedChangeListener
            TradingSettings.autoConfirm = checked
            Toast.makeText(
                this,
                if (checked) "Нелли будет входить в сделку без дополнительного подтверждения."
                else "Перед каждой сделкой Нелли спросит «подтверждаю сделку».",
                Toast.LENGTH_LONG
            ).show()
        }
        switchDemoOnly.setOnCheckedChangeListener { _, checked ->
            if (updatingTradingUi) return@setOnCheckedChangeListener
            TradingSettings.demoOnly = checked
            if (!checked) {
                Toast.makeText(
                    this,
                    "Демо-режим выключен: сделки пойдут на реальном счёте, если он открыт в OlympTrade.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnClearJournal.setOnClickListener {
            TradingSettings.clearJournal()
            refreshJournalUi(force = true)
            Toast.makeText(this, "Журнал сделок очищен.", Toast.LENGTH_SHORT).show()
        }

        refreshJournalUi()
        updateTradingUi()
    }

    private fun updateTradingUi() {
        val status = AssistantStateBus.tradingStatusText.ifBlank {
            "Актив: ${MarketAssets.byId(TradingSettings.assetId).titleRu}. Нажмите «Анализ сейчас»."
        }
        tvTradingStatus.text = status
        val signal = AssistantStateBus.lastSignalText
        tvSignal.text = signal
        tvSignal.visibility = if (signal.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        updatingTradingUi = true
        switchAutoTrade.isChecked = TradingSettings.autoTrade
        switchAutoConfirm.isChecked = TradingSettings.autoConfirm
        switchDemoOnly.isChecked = TradingSettings.demoOnly
        updatingTradingUi = false
    }

    private fun refreshJournalUi(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastJournalRefreshMs < 2500L) return
        lastJournalRefreshMs = now
        tvJournal.text = TradingSettings.journalText()
    }

    private fun runCommandFromButton(command: String) {
        if (AssistantStateBus.isServiceRunning) {
            startAssistantService(
                action = VoiceAssistantService.ACTION_EXECUTE_TEXT,
                extraText = command
            )
        } else {
            executeLocalCandidates(listOf(command))
        }
    }

    // -------------------------------------------------------- разрешения

    private fun setupPermissionsCard() {
        btnGrantAllPermissions.setOnClickListener { requestAllPermissions() }
        btnBatteryOptimization.setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "Откройте настройки батареи вручную.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(
                this,
                "Все разрешения выданы! Осталось включить «Управление экраном» в спецвозможностях.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
            }
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS_ALL)
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
        updateScreenControlStatus()
        if (waitingForSystemSpeechDialog) {
            waitingForSystemSpeechDialog = false
        }
    }

    private fun updateScreenControlStatus() {
        val enabled = NellyAccessibilityService.isEnabled(this)
        if (enabled) {
            tvScreenControlStatus.text = "✓ АКТИВНО: Нелли видит открытое приложение, жмёт «В путь» в 2ГИС, отправляет сообщения в WhatsApp/Telegram и управляет кнопками «Вверх/Вниз» в OlympTrade!"
            tvScreenControlStatus.setTextColor(ContextCompat.getColor(this, R.color.statusRunning))
            btnEnableScreenControl.text = "Настройки управления экраном (включено ✓)"
        } else {
            tvScreenControlStatus.text = "Нажмите кнопку ниже один раз, чтобы Нелли понимала открытое приложение, управляла внутри 2ГИС и сама нажимала кнопку «Отправить» в мессенджерах."
            tvScreenControlStatus.setTextColor(ContextCompat.getColor(this, R.color.textSecondary))
            btnEnableScreenControl.text = "⚡ Включить управление 2ГИС и авто-отправку"
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
            // ВАЖНО: onInit вызывается движком синтеза речи в ФОНОВОМ (binder) потоке.
            // Трогать элементы интерфейса здесь нельзя — только через главный поток.
            localTtsReady = true
            val engine = localTts
            if (engine != null) {
                try {
                    engine.language = Locale("ru", "RU")
                    VoicePersona.selectVoice(engine)
                    VoicePersona.apply(engine)
                } catch (_: Exception) {
                }
            }
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    try {
                        updateVoiceUi()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun speakLocally(text: String) {
        if (localTtsReady && !AssistantStateBus.isServiceRunning) {
            val spoken = VoicePersona.prepareTextForSpeech(text)
            localTts?.let { VoicePersona.apply(it) }
            localTts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "local_utt")
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
        if (isFinishing || isDestroyed) return
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

        refreshJournalUi()
        updateTradingUi()

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
        if (!prefsPromptedV40Once()) {
            val extraPerms = mutableListOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extraPerms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            for (p in extraPerms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(p)
                }
            }
        }

        if (needed.isEmpty()) {
            onReady()
            return
        }

        markPromptedV40Once()
        pendingActionAfterPermission = onReady
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun prefsPromptedV40Once(): Boolean =
        getSharedPreferences("assistant_prefs", MODE_PRIVATE).getBoolean("prompted_v40_perms", false)

    private fun markPromptedV40Once() {
        getSharedPreferences("assistant_prefs", MODE_PRIVATE).edit()
            .putBoolean("prompted_v40_perms", true)
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
        if (requestCode == REQ_PERMISSIONS_ALL) {
            val denied = grantResults.count { it != PackageManager.PERMISSION_GRANTED }
            if (denied == 0) {
                Toast.makeText(
                    this,
                    "Все разрешения выданы! Теперь включите «Управление экраном».",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                }
            } else {
                Toast.makeText(
                    this,
                    "Выданы не все разрешения ($denied отклонено). Их можно включить в настройках приложения.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildGuideSections(): List<String> = listOf(
        """
📈 ТРЕЙДИНГ В OLYMPTRADE И МИЛЫЙ ГОЛОС (v5.0)
• Открыть: «Открой Олимп Трейд», «Запусти OlympTrade»
• Анализ 15-минутного рынка: «Проанализируй рынок», «Что по рынку», «Дай сигнал», «Стоит ли входить»
• Вход в сделку: «Купи вверх», «Купи вниз», «Зайди в сделку вверх», затем «Подтверждаю сделку»
• Автоторговля: «Торгуй за меня», «Включи автоторговлю» → Нелли проверяет рынок каждые 30 секунд
• Остановить: «Выключи автоторговлю», «Стоп торговля»
• Статус и журнал: «Статус торговли», «Журнал сделок», «Сделка выиграла», «Сделка проиграла»
• Актив: «Выбери актив золото», «Торгуй биткоин», «Переключи на евро доллар»
• Счёт: «Включи демо режим», «Торгуй на реальные деньги»
• Ставка и порог: «Ставка 50», «Уверенность 75», «Экспирация 15 минут»
• Голос: «Говори милым голосом», «Обычным голосом», «Спокойным голосом», «Какой у тебя голос»
        """.trimIndent(),
        """
🗺 УПРАВЛЕНИЕ 2ГИС, КООРДИНАТЫ, КРАТЧАЙШИЕ ПУТИ И РАБОТА В ОТКРЫТЫХ ПРИЛОЖЕНИЯХ (v4.0)
• Кратчайшие маршруты в 2ГИС: «Проложи маршрут в 2ГИС до аэропорта», «Кратчайший путь до Керуен Сити», «Пеший маршрут до парка», «Маршрут на автобусе до вокзала»
• Определение мест и расстояний: «Определи место улица Абая 15», «Сколько километров до вокзала», «Ближайшая аптека / заправка / банкомат / ЦОН»
• Мои GPS-координаты и адрес: «Мои координаты», «Где я сейчас», «Какой тут адрес» (называет вслух и копирует в буфер!)
• Отправка своих координат контакту: «Отправь мои координаты маме в Ватсап», «Скинь мою геолокацию Саше по СМС»
• Понимание открытого приложения на экране:
  — Открыли 2ГИС сами: скажите «Продолжи маршрут», «В путь», «Поехали», «Заверши маршрут», «Приблизь карту» или «Найди аптеку»!
  — Открыли чат в WhatsApp / Telegram сами: скажите «Отправь скоро буду» или «Напиши сюда я уже выхожу» — Нелли сама введёт и нажмёт Отправить!
  — В любом приложении: «Какое приложение открыто», «Что на экране», «Нажми кнопку …», «Листай вниз», «Следующее видео», «Шаг назад», «Домой»
• Полная авто-отправка сообщений без рук: «Отправь СМС маме я дома» (уходит сразу в фоне!), «Напиши маме в Ватсап скоро приеду», «Напиши Саше в Телеграм привет»
        """.trimIndent(),
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
        private const val REQ_PERMISSIONS_ALL = 1003
    }
}
