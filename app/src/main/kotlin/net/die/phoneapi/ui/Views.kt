package net.die.phoneapi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal fun Context.dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.button(label: Int, onClick: () -> Unit) =
    Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    }

internal fun Context.title(text: Int) =
    TextView(this).apply {
        setText(text)
        textSize = 24f
    }

internal fun Context.heading(text: Int) =
    TextView(this).apply {
        setText(text)
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dpToPx(24), 0, dpToPx(4))
    }

internal fun Context.body(text: Int) =
    TextView(this).apply {
        setText(text)
        setPadding(0, dpToPx(4), 0, dpToPx(4))
    }

/** Monospace and selectable, for commands and addresses the user copies to a computer. */
internal fun Context.code(value: String, size: Float = 12f) =
    TextView(this).apply {
        text = value
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, dpToPx(4), 0, dpToPx(4))
    }

internal fun Context.copyButton(label: Int, value: () -> CharSequence) =
    button(label) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(label), value()))
    }

internal fun View.shownIf(shown: Boolean) {
    visibility = if (shown) View.VISIBLE else View.GONE
}

internal fun Context.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
