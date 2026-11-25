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

        if (!pacote.contains("phone") &&
            !pacote.contains("telecom") &&
            !pacote.contains("ussd") &&
            !pacote.contains("stk") &&
            !pacote.contains("dialer")
        ) {
            return
        }

        val textos = mutableListOf<String>()
        coletarTextos(root, textos)
        val textoCompleto = textos.joinToString(" ").trim()
        if (textoCompleto.isEmpty()) return

        getSharedPreferences("ussd_debug", Context.MODE_PRIVATE).edit()
            .putString("ultima_janela", pacote)
            .putString("ultima_leitura", textoCompleto)
            .apply()

        if (!fluxoEmExecucao) return
        if (textoCompleto == ultimoTextoProcessado) return
        ultimoTextoProcessado = textoCompleto

        val lower = textoCompleto.lowercase()

        // 1. Confirmação final de sucesso
        if (lower.contains("transferiste") ||
            lower.contains("sucesso") ||
            lower.contains("enviaste") ||
            lower.contains("partilhaste")
        ) {
            clicarBotaoPositivo(root)
            pararFluxo()
            sendBroadcast(Intent(ACAO_SUCESSO_USSD).setPackage(packageName))
            return
        }

        // 2. Mensagens de erro / bloqueio
        if (lower.contains("saldo insuficiente") ||
            lower.contains("invalido") ||
            lower.contains("inválido") ||
            lower.contains("limite") ||
            lower.contains("erro") ||
            lower.contains("tente novamente") ||
            lower.contains("falhou")
        ) {
            clicarBotaoPositivo(root)
            pararFluxo()
            val intentFalha = Intent(ACAO_FALHA_USSD).setPackage(packageName).apply {
                putExtra("motivo", textoCompleto.take(90))
            }
            sendBroadcast(intentFalha)
            return
        }

        // 3. Navegação pelos passos do *162#
        handler.postDelayed({
            val janelaAtual = rootInActiveWindow ?: return@postDelayed
            when (passoAtual) {
                0 -> {
                    if (preencherEEnviar(janelaAtual, "1")) {
                        passoAtual = 1
                    }
                }
                1 -> {
                    if (preencherEEnviar(janelaAtual, numeroDestino)) {
                        passoAtual = 2
                    }
                }
                2 -> {
                    if (preencherEEnviar(janelaAtual, megasDestino.toString())) {
                        passoAtual = 3
                    }
                }
                3 -> {
                    if (preencherEEnviar(janelaAtual, "1")) {
                        passoAtual = 4
                    }
                }
            }
        }, 500)
    }

    private fun coletarTextos(node: AccessibilityNodeInfo?, lista: MutableList<String>) {
        if (node == null) return
        val isInput = node.className?.toString()?.contains("EditText") == true
        if (!isInput) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { lista.add(it) }
        }
        for (i in 0 until node.childCount) {
            coletarTextos(node.getChild(i), lista)
        }
    }

    private fun preencherEEnviar(root: AccessibilityNodeInfo, valor: String): Boolean {
        val campoInput = encontrarEditText(root) ?: return false
        campoInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, valor)
        }
        val preencheu = campoInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!preencheu) return false

        handler.postDelayed({
            clicarBotaoPositivo(rootInActiveWindow ?: root)
        }, 300)
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

        // Tenta primeiro o botão positivo padrão de diálogos Android (Enviar / OK)
        val botoesPadrao = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        for (btn in botoesPadrao) {
            if (clicarNoOuNoPai(btn)) return
        }

        // Fallback por texto (em qualquer caixa alta/baixa)
        val textosBotao = listOf("enviar", "send", "ok", "aceitar", "confirmar", "sim")
        for (alvo in textosBotao) {
            val encontrados = root.findAccessibilityNodeInfosByText(alvo)
            for (no in encontrados) {
                val txtNo = no.text?.toString()?.trim()?.lowercase() ?: ""
                if (txtNo == alvo || txtNo.contains(alvo)) {
                    if (clicarNoOuNoPai(no)) return
                }
            }
        }
    }

    private fun clicarNoOuNoPai(node: AccessibilityNodeInfo?): Boolean {
        var atual: AccessibilityNodeInfo? = node
        var tentativas = 0
        while (atual != null && tentativas < 4) {
            if (atual.isClickable && atual.isEnabled) {
                return atual.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            atual = atual.parent
            tentativas++
        }
        return false
    }
}
