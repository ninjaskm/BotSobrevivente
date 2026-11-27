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
        fun num(v: Int) = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(v.toString()) }.also { col.addView(it) }

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

        label("Lutas até receber (0 = só se o time morrer)")
        val eFights = num(cfg.fightTarget)
        label("Segundos até o poder 1 (0 = nunca)")
        val eP1 = num(cfg.power1AfterSec)
        label("Atraso do arremesso (ms)")
        val eToss = num(cfg.tossDelayMs)
        label("Arrastos por rodada (padrão 8)")
        val eSwipes = num(cfg.comboSwipes)
        label("Pausa entre arrastos (ms, padrão 0)")
        val eGap = num(cfg.comboGapMs)
        label("Brilho da ult (%, padrão 55)")
        val ePow = num(cfg.powerPct)
        val cBlock = CheckBox(this).apply { text = "Bloqueio (2 dedos)"; isChecked = cfg.block }
        val cUlt = CheckBox(this).apply { text = "Usar poder 3 (ult)"; isChecked = cfg.ult }
        col.addView(cBlock); col.addView(cUlt)
        btn("Salvar configurações") {
            cfg.fightTarget = eFights.text.toString().toIntOrNull() ?: 0
            cfg.power1AfterSec = eP1.text.toString().toIntOrNull() ?: 0
            cfg.tossDelayMs = eToss.text.toString().toIntOrNull() ?: 600
            cfg.comboSwipes = eSwipes.text.toString().toIntOrNull() ?: 8
            cfg.comboGapMs = eGap.text.toString().toIntOrNull() ?: 0
            cfg.powerPct = ePow.text.toString().toIntOrNull() ?: 55
            cfg.block = cBlock.isChecked
            cfg.ult = cUlt.isChecked
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
        info.text = "Sobreposição: " + (if (Settings.canDrawOverlays(this)) "OK" else "FALTA") +
            "\nAcessibilidade: " + (if (BotAccessibilityService.instance != null) "OK" else "FALTA")
    }
}
