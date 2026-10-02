package com.steadyvault.camera.core.camera

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * Main4 lightweight pose-based focus target selector.
 *
 * This never changes video pixels. It only chooses a normalized point that Camera2
 * can use as an AF/AE metering target. The fast ML Kit pose model runs on throttled
 * preview snapshots, not on the 4K encoder stream.
 */
class SmartPoseFocusAnalyzer : Closeable {

    data class Target(
        val x: Float,
        val y: Float,
        val kind: Kind,
        val confidence: Float
    )

    enum class Kind {
        HAND_TO_MOUTH,
        FACE,
        HANDS,
        FEET,
        BODY,
        /** Pele (mão/pé) que acabou de entrar no quadro; achada por cor, sem pose. */
        SKIN
    }

    private val busy = AtomicBoolean(false)
    private val skinFinder = SkinBlobFinder()
    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    fun analyze(bitmap: Bitmap, rotationDegrees: Int = 0, onResult: (Target?) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            onResult(null)
            return
        }

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        // Atualiza o fundo de pele a cada análise (barato); só vale se a pose não achar ninguém.
        val skin = runCatching { skinFinder.find(bitmap) }.getOrNull()
        val image = InputImage.fromBitmap(bitmap, normalizedRotation)
        detector.process(image)
            .addOnSuccessListener { pose ->
                val rotated = normalizedRotation == 90 || normalizedRotation == 270
                val analysisWidth = if (rotated) bitmap.height else bitmap.width
                val analysisHeight = if (rotated) bitmap.width else bitmap.height
                val poseTarget = selectTarget(pose, analysisWidth, analysisHeight)
                val skinTarget = skin?.let { blob ->
                    val point = skinFinder.toUpright(blob, normalizedRotation)
                    Target(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f), Kind.SKIN, 0.5f)
                }
                // Mão/pé novos no quadro valem mais que um corpo/mãos "adivinhados" pela pose; rosto
                // e mão-na-boca (agora só com rosto confiável) continuam com prioridade.
                val poseIsSolid = poseTarget != null &&
                    (poseTarget.kind == Kind.FACE || poseTarget.kind == Kind.HAND_TO_MOUTH)
                onResult(if (poseIsSolid) poseTarget else (skinTarget ?: poseTarget))
            }
            .addOnFailureListener {
                onResult(null)
            }
            .addOnCompleteListener {
                busy.set(false)
            }
    }

    private fun selectTarget(pose: Pose, width: Int, height: Int): Target? {
        if (width <= 0 || height <= 0) return null

        // O modelo "inventa" boca/rosto em volta de uma mão sozinha (mão virava "mão na boca" e o
        // foco ia para um ponto entre a mão e a boca falsa). Rosto só vale com pelo menos 2
        // marcos de olhos/nariz/orelhas bem confiáveis; sem isso, nada de rosto nem de boca.
        val faceIsCredible = listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR
        ).count { type -> (pose.getPoseLandmark(type)?.inFrameLikelihood ?: 0f) >= FACE_LIKELIHOOD } >= 2

        val mouth = if (!faceIsCredible) null else averageVisible(
            pose,
            PoseLandmark.LEFT_MOUTH,
            PoseLandmark.RIGHT_MOUTH,
            minLikelihood = FACE_LIKELIHOOD
        )
        val face = if (!faceIsCredible) null else averageVisible(
            pose,
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_MOUTH,
            PoseLandmark.RIGHT_MOUTH,
            minLikelihood = MIN_LIKELIHOOD
        )

        val leftHand = averageVisible(
            pose,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.LEFT_INDEX,
            PoseLandmark.LEFT_THUMB,
            PoseLandmark.LEFT_PINKY,
            minLikelihood = MIN_LIKELIHOOD
        )
        val rightHand = averageVisible(
            pose,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.RIGHT_INDEX,
            PoseLandmark.RIGHT_THUMB,
            PoseLandmark.RIGHT_PINKY,
            minLikelihood = MIN_LIKELIHOOD
        )

        val handNearMouth = mouth?.let { mouthPoint ->
            listOfNotNull(leftHand, rightHand)
                .map { hand -> hand to normalizedDistance(hand.point, mouthPoint.point, width, height) }
                .filter { (_, distance) -> distance <= HAND_TO_MOUTH_DISTANCE }
                .minByOrNull { it.second }
        }

        if (handNearMouth != null && mouth != null) {
            val hand = handNearMouth.first
            val point = midpoint(hand.point, mouth.point)
            return target(
                point,
                width,
                height,
                Kind.HAND_TO_MOUTH,
                minOf(hand.confidence, mouth.confidence)
            )
        }

        if (face != null) {
            return target(face.point, width, height, Kind.FACE, face.confidence)
        }

        val body = averageVisible(
            pose,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            minLikelihood = MIN_LIKELIHOOD
        )
        if (body != null) {
            return target(body.point, width, height, Kind.BODY, body.confidence)
        }

        val hands = averageCandidates(leftHand, rightHand)
        if (hands != null) {
            return target(hands.point, width, height, Kind.HANDS, hands.confidence)
        }

        val feet = averageVisible(
            pose,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.LEFT_HEEL,
            PoseLandmark.RIGHT_HEEL,
            PoseLandmark.LEFT_FOOT_INDEX,
            PoseLandmark.RIGHT_FOOT_INDEX,
            minLikelihood = MIN_LIKELIHOOD
        )
        if (feet != null) {
            return target(feet.point, width, height, Kind.FEET, feet.confidence)
        }

        return null
    }

    private data class Candidate(
        val point: PointF,
        val confidence: Float
    )

    private fun averageVisible(
        pose: Pose,
        vararg types: Int,
        minLikelihood: Float
    ): Candidate? {
        val landmarks = types.asList().mapNotNull { type ->
            pose.getPoseLandmark(type)?.takeIf { it.inFrameLikelihood >= minLikelihood }
        }
        if (landmarks.isEmpty()) return null
        val x = landmarks.sumOf { it.position.x.toDouble() }.toFloat() / landmarks.size
        val y = landmarks.sumOf { it.position.y.toDouble() }.toFloat() / landmarks.size
        val confidence = landmarks.map { it.inFrameLikelihood }.average().toFloat()
        return Candidate(PointF(x, y), confidence)
    }

    private fun averageCandidates(vararg candidates: Candidate?): Candidate? {
        val values = candidates.filterNotNull()
        if (values.isEmpty()) return null
        return Candidate(
            point = PointF(
                values.sumOf { it.point.x.toDouble() }.toFloat() / values.size,
                values.sumOf { it.point.y.toDouble() }.toFloat() / values.size
            ),
            confidence = values.map { it.confidence }.average().toFloat()
        )
    }

    private fun normalizedDistance(a: PointF, b: PointF, width: Int, height: Int): Float {
        val dx = (a.x - b.x) / width.coerceAtLeast(1).toFloat()
        val dy = (a.y - b.y) / height.coerceAtLeast(1).toFloat()
        return hypot(dx, dy)
    }

    private fun midpoint(a: PointF, b: PointF): PointF =
        PointF((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

    private fun target(
        point: PointF,
        width: Int,
        height: Int,
        kind: Kind,
        confidence: Float
    ): Target = Target(
        x = (point.x / width.toFloat()).coerceIn(0f, 1f),
        y = (point.y / height.toFloat()).coerceIn(0f, 1f),
        kind = kind,
        confidence = confidence.coerceIn(0f, 1f)
    )

    override fun close() {
        detector.close()
    }

    companion object {
        private const val MIN_LIKELIHOOD = 0.4f
        private const val FACE_LIKELIHOOD = 0.6f
        private const val HAND_TO_MOUTH_DISTANCE = 0.16f
    }
}
