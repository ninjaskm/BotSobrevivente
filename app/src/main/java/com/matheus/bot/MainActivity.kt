package com.matheus.bot

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var info: TextView
    private var pauseBtn: Button? = null

    /** Texto do botão de pausa conforme o estado do bot (null = captura ainda não iniciada). */
    private fun refreshPauseBtn(p: Boolean? = BotService.instance?.paused) {
        pauseBtn?.text = when (p) {
            null -> "⏸ Pausar o bot (inicie a captura primeiro)"
            true -> "▶ Continuar o bot"
            false -> "⏸ Pausar o bot"
        }
    }

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == Activity.RESULT_OK && d != null) {
            startForegroundService(
                Intent(this, BotService::class.java).putExtra("code", r.resultCode).putExtra("data", d)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val cfg = BotConfig(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4) }.also { col.addView(it) }
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also {
            col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(20) })
        }
        fun num(v: Int, signed: Boolean = false) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            setText(v.toString())
        }.also { col.addView(it) }

        label("Bot Sobrevivente").textSize = 22f
        info = label("")

        btn("1) Permitir sobreposição de tela") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2) Abrir acessibilidade") { openAccessibilityDetails() }
        btn("3) Iniciar captura") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
        }
        pauseBtn = btn("") {
            val s = BotService.instance
            if (s == null) Toast.makeText(this, "Inicie a captura primeiro", Toast.LENGTH_SHORT).show()
            else s.toggle()
            // O texto também muda sozinho pelo aviso do bot; isto só garante caso o aviso demore.
            info.postDelayed({ refreshPauseBtn() }, 300)
        }
        refreshPauseBtn()

        label("Lutas até receber (0 = só se o time morrer)")
        val eFights = num(cfg.fightTarget)
        label("Segundos até o poder 1 (0 = nunca)")
        val eP1 = num(cfg.power1AfterSec)
        label("Arrastos por rodada (padrão 6)")
        val eSwipes = num(cfg.comboSwipes)
        label("Arrastos por lote (1 = um por vez; máx. 4; padrão 2)")
        val eBatch = num(cfg.comboBatch)
        label("Pausa entre arrastos (ms; 0 = automática, +30%)")
        val eGap = num(cfg.comboGapMs)
        label("Brilho da ult (%, padrão 55)")
        val ePow = num(cfg.powerPct)
        label("Tamanho da captura (% da tela, 30 a 100; vale ao iniciar a captura)")
        val eCap = num(cfg.captureScalePct)
        label("Personagem pela arte (0 = só texto, 1 = arte ajuda quando o texto falha, 2 = também rejeita versão errada; padrão 2)")
        val eArtMode = num(cfg.artMode)
        label("Arte: aceitar a partir de (%, padrão 60)")
        val eArtAccept = num(cfg.artAccept)
        label("Arte: rejeitar abaixo de (%, padrão 30)")
        val eArtVeto = num(cfg.artVeto)
        label("Montagem de times: segundos arrastando para um lado antes de inverter (3 a 120; padrão 17)")
        val ePhase = num(cfg.phaseSec)
        val cBlock = CheckBox(this).apply { text = "Bloqueio (2 dedos)"; isChecked = cfg.block }
        val cUlt = CheckBox(this).apply { text = "Usar poder 3 (ult)"; isChecked = cfg.ult }
        val cBar = CheckBox(this).apply { text = "Minigame da barra (desligue se atrapalhar o combo)"; isChecked = cfg.barGame }
        val cRecord = CheckBox(this).apply { text = "Coletar quadros do combate (debug/dataset)"; isChecked = cfg.record }
        col.addView(cBlock); col.addView(cUlt); col.addView(cBar); col.addView(cRecord)
        btn("Salvar configurações") {
            cfg.fightTarget = eFights.text.toString().toIntOrNull() ?: 0
            cfg.power1AfterSec = eP1.text.toString().toIntOrNull() ?: 0
            cfg.comboSwipes = eSwipes.text.toString().toIntOrNull() ?: 6
            cfg.comboBatch = (eBatch.text.toString().toIntOrNull() ?: 2).coerceIn(1, 4)
            cfg.comboGapMs = eGap.text.toString().toIntOrNull() ?: 0
            cfg.powerPct = ePow.text.toString().toIntOrNull() ?: 55
            cfg.captureScalePct = (eCap.text.toString().toIntOrNull() ?: 100).coerceIn(30, 100)
            cfg.artMode = (eArtMode.text.toString().toIntOrNull() ?: 2).coerceIn(0, 2)
            cfg.artAccept = (eArtAccept.text.toString().toIntOrNull() ?: 60).coerceIn(10, 99)
            cfg.artVeto = (eArtVeto.text.toString().toIntOrNull() ?: 30).coerceIn(0, 90)
            cfg.phaseSec = (ePhase.text.toString().toIntOrNull() ?: 17).coerceIn(3, 120)
            cfg.block = cBlock.isChecked
            cfg.ult = cUlt.isChecked
            cfg.barGame = cBar.isChecked
            cfg.record = cRecord.isChecked
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val component = ComponentName(this, BotAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    /**
     * Abre direto a página do serviço "Bot Sobrevivente" (a do interruptor "Usar Bot Sobrevivente"), do mesmo jeito que o
     * app MasterDriver: SubSettings do Settings + ToggleAccessibilityServicePreferenceFragment, com os mesmos parâmetros
     * que o Settings usa ao abrir o serviço pela lista. Se o Settings recusar, abre a lista geral e avisa o caminho curto.
     */
    private fun openAccessibilityDetails() {
        val component = ComponentName(this, BotAccessibilityService::class.java)
        val flat = component.flattenToString()
        if (openServiceDetailsPage(component, flat)) return

        try {
            val args = Bundle().apply { putString(SETTINGS_FRAGMENT_ARGS_KEY, flat) }
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(SETTINGS_FRAGMENT_ARGS_KEY, flat)
                    .putExtra(SETTINGS_SHOW_FRAGMENT_ARGS, args)
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Toast.makeText(
            this,
            "Toque em 'Apps baixados' > 'Bot Sobrevivente' > ligue 'Usar Bot Sobrevivente'",
            Toast.LENGTH_LONG
        ).show()
    }

    @Suppress("DEPRECATION")
    private fun openServiceDetailsPage(component: ComponentName, flat: String): Boolean {
        val resolveInfo = packageManager.queryIntentServices(
            Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(packageName),
            PackageManager.GET_META_DATA
        ).firstOrNull { it.serviceInfo.name == component.className } ?: return false

        val title = resolveInfo.loadLabel(packageManager).toString()
        val args = Bundle().apply {
            putString("preference_key", flat)
            putString("title", title)
            putBoolean("checked", isAccessibilityServiceEnabled())
            putParcelable("resolve_info", resolveInfo)
            putParcelable("component_name", component)
            putInt("animated_image_res", 0)
        }
        return try {
            startActivity(
                Intent().apply {
                    setClassName("com.android.settings", "com.android.settings.SubSettings")
                    putExtra(":settings:show_fragment", TOGGLE_SERVICE_FRAGMENT)
                    putExtra(":android:show_fragment", TOGGLE_SERVICE_FRAGMENT)
                    putExtra(SETTINGS_SHOW_FRAGMENT_ARGS, args)
                    putExtra(":settings:show_fragment_title", title)
                }
            )
            true
        } catch (e: Exception) {
            Log.w("MainActivity", "Página do serviço indisponível, usando a tela geral", e)
            false
        }
    }

    private companion object {
        const val TOGGLE_SERVICE_FRAGMENT =
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
        const val SETTINGS_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
        const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }

    override fun onResume() {
        super.onResume()
        // O bot também pausa sozinho (ex.: time morto): o botão acompanha o estado real enquanto a tela está aberta.
        BotService.pauseListener = { p -> refreshPauseBtn(p) }
        refreshPauseBtn()
        autoEnableAccessibility()
        refreshInfo()
        info.postDelayed({
            // O serviço ainda não conectou após ~1,5 s: tenta de novo, agora forçando o religamento (tira e põe de volta na lista).
            if (BotAccessibilityService.instance == null) autoEnableAccessibility(force = true)
            refreshInfo()
        }, 1500)
        info.postDelayed({ refreshInfo() }, 3000)
    }

    /**
     * Liga a acessibilidade sozinho ao abrir o app (depois de cada Run), se o app tiver a permissão WRITE_SECURE_SETTINGS.
     * Essa permissão só se concede pelo adb, UMA vez: conceder_permissao_uma_vez.bat. Sem ela, não faz nada.
     */
    /** Resultado da última tentativa de ligar a acessibilidade sozinho (aparece na tela quando ela não liga). */
    private var autoMsg = ""

    private fun hasSecureSettingsPermission() =
        checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun autoEnableAccessibility(force: Boolean = false) {
        if (BotAccessibilityService.instance != null) { autoMsg = ""; return }
        if (!hasSecureSettingsPermission()) {
            autoMsg = "Sem a permissão WRITE_SECURE_SETTINGS: rode conceder_permissao_uma_vez.bat (celular por USB) e abra o app de novo."
            return
        }
        try {
            val comp = ComponentName(this, BotAccessibilityService::class.java)
            val full = comp.flattenToString()
            val short = comp.flattenToShortString()
            val isOurs = { s: String -> s.equals(full, ignoreCase = true) || s.equals(short, ignoreCase = true) }
            val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = cur.split(':').filter { it.isNotEmpty() }
            val listed = list.any(isOurs)
            if (!listed) {
                Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + full).joinToString(":"))
                autoMsg = "Ligando a acessibilidade sozinho..."
            } else if (force) {
                // Já está na lista mas o serviço não conectou (ex.: depois de um Run): tira e põe de volta para o sistema religar.
                Settings.Secure.putString(
                    contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.filterNot(isOurs).joinToString(":"),
                )
                info.postDelayed({
                    runCatching {
                        val now = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                            .split(':').filter { it.isNotEmpty() }
                        if (!now.any(isOurs)) {
                            Settings.Secure.putString(
                                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (now + full).joinToString(":"),
                            )
                        }
                        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    }
                    refreshInfo()
                }, 400)
                autoMsg = "Serviço na lista mas desconectado: religando..."
            } else {
                autoMsg = "Serviço já está na lista; esperando o sistema conectá-lo..."
            }
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: Exception) {
            autoMsg = "Erro ao ligar a acessibilidade: ${e.javaClass.simpleName}: ${e.message}"
            Log.w("MainActivity", "autoEnableAccessibility falhou", e)
        }
    }

    override fun onPause() {
        BotService.pauseListener = null
        super.onPause()
    }

    private fun refreshInfo() {
        refreshPauseBtn()
        val acc = BotAccessibilityService.instance != null
        info.text = "Sobreposição: " + (if (Settings.canDrawOverlays(this)) "OK" else "FALTA") +
            "\nAcessibilidade: " + (if (acc) "OK" else "FALTA") +
            "\nPermissão p/ ligar sozinho: " + (if (hasSecureSettingsPermission()) "OK" else "FALTA") +
            (if (!acc && autoMsg.isNotEmpty()) "\n$autoMsg" else "")
    }
}
