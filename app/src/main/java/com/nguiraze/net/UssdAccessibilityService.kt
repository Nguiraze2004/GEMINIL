package com.nguiraze.net

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        const val ACAO_SUCESSO_USSD = "com.nguiraze.net.ACAO_SUCESSO_USSD"
        const val ACAO_FALHA_USSD = "com.nguiraze.net.ACAO_FALHA_USSD"

        var servicoAtivo = false
        private var fluxoEmExecucao = false
        private var numeroDestino = ""
        private var megasDestino = 0
        private var passoAtual = 0
        private var ultimoTextoProcessado = ""

        fun iniciarFluxo(numero: String, megas: Int) {
            numeroDestino = numero.trim()
            megasDestino = megas
            passoAtual = 0
            ultimoTextoProcessado = ""
            fluxoEmExecucao = true
        }

        fun pararFluxo() {
            fluxoEmExecucao = false
            passoAtual = 0
            ultimoTextoProcessado = ""
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        servicoAtivo = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        servicoAtivo = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        pararFluxo()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pacote = event?.packageName?.toString() ?: return

        // Filtra apenas diálogos de telefone / USSD do sistema Android
        if (!pacote.contains("phone") &&
            !pacote.contains("telecom") &&
            !pacote.contains("ussd") &&
            !pacote.contains("stk")
        ) {
            return
        }

        val textos = mutableListOf<String>()
        coletarTextos(root, textos)
        val textoCompleto = textos.joinToString(" ").trim()
        if (textoCompleto.isEmpty()) return

        // Guarda para o painel "DIAGNÓSTICO USSD" na aba SIMs
        getSharedPreferences("ussd_debug", Context.MODE_PRIVATE).edit()
            .putString("ultima_janela", pacote)
            .putString("ultima_leitura", textoCompleto)
            .apply()

        if (!fluxoEmExecucao) return
        if (textoCompleto == ultimoTextoProcessado) return
        ultimoTextoProcessado = textoCompleto

        val lower = textoCompleto.lowercase()

        // 1. Verificar confirmação final de sucesso da Vodacom
        if (lower.contains("transferiste") ||
            lower.contains("sucesso") ||
            lower.contains("enviaste")
        ) {
            clicarBotaoPositivo(root)
            pararFluxo()
            sendBroadcast(Intent(ACAO_SUCESSO_USSD).setPackage(packageName))
            return
        }

        // 2. Verificar mensagens de erro conhecidas
        if (lower.contains("saldo insuficiente") ||
            lower.contains("invalido") ||
            lower.contains("inválido") ||
            lower.contains("limite") ||
            lower.contains("erro") ||
            lower.contains("tente novamente")
        ) {
            clicarBotaoPositivo(root)
            pararFluxo()
            val intentFalha = Intent(ACAO_FALHA_USSD).setPackage(packageName).apply {
                putExtra("motivo", textoCompleto.take(90))
            }
            sendBroadcast(intentFalha)
            return
        }

        // 3. Navegação pelos passos do menu *162# (Vodacom MZ)
        handler.postDelayed({
            val janelaAtual = rootInActiveWindow ?: return@postDelayed
            when {
                // Passo 0: Menu inicial *162# -> escolher opção de transferir megas (ex: "1")
                passoAtual == 0 && (lower.contains("transferir") || lower.contains("partilhar") || lower.contains("megas")) -> {
                    if (preencherEEnviar(janelaAtual, "1")) passoAtual = 1
                }
                // Passo 1: Inserir número do destinatário
                passoAtual <= 1 && (lower.contains("numero") || lower.contains("número") || lower.contains("destinat")) -> {
                    if (preencherEEnviar(janelaAtual, numeroDestino)) passoAtual = 2
                }
                // Passo 2: Inserir quantidade de megas (MB)
                passoAtual <= 2 && (lower.contains("quantidade") || lower.contains("megas") || lower.contains("mb")) -> {
                    if (preencherEEnviar(janelaAtual, megasDestino.toString())) passoAtual = 3
                }
                // Passo 3: Confirmar transferência ("1" para confirmar)
                passoAtual <= 3 && (lower.contains("confirm") || lower.contains("1.") || lower.contains("aceitar")) -> {
                    if (preencherEEnviar(janelaAtual, "1")) passoAtual = 4
                }
            }
        }, 450)
    }

    private fun coletarTextos(node: AccessibilityNodeInfo?, lista: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { lista.add(it) }
        for (i in 0 until node.childCount) {
            coletarTextos(node.getChild(i), lista)
        }
    }

    private fun preencherEEnviar(root: AccessibilityNodeInfo, valor: String): Boolean {
        val campoInput = encontrarEditText(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, valor)
        }
        val preencheu = campoInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!preencheu) return false

        handler.postDelayed({
            clicarBotaoPositivo(rootInActiveWindow ?: root)
        }, 250)
        return true
    }

    private fun encontrarEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString()?.contains("EditText") == true && node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val achado = encontrarEditText(node.getChild(i))
            if (achado != null) return achado
        }
        return null
    }

    private fun clicarBotaoPositivo(root: AccessibilityNodeInfo?) {
        if (root == null) return
        val textosBotao = listOf("Enviar", "Send", "OK", "Aceitar", "Confirmar")
        for (texto in textosBotao) {
            val botoes = root.findAccessibilityNodeInfosByText(texto)
            for (btn in botoes) {
                if (btn.isClickable) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        }
    }
}
