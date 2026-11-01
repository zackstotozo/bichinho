package com.bichinho.pet

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Média/desvio correndo (Welford) para normalizar as features ao SEU jeito de falar. */
class OnlineScaler(private val dim: Int) {
    var count = 0
        private set
    private val mean = DoubleArray(dim)
    private val m2 = DoubleArray(dim)

    fun update(x: DoubleArray) {
        count++
        for (i in 0 until dim) {
            val d = x[i] - mean[i]
            mean[i] += d / count
            m2[i] += d * (x[i] - mean[i])
        }
    }

    fun transform(x: DoubleArray): DoubleArray = DoubleArray(dim) { i ->
        val sd = if (count > 1) sqrt(m2[i] / (count - 1)) else 1.0
        (x[i] - mean[i]) / max(sd, 1e-3)
    }

    fun serialize(): String =
        "$count|" + mean.joinToString(",") + "|" + m2.joinToString(",")

    fun load(s: String) {
        val p = s.split("|")
        if (p.size != 3) return
        val me = p[1].split(",").mapNotNull { it.toDoubleOrNull() }
        val m = p[2].split(",").mapNotNull { it.toDoubleOrNull() }
        if (me.size != dim || m.size != dim) return
        count = p[0].toIntOrNull() ?: 0
        me.forEachIndexed { i, v -> mean[i] = v }
        m.forEachIndexed { i, v -> m2[i] = v }
    }
}

/**
 * Autoencoder pequeno (entrada -> camada escondida tanh -> reconstrução),
 * treinado online por gradiente descendente. Aprende o "formato" da sua voz;
 * erro de reconstrução alto = voz que ele não conhece.
 */
class Autoencoder(private val nIn: Int, private val nHid: Int) {
    private var w1 = Array(nHid) { DoubleArray(nIn) { Random.nextDouble(-0.3, 0.3) } }
    private var b1 = DoubleArray(nHid)
    private var w2 = Array(nIn) { DoubleArray(nHid) { Random.nextDouble(-0.3, 0.3) } }
    private var b2 = DoubleArray(nIn)

    private fun forward(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val h = DoubleArray(nHid) { j ->
            var s = b1[j]
            for (i in 0 until nIn) s += w1[j][i] * x[i]
            Math.tanh(s)
        }
        val y = DoubleArray(nIn) { i ->
            var s = b2[i]
            for (j in 0 until nHid) s += w2[i][j] * h[j]
            s
        }
        return h to y
    }

    /** Erro quadrático médio da reconstrução. */
    fun error(x: DoubleArray): Double {
        val (_, y) = forward(x)
        var e = 0.0
        for (i in 0 until nIn) e += (y[i] - x[i]) * (y[i] - x[i])
        return e / nIn
    }

    /** Um passo de SGD; devolve o erro antes do ajuste. */
    fun train(x: DoubleArray, lr: Double): Double {
        val (h, y) = forward(x)
        val dy = DoubleArray(nIn) { (y[it] - x[it]) * 2.0 / nIn }
        val dh = DoubleArray(nHid) { j ->
            var s = 0.0
            for (i in 0 until nIn) s += dy[i] * w2[i][j]
            s * (1.0 - h[j] * h[j])
        }
        for (i in 0 until nIn) {
            for (j in 0 until nHid) w2[i][j] -= lr * dy[i] * h[j]
            b2[i] -= lr * dy[i]
        }
        for (j in 0 until nHid) {
            for (i in 0 until nIn) w1[j][i] -= lr * dh[j] * x[i]
            b1[j] -= lr * dh[j]
        }
        var e = 0.0
        for (i in 0 until nIn) e += (y[i] - x[i]) * (y[i] - x[i])
        return e / nIn
    }

    fun serialize(): String = listOf(
        w1.joinToString(";") { it.joinToString(",") },
        b1.joinToString(","),
        w2.joinToString(";") { it.joinToString(",") },
        b2.joinToString(",")
    ).joinToString("#")

    fun load(s: String) {
        try {
            val p = s.split("#")
            if (p.size != 4) return
            val nw1 = p[0].split(";").map { r -> r.split(",").map { it.toDouble() }.toDoubleArray() }
            val nb1 = p[1].split(",").map { it.toDouble() }.toDoubleArray()
            val nw2 = p[2].split(";").map { r -> r.split(",").map { it.toDouble() }.toDoubleArray() }
            val nb2 = p[3].split(",").map { it.toDouble() }.toDoubleArray()
            if (nw1.size != nHid || nw1.any { it.size != nIn } || nb1.size != nHid) return
            if (nw2.size != nIn || nw2.any { it.size != nHid } || nb2.size != nIn) return
            w1 = nw1.toTypedArray()
            b1 = nb1
            w2 = nw2.toTypedArray()
            b2 = nb2
        } catch (_: Exception) {
            // arquivo corrompido: começa do zero
        }
    }
}
