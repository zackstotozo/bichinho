package com.bichinho.pet

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Escuta o microfone, detecta fala e entrega só NÚMEROS (8 features por janela
 * de ~0,5 s). O áudio nunca é gravado em disco nem enviado para lugar nenhum.
 */
class VoiceListener(private val callback: Callback) {

    interface Callback {
        /** Nível de voz 0..1 (para a boca do bichinho). */
        fun onLevel(level: Float)

        /** Janela de fala com 8 features. */
        fun onWindow(f: DoubleArray)
    }

    companion object {
        const val SR = 16000
        const val FRAME = 1024
        const val WINDOW_FRAMES = 8
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // a permissão é checada antes de chamar start()
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FRAME * 4)
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        running = true
        thread = Thread({ loop(rec) }, "bichinho-ouvido").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(FRAME)
        val x = DoubleArray(FRAME)
        val window = ArrayList<DoubleArray>()
        var noise = 0.005
        var silentFrames = 0
        val hann = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        try {
            rec.startRecording()
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val n = rec.read(buf, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) continue

                var sum = 0.0
                for (i in 0 until FRAME) {
                    x[i] = buf[i] / 32768.0
                    sum += x[i] * x[i]
                }
                val rms = sqrt(sum / FRAME)
                val active = rms > max(0.015, noise * 3.0)

                if (!active) {
                    noise += (rms - noise) * 0.05
                    silentFrames++
                    if (silentFrames > 6) window.clear()
                    postLevel(0f)
                    continue
                }
                silentFrames = 0
                postLevel(min(1f, (rms * 8).toFloat()))

                var zc = 0
                for (i in 1 until FRAME) if ((x[i] >= 0) != (x[i - 1] >= 0)) zc++
                val zcr = zc.toDouble() / FRAME

                val centroid = spectralCentroid(x, hann) / 1000.0
                val pitch = pitchHz(x, sum)

                window.add(doubleArrayOf(rms, zcr, centroid, pitch))
                if (window.size >= WINDOW_FRAMES) {
                    val feats = summarize(window)
                    window.clear()
                    main.post { callback.onWindow(feats) }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun postLevel(l: Float) = main.post { callback.onLevel(l) }

    /** [rms médio, rms desvio, zcr médio, centroide médio, centroide desvio, pitch médio, pitch desvio, % voiced] */
    private fun summarize(w: List<DoubleArray>): DoubleArray {
        val n = w.size.toDouble()
        fun mean(k: Int) = w.sumOf { it[k] } / n
        fun sd(k: Int, m: Double) = sqrt(w.sumOf { (it[k] - m) * (it[k] - m) } / n)
        val rmsM = mean(0)
        val cenM = mean(2)
        val voiced = w.filter { it[3] > 0 }
        val pM = if (voiced.isEmpty()) 0.0 else voiced.sumOf { it[3] } / voiced.size / 100.0
        val pS = if (voiced.size < 2) 0.0 else
            sqrt(voiced.sumOf { (it[3] / 100.0 - pM) * (it[3] / 100.0 - pM) } / voiced.size)
        return doubleArrayOf(
            rmsM, sd(0, rmsM), mean(1), cenM, sd(2, cenM), pM, pS, voiced.size / n
        )
    }

    private fun spectralCentroid(x: DoubleArray, hann: DoubleArray): Double {
        val re = DoubleArray(FRAME) { x[it] * hann[it] }
        val im = DoubleArray(FRAME)
        fft(re, im)
        var num = 0.0
        var den = 0.0
        for (k in 1 until FRAME / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            num += mag * k * SR.toDouble() / FRAME
            den += mag
        }
        return if (den < 1e-9) 0.0 else num / den
    }

    /** Autocorrelação simples: 70–350 Hz. 0 = não vozeado. */
    private fun pitchHz(x: DoubleArray, energy: Double): Double {
        if (energy < 1e-6) return 0.0
        val minLag = SR / 350
        val maxLag = SR / 70
        var best = 0.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until FRAME - lag) s += x[i] * x[i + lag]
            val r = s / energy
            if (r > best) {
                best = r
                bestLag = lag
            }
        }
        return if (best > 0.3 && bestLag > 0) SR.toDouble() / bestLag else 0.0
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
