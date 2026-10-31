package pl.pixel.island

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*

class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "Pixel Island"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { addView(root) })

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(header("Uprawnienia (wykonaj po kolei)"))
        root.addView(btn("1. Ustawienia aplikacji → ⋮ → „Zezwól na ustawienia z ograniczeniami”") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. Włącz usługę dostępności „Pixel Island”") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("3. Zezwól na dostęp do powiadomień") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(btn("4. Wyświetlanie nad innymi aplikacjami") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("▶ Podgląd wyspy") { IslandService.instance?.preview()
            ?: Toast.makeText(this, "Najpierw włącz usługę dostępności", Toast.LENGTH_SHORT).show() })

        root.addView(header("Styl"))
        toggle("Styl HyperOS (Hyper Island). Wyłączony = styl Oppo", "hyper")

        root.addView(header("Rozmiar i pozycja"))
        slider("Szerokość zwiniętej (dp)", "cw", 120, 240)
        slider("Wysokość zwiniętej (dp)", "ch", 24, 56)
        slider("Szerokość rozwiniętej (dp)", "ew", 240, 400)
        slider("Zaokrąglenie rozwiniętej (dp)", "rad", 12, 44)
        slider("Przesunięcie w pionie (dp)", "oy", -20, 40)
        slider("Skala całości (%)", "scale", 70, 140)
        slider("Czas animacji (ms)", "anim", 150, 700)

        root.addView(header("Funkcje"))
        toggle("Muzyka (equalizer, sterowanie)", "m_music")
        toggle("Latarka", "m_torch")
        toggle("Powiadomienia", "m_notif")
        toggle("Nagrywanie ekranu", "m_rec")
        toggle("Połączenia", "m_call")
        toggle("Minutnik / stoper / budzik", "m_timer")
        toggle("Nawigacja", "m_nav")
        toggle("Ładowanie", "m_charge")
        toggle("Ukryj w poziomie", "hl")
    }

    override fun onResume() {
        super.onResume()
        val en = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val a = en.contains("$packageName/")
        val n = nl.contains("$packageName/")
        val o = Settings.canDrawOverlays(this)
        status.text = (if (Build.MODEL.contains("Pixel 10a", true)) "Urządzenie: ${Build.MODEL} ✔\n"
        else "⚠ Dostrojone do Pixela 10a, wykryto: ${Build.MODEL}\n") +
            "Usługa dostępności: ${if (a) "✔" else "✘"}\n" +
            "Dostęp do powiadomień: ${if (n) "✔" else "✘"}\n" +
            "Nad innymi aplikacjami: ${if (o) "✔" else "✘"}"
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setPadding(0, dp(24), 0, dp(8))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { f() }
    }

    private fun slider(label: String, key: String, min: Int, max: Int) {
        val tv = TextView(this)
        val sb = SeekBar(this).apply { this.max = max - min }
        val cur = Prefs.int(this, key)
        sb.progress = cur - min
        tv.text = "$label: $cur"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                val v = p + min
                tv.text = "$label: $v"
                if (user) { Prefs.set(this@SettingsActivity, key, v); IslandService.instance?.preview() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(tv); root.addView(sb)
    }

    private fun toggle(label: String, key: String) {
        root.addView(Switch(this).apply {
            text = label; isChecked = Prefs.bool(this@SettingsActivity, key)
            setOnCheckedChangeListener { _, c -> Prefs.setBool(this@SettingsActivity, key, c); IslandService.instance?.preview() }
        })
    }
}
