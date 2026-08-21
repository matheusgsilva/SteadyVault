package com.steadyvault.camera.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt
import android.widget.FrameLayout

/** Mantém cada miniatura da galeria perfeitamente quadrada. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val fallbackSide = maxOf(
            suggestedMinimumWidth,
            (context.resources.displayMetrics.density * FALLBACK_SIDE_DP).roundToInt()
        )
        val side = if (measuredWidth > 0) measuredWidth else fallbackSide
        val exact = View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
        super.onMeasure(exact, exact)
        setMeasuredDimension(side, side)
    }

    private companion object {
        const val FALLBACK_SIDE_DP = 96f
    }
}
