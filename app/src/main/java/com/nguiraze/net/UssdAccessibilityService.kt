package com.nguiraze.net

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        const val ACAO_SUCESSO_USSD = "com.nguiraze.net.ACAO_SUCESSO_USSD"
        const val ACAO_FALHA_USSD = "com.nguiraze.net.ACAO_FALHA_USSD"
        const val EXTRA_RESUMO_USSD = "extra_resumo_ussd"

        @Volatile
        var sessaoAtiva: Boolean = false

        // 0: envia "8" (Serviço de Internet)
        // 1: envia "2" (Transferir megas)
        // 2: envia MB (ex: 450)
        // 3: envia Número do destinatário
        // 4: Confirmação da transferência (envia "1" ou clica Confirmar)
        // 5: Clica em OK / aguarda resposta final
        @Volatile
        var passoAtualIndex: Int = 0

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

        // PASSO 8: Verificar sucesso ("transferiste")
        if (textoTela.contains("transferiste", ignoreCase = true)) {
            pararFluxo()
            clicarBotao(root, listOf("ok", "fechar", "cancelar"))

            val intent = Intent(ACAO_SUCESSO_USSD).apply {
                setPackage(packageName)
                putExtra(EXTRA_RESUMO_USSD, textoTela)
            }
            sendBroadcast(intent)
            return
        }

        // Detetar falha explícita da operadora para NÃO contabilizar
        val textoLower = textoTela.lowercase()
        if (textoLower.contains("saldo insuficiente") ||
            textoLower.contains("invalido") ||
            textoLower.contains("inválido") ||
            textoLower.contains("erro") ||
            textoLower.contains("falhou") ||
            textoLower.contains("nao foi possivel") ||
            textoLower.contains("não foi possível")
        ) {
            pararFluxo()
            clicarBotao(root, listOf("ok", "fechar", "cancelar"))

            val intent = Intent(ACAO_FALHA_USSD).apply {
                setPackage(packageName)
                putExtra(EXTRA_RESUMO_USSD, textoTela)
            }
            sendBroadcast(intent)
            return
        }

        // Evita repetir ação na mesma tela exata
        if (textoTela == ultimoTextoRespondido) return

        val editNode = encontrarEditText(root)

        when (passoAtualIndex) {
            0 -> {
                // Passo 2: Escolher opção 8
                if (enviarTextoNoCampo(root, editNode, "8")) {
                    ultimoTextoRespondido = textoTela
                    passoAtualIndex = 1
                }
            }
            1 -> {
                // Passo 3: Escolher opção 2
                if (enviarTextoNoCampo(root, editNode, "2")) {
                    ultimoTextoRespondido = textoTela
                    passoAtualIndex = 2
                }
            }
            2 -> {
                // Passo 4: Quantidade de MB
                if (enviarTextoNoCampo(root, editNode, quantidadeMb)) {
                    ultimoTextoRespondido = textoTela
                    passoAtualIndex = 3
                }
            }
            3 -> {
                // Passo 5: Número do destinatário
                if (enviarTextoNoCampo(root, editNode, numeroCliente)) {
                    ultimoTextoRespondido = textoTela
                    passoAtualIndex = 4
                }
            }
            4 -> {
                // Passo 6: Tela de confirmação (pode pedir "1" no campo OU ter botão Confirmar/OK)
                if (editNode != null) {
                    if (enviarTextoNoCampo(root, editNode, "1")) {
                        ultimoTextoRespondido = textoTela
                        passoAtualIndex = 5
                    }
                } else if (clicarBotao(root, listOf("confirmar", "sim", "ok", "enviar", "send"))) {
                    ultimoTextoRespondido = textoTela
                    passoAtualIndex = 5
                }
            }
            5 -> {
                // Passo 7: Clicar em OK depois da primeira confirmação
                if (clicarBotao(root, listOf("ok", "confirmar", "enviar", "send"))) {
                    ultimoTextoRespondido = textoTela
                    passoAtualIndex = 6
                }
            }
        }
    }

    override fun onInterrupt() {
        pararFluxo()
    }

    private fun enviarTextoNoCampo(
        root: AccessibilityNodeInfo,
        editNode: AccessibilityNodeInfo?,
        valor: String
    ): Boolean {
        if (editNode == null || valor.isBlank()) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, valor)
        }
        val preencheu = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!preencheu) return false

        return clicarBotao(root, listOf("enviar", "send", "ok", "confirmar"))
    }

    private fun clicarBotao(node: AccessibilityNodeInfo?, alvos: List<String>): Boolean {
        if (node == null) return false
        val txt = node.text?.toString()?.lowercase()?.trim().orEmpty()
        if (node.isClickable && alvos.contains(txt)) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            if (clicarBotao(node.getChild(i), alvos)) return true
        }
        return false
    }

    private fun coletarTextos(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty()) {
            out.add(txt)
        }
        for (i in 0 until node.childCount) {
            coletarTextos(
