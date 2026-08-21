package com.steadyvault.camera.ui.components

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.SpinnerAdapter
import android.widget.TextView
import com.steadyvault.camera.R
import com.steadyvault.camera.ui.theme.AppearanceStore

class ChoiceSpinnerAdapter(
    private val context: Context,
    options: List<Option>
) : BaseAdapter(), SpinnerAdapter {

    data class Option(
        val value: String,
        val label: String,
        val description: String = "",
        val enabled: Boolean = true,
        val disabledReason: String = ""
    )

    private val items = options.toMutableList()
    var selectedPosition: Int = 0
        set(value) {
            field = value.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            notifyDataSetChanged()
        }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Option = items[position]
    override fun getItemId(position: Int): Long = getItem(position).value.hashCode().toLong()
    override fun areAllItemsEnabled(): Boolean = items.all { it.enabled }
    override fun isEnabled(position: Int): Boolean = getItem(position).enabled

    fun replace(options: List<Option>, selectedValue: String? = null) {
        items.clear()
        items.addAll(options)
        selectedPosition = positionOf(selectedValue).takeIf { it >= 0 }
            ?: items.indexOfFirst { it.enabled }.takeIf { it >= 0 }
            ?: 0
        notifyDataSetChanged()
    }

    fun positionOf(value: String?): Int = items.indexOfFirst { it.value == value }
    fun snapshot(): List<Option> = items.toList()
    fun valueAt(position: Int): String = getItem(position.coerceIn(0, items.lastIndex)).value
    fun selectedOption(): Option = getItem(selectedPosition.coerceIn(0, items.lastIndex))

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val option = getItem(position)
        return selectedRow(convertView, option)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val option = getItem(position)
        val row = (convertView as? LinearLayout) ?: dropdownRow()
        val textContainer = row.getChildAt(0) as LinearLayout
        val title = textContainer.getChildAt(0) as TextView
        val summary = textContainer.getChildAt(1) as TextView
        val check = row.getChildAt(1) as TextView

        title.text = option.label
        val detail = if (!option.enabled && option.disabledReason.isNotBlank()) {
            option.disabledReason
        } else {
            option.description
        }
        summary.text = detail
        summary.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        check.text = if (position == selectedPosition) "✓" else ""

        val alpha = if (option.enabled) 1f else 0.38f
        row.alpha = alpha
        row.isEnabled = option.enabled
        row.setBackgroundColor(
            context.getColor(
                if (position == selectedPosition) R.color.surface_high else R.color.surface
            )
        )
        return row
    }

    private fun selectedRow(convertView: View?, option: Option): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(10), dp(6))

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    setTextColor(context.getColor(R.color.text_primary))
                    textSize = 14.5f
                    maxLines = 2
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    setTextColor(context.getColor(R.color.text_secondary))
                    textSize = 10.5f
                    maxLines = 1
                })
            }
            addView(textContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "›"
                gravity = Gravity.CENTER
                setTextColor(AppearanceStore.palette(context).accent)
                textSize = 18f
            }, LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        val textContainer = row.getChildAt(0) as LinearLayout
        (textContainer.getChildAt(0) as TextView).text = option.label
        val summary = textContainer.getChildAt(1) as TextView
        val detail = if (!option.enabled && option.disabledReason.isNotBlank()) option.disabledReason else option.description
        summary.text = detail
        summary.visibility = View.GONE
        row.alpha = if (option.enabled) 1f else 0.45f
        return row
    }

    private fun dropdownRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(18), dp(10), dp(12), dp(10))

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setTextColor(context.getColor(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            })
            addView(TextView(context).apply {
                setTextColor(context.getColor(R.color.text_secondary))
                textSize = 12f
                maxLines = 3
                setPadding(0, dp(3), 0, 0)
            })
        }
        addView(textContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(AppearanceStore.palette(context).accent)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
