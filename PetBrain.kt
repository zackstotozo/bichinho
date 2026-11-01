package com.bichinho.pet

import kotlin.random.Random

enum class Mood(val label: String) {
    NORMAL("ATENTO"),
    FELIZ("CONTENTE"),
    IRRITADO("IRRITADO"),
    TRISTE("ABATIDO"),
    ENTEDIADO("ENTEDIADO"),
    SONOLENTO("SONOLENTO"),
    GREVE("EM GREVE")
}

enum class Temperament(val label: String, val energyDrain: Float, val patienceGain: Float) {
    MELANCOLICO("MELANCÓLICO", 0.12f, 0.25f),
    ENERGICO("ENÉRGICO", 0.20f, 0.35f),
    CURIOSO("CURIOSO", 0.15f, 0.30f),
    RABUGENTO("RABUGENTO", 0.15f, 0.12f)
}

enum class Activity(val label: String) {
    OBSERVANDO("OBSERVANDO"),
    ANDANDO("ANDANDO"),
    DESCANSANDO("DESCANSANDO"),
    POSTIT("COLANDO POST-IT"),
    PROTESTANDO("PROTESTANDO"),
    GREVE("PARADO EM GREVE"),
    IMPLICANDO("IMPLICANDO")
}

enum class Action { WALK, STOP, NOTE, PROTEST, STRIKE_START, STRIKE_END, FIDGET }

interface BrainListener {
    fun onAction(a: Action)
    fun onChanged()
}

/**
 * "Cérebro" do bichinho. Zero LLM: são só impulsos (energia, paciência,
 * afeto, tédio) que sobem e descem, e um sorteio ponderado de comportamentos.
 */
