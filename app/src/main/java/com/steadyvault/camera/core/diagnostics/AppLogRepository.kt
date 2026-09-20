package com.steadyvault.camera.core.diagnostics

import android.content.Context

/**
 * Compatibilidade para chamadas antigas de diagnóstico.
 *
 * Não grava arquivos, SharedPreferences nem mantém histórico persistente.
 * As chamadas são intencionalmente descartadas para evitar uso de armazenamento.
 */
object AppLogRepository {
    fun info(context: Context, category: String, message: String) = Unit
    fun warn(context: Context, category: String, message: String, error: Throwable? = null) = Unit
    fun error(context: Context, category: String, message: String, error: Throwable? = null) = Unit
}
