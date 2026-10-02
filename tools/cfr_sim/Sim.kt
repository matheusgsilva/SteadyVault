import com.steadyvault.camera.capture.recorder.CfrTimeResampler
import kotlin.random.Random
fun main() {
    val interval = 1_000_000_000L / 60L
    for (seed in 1..3) for (jit in listOf(0.03, 0.08, 0.15)) for (drift in listOf(0.0, 0.0003, -0.0003, 0.003, -0.003)) {
        val rnd = Random(seed)
        val pos = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until 3600) {
            t += 1.0 + drift
            if (rnd.nextDouble() < 0.05) { t += 1.0 + drift } // frame perdido
            pos.add(t + rnd.nextDouble(-jit, jit))
        }
        val r = CfrTimeResampler(interval)
        r.start((pos[0] * interval).toLong())
        var zero = 0; var tot = 0; var skipped = 0; var blends = 0
        for (i in 1 until pos.size) {
            val n = r.plan((pos[i - 1] * interval).toLong(), (pos[i] * interval).toLong())
            if (n == 0) skipped++
            for (k in 0 until n) { val a = r.alphaAt(k); tot++; if (a <= 0.12f) zero++; else if (a < 1f) blends++ }
        }
        println("seed=$seed jit=$jit drift=$drift outputs=$tot repeats=$zero dropped=$skipped blends=$blends")
    }
}
