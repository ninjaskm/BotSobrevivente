package com.matheus.bot

import android.content.Context
import android.widget.Button

/** Botão pequeno para os painéis flutuantes. */
fun miniButton(ctx: Context, text: String, onClick: () -> Unit): Button = Button(ctx).apply {
    this.text = text
    textSize = 11f
    isAllCaps = false
    minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
    setPadding(16, 6, 16, 6)
    setOnClickListener { onClick() }
}
