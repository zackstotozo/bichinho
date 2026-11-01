package com.bichinho.pet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var btnPerm: Button
    private lateinit var btnToggle: Button
    private lateinit var btnTeach: Button
    private lateinit var chkListen: CheckBox

    private val green = 0xFF00FF66.toInt()
    private var pendingTeach = false

    private val prefs get() = getSharedPreferences("bichinho", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF050805.toInt())
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(text("> BICHINHO_", 26f, true))
        root.addView(text(
            "Um bichinho de olhos verdes que anda pela tela, cola post-it, " +
                "se irrita com toques repetidos, organiza protesto e entra em greve. " +
                "Sem LLM: só impulsos, humor e uma rede neural pequena que aprende a sua voz.",
            14f, false
        ).apply { setPadding(0, pad / 2, 0, pad) })

        status = text("", 14f, false).apply { setPadding(0, 0, 0, pad) }
        root.addView(status)

        btnPerm = button("Dar permissão de sobreposição") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btnToggle = button("") { toggle() }
        root.addView(btnPerm)
        root.addView(btnToggle)

        // ---- voz
        root.addView(text("VOZ", 16f, true).apply { setPadding(0, pad, 0, pad / 3) })
        chkListen = CheckBox(this).apply {
            text = "Ouvir minha voz (processa no aparelho; nada é gravado nem enviado)"
            setTextColor(green)
            setOnClickListener { onListenClicked(isChecked) }
        }
        root.addView(chkListen)
        btnTeach = button("Ensinar minha voz (60 s)") { onTeachClicked() }
        root.addView(btnTeach)
        voiceStatus = text("", 12f, false).apply {
            setPadding(0, pad / 3, 0, 0)
            setTextColor(0xFF1FD18A.toInt())
        }
        root.addView(voiceStatus)

        root.addView(text(
            "Dicas: toque 1x = carinho. Toques rápidos = irritação. " +
                "Arrastar = ele odeia. Depois de aprender, ele estranha vozes que não são a sua, " +
                "reclama se você gritar e se acalma com conversa tranquila. " +
                "Em greve, 5 toques lentos (ou conversa calma) negociam a volta.",
            12f, false
        ).apply { setPadding(0, pad, 0, 0); setTextColor(0xFF1FD18A.toInt()) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF050805.toInt())
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------ ações

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggle() {
        if (!Settings.canDrawOverlays(this)) return
        if (PetService.running) {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_STOP))
        } else {
            startPet(false)
        }
        btnToggle.postDelayed({ refresh() }, 400)
    }

    private fun startPet(teach: Boolean) {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)
        startForegroundService(
            Intent(this, PetService::class.java).putExtra(PetService.EXTRA_TEACH, teach)
        )
    }

    /** O microfone só entra no serviço no início, então reinicia o bichinho. */
    private fun restartPet(teach: Boolean) {
        if (!Settings.canDrawOverlays(this)) return
        stopService(Intent(this, PetService::class.java))
        btnToggle.postDelayed({
            startPet(teach)
            btnToggle.postDelayed({ refresh() }, 500)
        }, 500)
    }

    private fun onListenClicked(checked: Boolean) {
        if (checked && !hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        prefs.edit().putBoolean("listen", checked).apply()
        if (PetService.running) restartPet(false)
        refresh()
    }

    private fun onTeachClicked() {
        if (!Settings.canDrawOverlays(this)) return
        if (!hasMic()) {
            pendingTeach = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        prefs.edit().putBoolean("listen", true).apply()
        if (PetService.running) restartPet(true) else startPet(true)
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != 2) return
        val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
        prefs.edit().putBoolean("listen", ok).apply()
        if (ok) {
            if (pendingTeach) onTeachClicked()
            else if (PetService.running) restartPet(false)
        }
        pendingTeach = false
        refresh()
    }

    private fun refresh() {
        val can = Settings.canDrawOverlays(this)
        btnPerm.visibility = if (can) View.GONE else View.VISIBLE
        btnToggle.isEnabled = can
        btnToggle.text = if (PetService.running) "Mandar dormir" else "Acordar o bichinho"
        status.text = when {
            !can -> "Status: sem permissão de sobreposição."
            PetService.running -> "Status: acordado e de olho."
            else -> "Status: dormindo."
        }

        val listen = prefs.getBoolean("listen", false) && hasMic()
        chkListen.isChecked = listen
        btnTeach.isEnabled = can
        val n = prefs.getInt("vm_samples", 0)
        voiceStatus.text = when {
            n >= VoiceMind.MIN_SAMPLES -> "Voz aprendida: $n amostras. Ensine de novo para refinar."
            n > 0 -> "Aprendendo: $n amostras (precisa de ${VoiceMind.MIN_SAMPLES}+). Fale mais tempo."
            else -> "Ainda não conheço sua voz. Toque em \"Ensinar minha voz\" e fale por 60 s."
        }
    }

    // ------------------------------------------------------------ helpers

    private fun text(s: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        setTextColor(green)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.BLACK)
        setBackgroundColor(green)
        setOnClickListener { onClick() }
    }
}
