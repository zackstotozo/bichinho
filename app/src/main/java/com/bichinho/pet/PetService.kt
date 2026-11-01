package com.bichinho.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class PetService : Service(), BrainListener, VoiceListener.Callback {

    companion object {
        const val CHANNEL = "bichinho"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.bichinho.pet.STOP"
        const val EXTRA_TEACH = "teach"
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var view: PetView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var brain: PetBrain
    private lateinit var prefs: SharedPreferences

    private var voice: VoiceListener? = null
    private lateinit var mind: VoiceMind
    private var micLevel = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val notes = ArrayList<View>()
    private val signs = ArrayList<View>()

    private var density = 1f
    private var screenW = 0
    private var screenH = 0

    private var px = 0f
    private var py = 0f
    private var tx = 0f
    private var ty = 0f
    private var walking = false
    private var dragging = false
    private var hopUntil = 0L
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    private val frameLoop = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 33)
        }
    }
    private val brainLoop = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Float) = v * density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_TEACH, false) == true &&
            ::mind.isInitialized && voice != null
        ) {
            val now = SystemClock.uptimeMillis()
            mind.startTeaching(now)
            brain.say("fala comigo! aprendendo sua voz...", 6000L, now)
        }
        return START_STICKY
    }

    private fun micAllowed() =
        prefs.getBoolean("listen", false) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("bichinho", MODE_PRIVATE)
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        density = resources.displayMetrics.density
        screenW = resources.displayMetrics.widthPixels
        screenH = resources.displayMetrics.heightPixels
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mind = VoiceMind(prefs)

        val temp = Temperament.values()[
            prefs.getInt("temp", Random.nextInt(Temperament.values().size)).also {
                prefs.edit().putInt("temp", it).apply()
            }
        ]
        brain = PetBrain(temp, this, prefs.getFloat("aff", 50f))

        view = PetView(this)
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        px = dp(20f)
        py = dp(120f)
        lp.x = px.toInt()
        lp.y = py.toInt()
        view.setOnTouchListener(touchListener())
        wm.addView(view, lp)

        if (micAllowed()) {
            val v = VoiceListener(this)
            if (v.start()) voice = v
        }

        running = true
        refreshView()
        handler.post(frameLoop)
        handler.post(brainLoop)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        voice?.stop()
        voice = null
        if (::mind.isInitialized) mind.save()
        if (::brain.isInitialized) {
            prefs.edit().putFloat("aff", brain.affection).apply()
        }
        (notes + signs).forEach { safeRemove(it) }
        notes.clear()
        signs.clear()
        if (::view.isInitialized) safeRemove(view)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- toque

    private fun touchListener() = object : View.OnTouchListener {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!moved && hypot(dx, dy) > dp(8f)) {
                        moved = true
                        dragging = true
                        walking = false
                        brain.drag(SystemClock.uptimeMillis())
                    }
                    if (moved) {
                        px = (ox + dx).coerceIn(0f, (screenW - dp(PetView.W_DP)).coerceAtLeast(0f))
                        py = (oy + dy).coerceIn(0f, (screenH - dp(PetView.H_DP)).coerceAtLeast(0f))
                        applyPosition(py)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val now = SystemClock.uptimeMillis()
                    if (!moved && e.action == MotionEvent.ACTION_UP) brain.poke(now)
                    if (moved) brain.settle(now)
                    dragging = false
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- loops

    private fun step() {
        if (dragging) return
        val now = SystemClock.uptimeMillis()
        var yOffset = 0f

        if (walking) {
            val dx = tx - px
            val dy = ty - py
            val dist = hypot(dx, dy)
            val speed = dp(2.4f) * (0.5f + brain.energy / 100f)
            if (dist <= speed) {
                px = tx
                py = ty
                walking = false
                brain.onArrived(now)
            } else {
                px += dx / dist * speed
                py += dy / dist * speed
                yOffset = (sin(now / 120.0) * dp(2f)).toFloat()
                view.look(if (abs(dx) < 1f) 0f else if (dx > 0) 1f else -1f, if (dy > 0) 0.5f else -0.5f)
            }
        }

        if (now < hopUntil) {
            val t = 1f - (hopUntil - now) / 900f
            yOffset -= abs(sin(t * Math.PI * 2)).toFloat() * dp(14f)
        }
        applyPosition(py + yOffset)
    }

    private fun applyPosition(yPos: Float) {
        val nx = px.toInt()
        val ny = yPos.toInt()
        if (nx != lastX || ny != lastY) {
            lastX = nx
            lastY = ny
            lp.x = nx
            lp.y = ny
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) brain.batteryPct = level * 100 / scale
            val status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            brain.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        brain.hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        brain.tick(now)
        if (!walking && Random.nextInt(100) < 30) {
            view.look(Random.nextFloat() * 2f - 1f, Random.nextFloat() * 1.2f - 0.6f)
        }
        refreshView()
    }

    private fun refreshView() {
        val m = brain.mood
        view.mood = m
        view.l1 = m.label
        view.l2 = brain.focusWord
        view.l3 = "JEITO: ${brain.temperament.label} • ${m.name}"
        view.l4 = brain.activity.label
        view.speech = brain.speech
        view.listening = voice != null
        view.level = micLevel
        view.teachProgress =
            if (::mind.isInitialized && mind.teaching) mind.teachProgress(SystemClock.uptimeMillis()) else -1f
        view.invalidate()
    }

    // ---------------------------------------------------------------- voz

    override fun onLevel(level: Float) {
        micLevel = level
        if (level > 0.25f) view.look(0f, -0.2f) // olha para você quando você fala
        view.level = level
    }

    override fun onWindow(f: DoubleArray) {
        val now = SystemClock.uptimeMillis()
        val wasTeaching = mind.teaching
        val heard = mind.hear(f, now)
        if (wasTeaching && !mind.teaching) {
            brain.say("aprendi sua voz! (${mind.samples} amostras)", 6000L, now)
        } else if (!wasTeaching) {
            brain.heard(heard, now)
        }
    }

    // ---------------------------------------------------------------- brain

    override fun onChanged() = refreshView()

    override fun onAction(a: Action) {
        when (a) {
            Action.WALK -> {
                tx = Random.nextFloat() * (screenW - dp(PetView.W_DP)).coerceAtLeast(1f)
                ty = dp(60f) + Random.nextFloat() *
                    (screenH - dp(PetView.H_DP) - dp(180f)).coerceAtLeast(1f)
                walking = true
            }
            Action.STOP -> walking = false
            Action.NOTE -> {
                walking = false
                addNote()
            }
            Action.PROTEST -> {
                walking = false
                spawnSigns()
            }
            Action.STRIKE_START -> {
                walking = false
                spawnSigns()
            }
            Action.STRIKE_END -> clearSigns()
            Action.FIDGET -> hopUntil = SystemClock.uptimeMillis() + 900L
        }
    }

    // ---------------------------------------------------------------- post-its e cartazes

    private fun overlayParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun makeLabel(text: String, bg: Int, fg: Int, maxWidthDp: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxWidth = dp(maxWidthDp).toInt()
            val pad = dp(10f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(3f)
            }
        }

    private fun addNote() {
        if (notes.size >= 4) removeExtra(notes, notes.first())
        val tv = makeLabel(brain.notePhrase(), 0xFFFFEB7A.toInt(), 0xFF2B2B2B.toInt(), 130f)
        val x = Random.nextInt((screenW - dp(150f)).toInt().coerceAtLeast(1))
        val y = dp(80f).toInt() + Random.nextInt((screenH - dp(300f)).toInt().coerceAtLeast(1))
        tv.setOnClickListener { removeExtra(notes, tv) }
        try {
            wm.addView(tv, overlayParams(x, y))
            notes.add(tv)
            handler.postDelayed({ removeExtra(notes, tv) }, 90_000L)
        } catch (_: Exception) {}
    }

    private fun spawnSigns() {
        clearSigns()
        val offsets = listOf(
            -dp(10f) to dp(PetView.H_DP) + dp(6f),
            dp(PetView.W_DP) / 2f - dp(40f) to -dp(46f),
            dp(40f) to dp(PetView.H_DP) + dp(52f)
        )
        offsets.forEach { (ox, oy) ->
            val x = (px + ox).toInt().coerceIn(0, (screenW - dp(150f).toInt()).coerceAtLeast(0))
            val y = (py + oy).toInt().coerceIn(0, (screenH - dp(80f).toInt()).coerceAtLeast(0))
            val tv = makeLabel(brain.signPhrase(), 0xFFFF5A36.toInt(), Color.WHITE, 140f)
            try {
                wm.addView(tv, overlayParams(x, y))
                signs.add(tv)
                // cartazes de greve ficam até a greve acabar
                handler.postDelayed({ if (!brain.strike) removeExtra(signs, tv) }, 45_000L)
            } catch (_: Exception) {}
        }
    }

    private fun clearSigns() {
        signs.toList().forEach { removeExtra(signs, it) }
    }

    private fun removeExtra(list: MutableList<View>, v: View) {
        if (list.remove(v)) safeRemove(v)
    }

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- notificação

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bichinho", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Bichinho acordado")
            .setContentText("Tá de olho em você.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Dormir", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (micAllowed()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                startForeground(NOTIF_ID, n, type)
            } catch (_: SecurityException) {
                // sem direito ao microfone agora: sobe só com o tipo básico
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
