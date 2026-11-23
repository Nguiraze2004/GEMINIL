package com.nguiraze.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val ACAO_SMS_MPESA = "com.nguiraze.net.ACAO_SMS_MPESA"
        const val EXTRA_TEXTO_SMS = "extra_texto_sms"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val mensagens = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val textoCompleto = mensagens.joinToString("") { it.messageBody.orEmpty() }.trim()
        if (textoCompleto.isBlank()) return

        val lower = textoCompleto.lowercase()
        if (lower.contains("recebeste") && (lower.contains("m-pesa") || lower.contains("mpesa"))) {
            val broadcast = Intent(ACAO_SMS_MPESA).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_TEXTO_SMS, textoCompleto)
            }
            context.sendBroadcast(broadcast)
        }
    }
}
