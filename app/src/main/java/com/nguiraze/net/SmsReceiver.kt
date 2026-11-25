package com.nguiraze.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val ACAO_NOVO_PEDIDO_SMS = "com.nguiraze.net.NOVO_PEDIDO_SMS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val mensagens = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val corpoCompleto = mensagens.joinToString("") { it.messageBody ?: "" }
        val lower = corpoCompleto.lowercase()

        // Filtra apenas mensagens de confirmação de recebimento (M-Pesa / e-Mola)
        if (!lower.contains("recebeste") && !lower.contains("transferencia recebida")) return

        // Extrai valor em MT (ex: "10.00MT" ou "10 MT")
        val regexValor = Regex("""(\d+(?:[.,]\d+)?)\s*mt""", RegexOption.IGNORE_CASE)
        val valorStr = regexValor.find(corpoCompleto)?.groupValues?.get(1)?.replace(",", ".")
        val valorMt = valorStr?.toDoubleOrNull()?.toInt() ?: return

        // Extrai número moçambicano (82, 83, 84, 85, 86, 87)
        val regexNumero = Regex("""\b(8[2-7]\d{7})\b""")
        val numeroCliente = regexNumero.find(corpoCompleto)?.groupValues?.get(1) ?: return

        val megas = converterMtParaMegas(valorMt)
        if (megas <= 0) return

        val aviso = Intent(ACAO_NOVO_PEDIDO_SMS).setPackage(context.packageName).apply {
            putExtra("numero", numeroCliente)
            putExtra("megas", megas)
            putExtra("valor_mt", valorMt)
        }
        context.sendBroadcast(aviso)
    }

    private fun converterMtParaMegas(valorMt: Int): Int {
        return when (valorMt) {
            10 -> 100
            20 -> 250
            50 -> 600
            100 -> 1200
            else -> valorMt * 10 // Regra base: 1 MT = 10 MB se não estiver na tabela fixa
        }
    }
}
