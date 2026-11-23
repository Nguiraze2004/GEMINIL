package com.nguiraze.net

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        const val ACAO_SUCESSO_USSD = "com.nguiraze.net.ACAO_SUCESSO_USSD"
        const val EXTRA_RESUMO_USSD = "extra_resumo_ussd"

        @Volatile
        var sessaoAtiva: Boolean = false

        @Volatile
        var passoAtualIndex: Int = 0 // 0: envia "8", 1: envia "2", 2: envia MB, 3: envia numero, 4: aguarda "transferiste"

        @Volatile
        var quantidadeMb: String = ""

        @Volatile
        var numeroCliente: String = ""

        @Volatile
        var ultimoTextoUssd: String = ""

        @Volatile
        private var ultimoTextoRespondido: String = ""

        fun iniciarFluxo(numero: String, mb: Int) {
            numeroCliente = numero.trim()
            quantidadeMb = mb.coerceAtLeast(1).toString()
            passoAtualIndex = 0
            ultimoTextoRespondido = ""
            sessaoAtiva = true
        }

        fun pararFluxo() {
            sessaoAtiva = false
            passoAtualIndex = 0
            ultimoTextoRespondido = ""
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val textos = mutableListOf<String>()
        coletarTextos(root, textos)
        if (textos.isEmpty()) return

        val textoTela = textos.joinToString(" | ").trim()
        if (textoTela.isBlank()) return
        ultimoTextoUssd = textoTela

        if (!sessaoAtiva) return

        // Verifica se a confirmação de sucesso com a palavra-chave "transferiste" apareceu
        if (textoTela.contains("transferiste", ignoreCase = true)) {
            sessaoAtiva = false
            passoAtualIndex = 0
            ultimoTextoRespondido = ""

            val intent = Intent(ACAO_SUCESSO_USSD).apply {
                setPackage(packageName)
                putExtra(EXTRA_RESUMO_USSD, textoTela)
            }
            sendBroadcast(intent)
            return
        }

        // Evita responder repetidamente ao mesmo texto exato da janela
        if (textoTela == ultimoTextoRespondido) return

        val resposta = when (passoAtualIndex) {
            0 -> "8"
            1 -> "2"
            2 -> quantidadeMb
            3 -> numeroCliente
            else -> null
        } ?: return

        val editNode = encontrarEditText(root)
        if (editNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, resposta)
            }
            val preencheu = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (preencheu) {
                ultimoTextoRespondido = textoTela
                passoAtualIndex += 1

                val btnConfirmar = encontrarBotaoConfirmar(root)
                btnConfirmar?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    override fun onInterrupt() {
        pararFluxo()
    }

    private fun coletarTextos(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty()) {
            out.add(txt)
        }
        for (i in 0 until node.childCount) {
            coletarTextos(node.getChild(i), out)
        }
    }

    private fun encontrarEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = encontrarEditText(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun encontrarBotaoConfirmar(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val txt = node.text?.toString()?.lowercase()?.trim().orEmpty()
        if (node.isClickable && (txt == "enviar" || txt == "send" || txt == "ok" || txt == "confirmar")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = encontrarBotaoConfirmar(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
