package com.steadyvault.camera.ui.navigation

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object SystemBarInsets {
    fun applyTop(view: View) = apply(view, includeTop = true, includeBottom = false)

    fun applyBottom(view: View) = apply(view, includeTop = false, includeBottom = true)

    fun applyTopAndBottom(view: View) = apply(view, includeTop = true, includeBottom = true)

    private fun apply(view: View, includeTop: Boolean, includeBottom: Boolean) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val mandatoryGestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val safe = Insets.max(systemBars, mandatoryGestures)
            target.setPadding(
                initialLeft + safe.left,
                initialTop + if (includeTop) safe.top else 0,
                initialRight + safe.right,
                initialBottom + if (includeBottom) safe.bottom else 0
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
