package com.bichinho.pet

import android.content.SharedPreferences
import kotlin.math.sqrt

enum class Heard { CALM, NEUTRAL, LOUD, STRANGER }

/**
 * Parte "que aprende" do bichinho, 100% no aparelho:
 *  - OnlineScaler: aprende a média/variação da SUA voz (volume, tom, timbre);
 *  - Autoencoder: rede neural treinada com a sua voz; erro alto = voz desconhecida.
 * Só é treinada durante a sessão "Ensinar minha voz" (e ajusta de leve quando reconhece você).
 */
class VoiceMind(private val prefs: SharedPreferences) {

    private val scaler = OnlineScaler(DIM)
    private val net = Autoencoder(DIM, 4)

    // estatística do erro em amostras SUAS
    private var errMean = 0.0
    private var errM2 = 0.0
    private var errN = 0

    var samples = 0
        private set
    var teachingUntil = 0L
        private set

    val teaching get() = teachingUntil > 0L
    val ready get() = samples >= MIN_SAMPLES

    init {
        prefs.getString("vm_scaler", null)?.let { scaler.load(it) }
        prefs.getString("vm_net", null)?.let { net.load(it) }
        samples = prefs.getInt("vm_samples", 0)
        errMean = prefs.getFloat("vm_err_mean", 0f).toDouble()
        errM2 = prefs.getFloat("vm_err_m2", 0f).toDouble()
        errN = prefs.getInt("vm_err_n", 0)
    }

    fun startTeaching(now: Long, ms: Long = 60_000L) {
        teachingUntil = now + ms
    }

    fun stopTeaching() {
        teachingUntil = 0L
        save()
    }

    fun teachProgress(now: Long): Float =
        if (!teaching) 0f else 1f - ((teachingUntil - now).toFloat() / 60_000f).coerceIn(0f, 1f)

    /** Processa uma janela de fala. Devolve o que o bichinho "entendeu". */
    fun hear(f: DoubleArray, now: Long): Heard {
        if (teaching) {
            scaler.update(f)
            val z = scaler.transform(f)
            // várias épocas por amostra, já que há poucos dados
            var e = 0.0
            repeat(10) { e = net.train(z, 0.03) }
            trackError(e)
            samples++
            if (now >= teachingUntil) stopTeaching()
            return Heard.CALM
        }
        if (!ready) return Heard.NEUTRAL

        val z = scaler.transform(f)
        val err = net.error(z)
        val sd = if (errN > 1) sqrt(errM2 / (errN - 1)) else 1.0
        val score = (err - errMean) / (sd + 1e-6)

        if (score > 3.5 && err > errMean * 2.5) return Heard.STRANGER

        // parece você: ajusta de leve a rede e o escalonador
        net.train(z, 0.002)
        trackError(err)

        // z[0] = volume em relação ao SEU normal; z[5] = tom
        val loud = z[0]
        val pitch = z[5]
        return when {
            loud > 1.6 || (loud > 1.0 && pitch > 1.4) -> Heard.LOUD
            loud < -0.2 && pitch < 0.8 -> Heard.CALM
            else -> Heard.NEUTRAL
        }
    }

    private fun trackError(e: Double) {
        errN++
        val d = e - errMean
        errMean += d / errN
        errM2 += d * (e - errMean)
    }

    fun save() {
        prefs.edit()
            .putString("vm_scaler", scaler.serialize())
            .putString("vm_net", net.serialize())
            .putInt("vm_samples", samples)
            .putFloat("vm_err_mean", errMean.toFloat())
            .putFloat("vm_err_m2", errM2.toFloat())
            .putInt("vm_err_n", errN)
            .apply()
    }

    companion object {
        const val DIM = 8
        const val MIN_SAMPLES = 40
    }
}
