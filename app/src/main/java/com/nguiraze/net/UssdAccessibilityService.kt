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
        private var aguardandoConfirmacaoFinal = false

        fun iniciarFluxo(numero: String, megas: Int) {
            numeroDestino = numero.trim()
            megasDestino = megas
            passoAtual = 0
            ultimoTextoProcessado = ""
            aguardandoConfirmacaoFinal = false
            fluxoEmExecucao = true
        }

        fun pararFluxo() {
            fluxoEmExecucao = false
            passoAtual = 0
            ultimoTextoProcessado = ""
            aguardandoConfirmacaoFinal = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val timeoutTelaRunnable = Runnable {
        if (fluxoEmExecucao && !aguardandoConfirmacaoFinal) {
            encerrarComFalha("Tempo esgotado à espera da tela (passo $passoAtual)")
        }
    }

    private val timeoutConfirmacaoRunnable = Runnable {
        if (fluxoEmExecucao && aguardandoConfirmacaoFinal) {
            encerrarComFalha("Sem 'transferiste com sucesso' em 6s")
        }
    }

    private fun cancelarTimers() {
        handler.removeCallbacks(timeoutTelaRunnable)
        handler.removeCallbacks(timeoutConfirmacaoRunnable)
    }

    private fun agendarTimeoutTela(delayMs: Long = 9_000L) {
        handler.removeCallbacks(timeoutTelaRunnable)
        handler.postDelayed(timeoutTelaRunnable, delayMs)
    }

    private fun agendarTimeoutConfirmacao(delayMs: Long = 6_000L) {
        handler.removeCallbacks(timeoutConfirmacaoRunnable)
        handler.postDelayed(timeoutConfirmacaoRunnable, delayMs)
    }

    private fun encerrarComFalha(motivo: String) {
        cancelarTimers()
        clicarFecharOuOk(rootInActiveWindow)
        pararFluxo()
        val intentFalha = Intent(ACAO_FALHA_USSD).setPackage(packageName).apply {
            putExtra("motivo", motivo.take(90))
        }
        sendBroadcast(intentFalha)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        servicoAtivo = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        servicoAtivo = false
        cancelarTimers()
        pararFluxo()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        cancelarTimers()
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

        // Confirmação real da transferência
        if (lower.contains("transferiste com sucesso") || lower.contains("transferiste")) {
            cancelarTimers()
            clicarBotaoPositivo(root)
            pararFluxo()
            sendBroadcast(Intent(ACAO_SUCESSO_USSD).setPackage(packageName))
            return
        }

        // Erro explícito durante o fluxo
        if (lower.contains("saldo insuficiente") ||
            lower.contains("inválido") ||
            lower.contains("invalido") ||
            lower.contains("erro") ||
            lower.contains("falh") ||
            lower.contains("tente novamente")
        ) {
            encerrarComFalha(textoCompleto)
            return
        }

        // Veio uma tela nova: reinicia o tempo limite de espera de tela enquanto não estiver nos 6s finais
        if (!aguardandoConfirmacaoFinal) {
            agendarTimeoutTela(9_000L)
        }

        handler.postDelayed({
            if (!fluxoEmExecucao) return@postDelayed
            val janelaAtual = rootInActiveWindow ?: return@postDelayed
            val textosAtualizados = mutableListOf<String>()
            coletarTextos(janelaAtual, textosAtualizados)
            val telaAtual = textosAtualizados.joinToString(" ").lowercase().trim()
            if (telaAtual.isEmpty()) return@postDelayed

            if (telaAtual.contains("transferiste com sucesso") || telaAtual.contains("transferiste")) {
                cancelarTimers()
                clicarBotaoPositivo(janelaAtual)
                pararFluxo()
                sendBroadcast(Intent(ACAO_SUCESSO_USSD).setPackage(packageName))
                return@postDelayed
            }

            when (passoAtual) {
                0 -> {
                    // 8 - serviço de internet
                    if (telaAtual.contains("serviço de internet") ||
                        telaAtual.contains("servico de internet") ||
                        telaAtual.contains("internet")
                    ) {
                        if (preencherEEnviar(janelaAtual, "8")) {
                            passoAtual = 1
                            agendarTimeoutTela(9_000L)
                        }
                    }
                }
                1 -> {
                    // 2 - transferir megas
                    if (telaAtual.contains("transferir megas") ||
                        telaAtual.contains("transferir")
                    ) {
                        if (preencherEEnviar(janelaAtual, "2")) {
                            passoAtual = 2
                            agendarTimeoutTela(9_000L)
                        }
                    }
                }
                2 -> {
                    // Introduzir quantidade de MB / megas (ou saldo de dados)
                    if (telaAtual.contains("megas") ||
                        telaAtual.contains("mb") ||
                        telaAtual.contains("quantidade") ||
                        telaAtual.contains("saldo de dados")
                    ) {
                        if (preencherEEnviar(janelaAtual, megasDestino.toString())) {
                            passoAtual = 3
                            agendarTimeoutTela(9_000L)
                        }
                    }
                }
                3 -> {
                    // Digita o número do recipiente
                    if (telaAtual.contains("recipiente") ||
                        telaAtual.contains("número") ||
                        telaAtual.contains("numero") ||
                        telaAtual.contains("contacto") ||
                        telaAtual.contains("telefone") ||
                        telaAtual.contains("destino")
                    ) {
                        if (preencherEEnviar(janelaAtual, numeroDestino)) {
                            passoAtual = 4
                            aguardandoConfirmacaoFinal = true
                            handler.removeCallbacks(timeoutTelaRunnable)
                            agendarTimeoutConfirmacao(6_000L)
                        }
                    }
                }
                4 -> {
                    // Caso exista tela extra de confirmação antes da mensagem final
                    if (telaAtual.contains("confirm") ||
                        telaAtual.contains("1.") ||
                        telaAtual.contains("aceitar") ||
                        telaAtual.contains("sim")
                    ) {
                        if (preencherEEnviar(janelaAtual, "1")) {
                            passoAtual = 5
                            aguardandoConfirmacaoFinal = true
                            handler.removeCallbacks(timeoutTelaRunnable)
                            agendarTimeoutConfirmacao(6_000L)
                        }
                    }
                }
            }
        }, 650L)
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
            val alvoJanela = rootInActiveWindow ?: root
            clicarBotaoPositivo(alvoJanela)
        }, 350L)
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

    private fun clicarFecharOuOk(root: AccessibilityNodeInfo?) {
        if (root == null) return

        val botoesPadrao = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        for (btn in botoesPadrao) {
            if (clicarNoOuNoPai(btn)) return
        }

        val textosSaida = listOf("ok", "fechar", "cancelar", "dismiss", "close", "enviar", "send")
        for (alvo in textosSaida) {
            val encontrados = root.findAccessibilityNodeInfosByText(alvo)
            for (no in encontrados) {
                val txtNo = no.text?.toString()?.trim()?.lowercase() ?: ""
                if (txtNo == alvo || txtNo.contains(alvo)) {
                    if (clicarNoOuNoPai(no)) return
                }
            }
        }
    }

    private fun clicarBotaoPositivo(root: AccessibilityNodeInfo?) {
        if (root == null) return

        val botoesPadrao = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        for (btn in botoesPadrao) {
            if (clicarNoOuNoPai(btn)) return
        }

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
