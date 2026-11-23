package com.nguiraze.net

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var passoAtual: String? = null

        @Volatile
        var ultimoTextoUssd: String = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val textos = mutableListOf<String>()
        coletarTextos(root, textos)
        if (textos.isNotEmpty()) {
            ultimoTextoUssd = textos.joinToString(" | ")
        }

        val resposta = passoAtual ?: return
        val editNode = encontrarEditText(root)
        if (editNode != null && resposta.isNotBlank()) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, resposta)
            }
            editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            passoAtual = null

            val btnEnviar = encontrarBotaoConfirmar(root)
            btnEnviar?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    override fun onInterrupt() {
        passoAtual = null
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
