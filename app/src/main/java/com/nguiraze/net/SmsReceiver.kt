package com.nguiraze.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val ACAO_SMS_MPESA = "com.nguiraze.net.ACAO_SMS_MPESA"
        const val ACAO_NOVA_SMS = "com.nguiraze.net.ACAO_NOVA_SMS"
        const val EXTRA_TEXTO_SMS = "extra_texto_sms"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val mensagens = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val textoCompleto = mensagens.joinToString("") { it.messageBody.orEmpty() }.trim()
        if (textoCompleto.isBlank()) return

        val remetente = mensagens.firstOrNull()?.displayOriginatingAddress.orEmpty().trim()
        val hora = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "MZ")).format(Date())
        val registoComprovativo = "[$hora] ${if (remetente.isNotBlank()) "$remetente: " else ""}$textoCompleto"

        val prefs = context.getSharedPreferences("nguiraze_net_prefs", Context.MODE_PRIVATE)

        // 1. Guarda TODAS as SMS na opção Comprovativo
        val comprovativosAtuais = prefs.getString("comprovantes_sms", "")
            .orEmpty()
            .split(";;")
            .filter { it.isNotBlank() }
        val novosComprovativos = (listOf(registoComprovativo) + comprovativosAtuais).take(100)
        prefs.edit().putString("comprovantes_sms", novosComprovativos.joinToString(";;")).apply()

        context.sendBroadcast(Intent(ACAO_NOVA_SMS).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TEXTO_SMS, registoComprovativo)
        })

        // 2. Se for pagamento M-Pesa, guarda também para processamento automático de megas
        val lower = textoCompleto.lowercase()
        if (lower.contains("recebeste") && (lower.contains("m-pesa") || lower.contains("mpesa"))) {
            val pendentes = prefs.getString("sms_mpesa_pendentes", "")
                .orEmpty()
                .split("||")
                .filter { it.isNotBlank() }
            val novaFilaSms = (pendentes + textoCompleto).takeLast(20)
            prefs.edit().putString("sms_mpesa_pendentes", novaFilaSms.joinToString("||")).apply()

            context.sendBroadcast(Intent(ACAO_SMS_MPESA).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_TEXTO_SMS, textoCompleto)
            })
        }
    }
}