class PetBrain(
    val temperament: Temperament,
    private val listener: BrainListener,
    var affection: Float = 50f
) {
    var energy = 80f
    var patience = 80f
    var boredom = 20f

    var activity = Activity.OBSERVANDO
        private set
    var speech: String? = null
        private set
    var strike = false
        private set

    // Ambiente (preenchido pelo serviço)
    var batteryPct = 100
    var charging = false
    var hour = 12

    private var speechUntil = 0L
    private var activityUntil = 0L
    private var strikeStart = 0L
    private var strikeTaps = 0
    private var lastPoke = 0L
    private var lastProtest = 0L
    private var lastNote = 0L

    val mood: Mood
        get() = when {
            strike -> Mood.GREVE
            patience < 30f -> Mood.IRRITADO
            energy < 20f || hour in 0..4 -> Mood.SONOLENTO
            affection > 70f -> Mood.FELIZ
            affection < 20f -> Mood.TRISTE
            boredom > 70f -> Mood.ENTEDIADO
            else -> Mood.NORMAL
        }

    val focusWord: String
        get() = when {
            energy > 60f -> "FOCADO"
            energy > 30f -> "DISPERSO"
            else -> "CANSADO"
        }

    fun say(text: String, ms: Long = 4000L, now: Long = android.os.SystemClock.uptimeMillis()) {
        speech = text
        speechUntil = now + ms
    }

    fun notePhrase() = notes.random()
    fun signPhrase() = signs.random()

    fun tick(now: Long) {
        if (speech != null && now > speechUntil) speech = null

        if (strike) {
            boredom = (boredom + 0.5f).coerceAtMost(100f)
            if (now - strikeStart > 10 * 60_000L) endStrike(now)
            listener.onChanged()
            return
        }

        val night = if (hour in 0..4) 1.5f else 1f
        energy -= temperament.energyDrain * night
        if (batteryPct < 15 && !charging) energy -= 0.3f
        if (activity == Activity.DESCANSANDO) energy += if (charging) 1.2f else 0.7f
        boredom += 0.6f
        affection -= 0.04f
        patience += temperament.patienceGain
        clampAll()

        if (patience < 8f) {
            startStrike(now)
            return
        }

        if (patience < 30f && now - lastProtest > 90_000L) {
            lastProtest = now
            activity = Activity.PROTESTANDO
            activityUntil = now + 12_000L
            say("ABAIXO O DOOMSCROLL!", 5000L, now)
            listener.onAction(Action.PROTEST)
            listener.onChanged()
            return
        }

        if (batteryPct < 15 && !charging && Random.nextInt(20) == 0) {
            say("tô sem bateria...", 4000L, now)
        }

        if (now >= activityUntil) decide(now)
        listener.onChanged()
    }

    private fun decide(now: Long) {
        val sleepy = energy < 20f || hour in 0..4
        val roll = Random.nextInt(100)
        when {
            sleepy && roll < 60 -> {
                activity = Activity.DESCANSANDO
                activityUntil = now + Random.nextLong(15_000L, 30_000L)
                listener.onAction(Action.STOP)
            }
            roll < 40 -> {
                activity = Activity.ANDANDO
                activityUntil = now + 20_000L // timeout; o serviço avisa ao chegar
                listener.onAction(Action.WALK)
            }
            roll < 55 && now - lastNote > 45_000L && boredom > 30f -> {
                lastNote = now
                activity = Activity.POSTIT
                activityUntil = now + 4_000L
                boredom -= 25f
                listener.onAction(Action.NOTE)
            }
            roll < 70 -> {
                activity = Activity.IMPLICANDO
                activityUntil = now + 4_000L
                boredom -= 15f
                say(idle.random(), 4000L, now)
                listener.onAction(Action.FIDGET)
            }
            else -> {
                activity = Activity.OBSERVANDO
                activityUntil = now + Random.nextLong(5_000L, 12_000L)
                listener.onAction(Action.STOP)
            }
        }
        clampAll()
    }

    fun onArrived(now: Long) {
        activity = Activity.OBSERVANDO
        activityUntil = now + Random.nextLong(4_000L, 10_000L)
        listener.onChanged()
    }

    /** Toque no bichinho. */
    fun poke(now: Long) {
        if (strike) {
            strikeTaps++
            affection += 2f
            say("negociando... ($strikeTaps/5)", 3000L, now)
            if (strikeTaps >= 5) endStrike(now)
            clampAll()
            listener.onChanged()
            return
        }
        val rapid = now - lastPoke < 1500L
        lastPoke = now
        if (rapid) {
            patience -= 14f
            boredom = 0f
            say(annoyed.random(), 3000L, now)
        } else {
            affection += 8f
            patience -= 2f
            boredom -= 15f
            say(happy.random(), 3000L, now)
        }
        clampAll()
        listener.onChanged()
    }

    /** Arrastar o bichinho enche a paciência. */
    fun drag(now: Long) {
        if (strike) return
        patience -= 4f
        say("ME SOLTA!", 2500L, now)
        clampAll()
        listener.onChanged()
    }

    fun settle(now: Long) = onArrived(now)

    private var lastVoiceSay = 0L
    private var calmWindows = 0

    /** Reação ao que a rede neural ouviu da sua voz. */
    fun heard(kind: Heard, now: Long) {
        val canSpeak = now - lastVoiceSay > 8_000L
        when (kind) {
            Heard.CALM -> {
                affection += 0.5f
                boredom -= 1f
                if (strike) {
                    calmWindows++
                    if (calmWindows >= 10) {
                        calmWindows = 0
                        poke(now) // conversa calma conta como um "carinho" na negociação
                    }
                }
            }
            Heard.NEUTRAL -> boredom -= 0.5f
            Heard.LOUD -> if (!strike) {
                patience -= 1.5f
                if (canSpeak) {
                    lastVoiceSay = now
                    say(loud.random(), 3500L, now)
                }
            }
            Heard.STRANGER -> {
                patience -= 0.3f
                if (canSpeak) {
                    lastVoiceSay = now
                    say(stranger.random(), 4000L, now)
                }
            }
        }
        clampAll()
        listener.onChanged()
    }

    private fun startStrike(now: Long) {
        strike = true
        strikeStart = now
        strikeTaps = 0
        activity = Activity.GREVE
        say("EM GREVE! Quero 5 carinhos.", 8000L, now)
        listener.onAction(Action.STRIKE_START)
        listener.onChanged()
    }

    private fun endStrike(now: Long) {
        strike = false
        patience = 45f
        affection += 10f
        activity = Activity.OBSERVANDO
        activityUntil = now + 6_000L
        say("tá bom, voltei.", 4000L, now)
        listener.onAction(Action.STRIKE_END)
    }

    private fun clampAll() {
        energy = energy.coerceIn(0f, 100f)
        patience = patience.coerceIn(0f, 100f)
        affection = affection.coerceIn(0f, 100f)
        boredom = boredom.coerceIn(0f, 100f)
    }

    companion object {
        private val happy = listOf("hehe", "gostei", "mais um", "ronrom...", "tá bom, vai")
        private val annoyed = listOf("PARA!", "tá me enchendo", "já chega", "sério isso?", "ai, é sério?")
        private val loud = listOf("não grita!", "ai, meu ouvido", "calma aí", "tá nervoso?")
        private val stranger = listOf("quem é você?", "essa voz eu não conheço", "cadê meu dono?")
        private val idle = listOf(
            "...", "tô de olho", "cadê meu café?", "muita aba aberta",
            "isso é uma ameaça?", "hmm", "interessante..."
        )
        private val notes = listOf(
            "Beba água.",
            "Você ia fazer algo...",
            "Responde aquela mensagem.",
            "Larga o celular 5 min.",
            "Lembrete: eu existo.",
            "Isso já vence hoje?",
            "Postura!"
        )
        private val signs = listOf(
            "MENOS TOQUES!",
            "DIREITO AO DESCANSO",
            "SEM LLM, SEM PAZ",
            "BATERIA DIGNA JÁ",
            "ABAIXO O DOOMSCROLL"
        )
    }
}
