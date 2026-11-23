package com.nguiraze.net

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("nguiraze_net_prefs", Context.MODE_PRIVATE)
    }

    private val limitePorSim = 10
    private val codigoPermissaoChamada = 101

    private val receptorSucessoUssd = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UssdAccessibilityService.ACAO_SUCESSO_USSD) {
                val resumo = intent.getStringExtra(UssdAccessibilityService.EXTRA_RESUMO_USSD).orEmpty()
                confirmarPedidoPendente(resumo)
            }
        }
    }

    private val receptorFalhaUssd = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UssdAccessibilityService.ACAO_FALHA_USSD) {
                val resumo = intent.getStringExtra(UssdAccessibilityService.EXTRA_RESUMO_USSD).orEmpty()
                cancelarPedidoPendente(resumo)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnEnviar = findViewById<Button>(R.id.btnEnviar)
        val btnFila = findViewById<Button>(R.id.btnFila)
        val btnHistorico = findViewById<Button>(R.id.btnHistorico)
        val btnMeta = findViewById<Button>(R.id.btnMeta)
        val btnUsarSim1 = findViewById<Button>(R.id.btnUsarSim1)
        val btnResetSim1 = findViewById<Button>(R.id.btnResetSim1)
        val btnUsarSim2 = findViewById<Button>(R.id.btnUsarSim2)
        val btnResetSim2 = findViewById<Button>(R.id.btnResetSim2)
        val btnPausar = findViewById<Button>(R.id.btnPausar)

        btnEnviar.setOnClickListener { mostrarPainelEnvioManual() }
        btnFila.setOnClickListener { processarProximoDaFila() }
        btnHistorico.setOnClickListener { mostrarHistorico() }
        btnMeta.setOnClickListener { mostrarDialogoTabelaMegas() }

        btnUsarSim1.setOnClickListener {
            prefs.edit().putInt("sim_preferido", 1).apply()
            Toast.makeText(this, "SIM 1 definido como preferencial", Toast.LENGTH_SHORT).show()
        }

        btnUsarSim2.setOnClickListener {
            prefs.edit().putInt("sim_preferido", 2).apply()
            Toast.makeText(this, "SIM 2 definido como preferencial", Toast.LENGTH_SHORT).show()
        }

        btnResetSim1.setOnClickListener {
            prefs.edit().putInt("sim1_count", 0).apply()
            atualizarInterface()
            Toast.makeText(this, "SIM 1 resetado (0/10)", Toast.LENGTH_SHORT).show()
        }

        btnResetSim2.setOnClickListener {
            prefs.edit().putInt("sim2_count", 0).apply()
            atualizarInterface()
            Toast.makeText(this, "SIM 2 resetado (0/10)", Toast.LENGTH_SHORT).show()
        }

        btnPausar.setOnClickListener {
            val pausadoAtual = prefs.getBoolean("fila_pausada", false)
            prefs.edit().putBoolean("fila_pausada", !pausadoAtual).apply()
            atualizarInterface()
        }

        registrarRecetores()
        verificarPermissoesIniciais()
        atualizarInterface()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receptorSucessoUssd)
            unregisterReceiver(receptorFalhaUssd)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun registrarRecetores() {
        val filtroSucesso = IntentFilter(UssdAccessibilityService.ACAO_SUCESSO_USSD)
        val filtroFalha = IntentFilter(UssdAccessibilityService.ACAO_FALHA_USSD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receptorSucessoUssd, filtroSucesso, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(receptorFalhaUssd, filtroFalha, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receptorSucessoUssd, filtroSucesso)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receptorFalhaUssd, filtroFalha)
        }
    }

    private fun verificarPermissoesIniciais() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                codigoPermissaoChamada
            )
        }

        if (!acessibilidadeAtiva()) {
            AlertDialog.Builder(this)
                .setTitle("Acessibilidade USSD")
                .setMessage("Ativa o serviço NGUIRAZE NET na Acessibilidade para executar o *162# e detetar 'transferiste'.")
                .setPositiveButton("Abrir Configurações") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Depois", null)
                .show()
        }
    }

    private fun acessibilidadeAtiva(): Boolean {
        val servicoId = "$packageName/${UssdAccessibilityService::class.java.name}"
        val habilitados = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return habilitados.split(":").any { it.equals(servicoId, ignoreCase = true) }
    }

    private fun mostrarPainelEnvioManual() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#09152E"))
            setPadding(40, 32, 40, 24)
        }

        val tvSubtitulo = TextView(this).apply {
            text = "PACOTES RÁPIDOS"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }
        container.addView(tvSubtitulo)

        val etMegabytes = EditText(this).apply {
            hint = "Megabytes (ex: 450, 1024)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(24, 24, 24, 24)
        }

        val btnPacote100 = Button(this).apply {
            text = "100MB • 3 MT"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#163A7A"))
            setOnClickListener { etMegabytes.setText("100") }
        }

        val btnPacote1024 = Button(this).apply {
            text = "1024MB (1GB)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#122752"))
            setOnClickListener { etMegabytes.setText("1024") }
        }

        val rowPacotes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 18)
        }
        val lpBtn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 10, 0)
        }
        val lpBtn2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(10, 0, 0, 0)
        }
        rowPacotes.addView(btnPacote100, lpBtn)
        rowPacotes.addView(btnPacote1024, lpBtn2)
        container.addView(rowPacotes)

        val etNumero = EditText(this).apply {
            hint = "Número do cliente (ex: 841234567)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(24, 24, 24, 24)
        }

        container.addView(etNumero)
        container.addView(etMegabytes)

        val tvInfo = TextView(this).apply {
            text = "Só contabiliza após confirmação com 'transferiste'."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        container.addView(tvInfo)

        AlertDialog.Builder(this)
            .setTitle("Envio manual")
            .setView(container)
            .setPositiveButton("Despachar para um Agente") { _, _ ->
                val numero = etNumero.text.toString().trim()
                val mb = etMegabytes.text.toString().trim().replace(",", ".").toDoubleOrNull()

                if (numero.length >= 9 && mb != null && mb > 0.0) {
                    val gb = mb / 1024.0
                    val filaAtual = lerLista("fila_pedidos").toMutableList()
                    filaAtual.add("$numero|$gb")
                    guardarLista("fila_pedidos", filaAtual)
                    atualizarInterface()
                    if (!prefs.getBoolean("fila_pausada", false)) {
                        processarProximoDaFila()
                    }
                } else {
                    Toast.makeText(this, "Preenche um número válido e os megabytes.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun processarProximoDaFila() {
        if (prefs.getBoolean("fila_pausada", false)) {
            Toast.makeText(this, "A fila está pausada.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!prefs.getString("pedido_pendente", "").isNullOrBlank()) {
            Toast.makeText(this, "Já existe um pedido USSD em curso.", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                codigoPermissaoChamada
            )
            Toast.makeText(this, "Concede permissão de chamada para disparar o USSD.", Toast.LENGTH_SHORT).show()
            return
        }

        val filaAtual = lerLista("fila_pedidos").toMutableList()
        if (filaAtual.isEmpty()) {
            Toast.makeText(this, "Fila de espera vazia.", Toast.LENGTH_SHORT).show()
            return
        }

        val sim1 = prefs.getInt("sim1_count", 0)
        val sim2 = prefs.getInt("sim2_count", 0)
        val preferido = prefs.getInt("sim_preferido", 1)

        val simEscolhido = when {
            preferido == 1 && sim1 < limitePorSim -> 1
            preferido == 2 && sim2 < limitePorSim -> 2
            sim1 < limitePorSim -> 1
            sim2 < limitePorSim -> 2
            else -> {
                Toast.makeText(this, "SIM 1 e SIM 2 atingiram 10/10! Faz Reset.", Toast.LENGTH_LONG).show()
                return
            }
        }

        val item = filaAtual.removeAt(0)
        guardarLista("fila_pedidos", filaAtual)

        val partes = item.split("|")
        val numero = partes.getOrNull(0).orEmpty()
        val gb = partes.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val mbInt = (gb * 1024.0).toInt().coerceAtLeast(1)

        prefs.edit().putString("pedido_pendente", "$numero|$gb|$mbInt|$simEscolhido").apply()
        UssdAccessibilityService.iniciarFluxo(numero, mbInt)

        val disparou = chamarPorIntent("*162#", simEscolhido)
        if (!disparou) {
            prefs.edit().remove("pedido_pendente").apply()
            UssdAccessibilityService.pararFluxo()
            Toast.makeText(this, "Falha ao iniciar *162# no SIM $simEscolhido.", Toast.LENGTH_SHORT).show()
            return
        }

        atualizarInterface()
        Toast.makeText(this, "A executar *162# para $numero ($mbInt MB)...", Toast.LENGTH_SHORT).show()
    }

    private fun confirmarPedidoPendente(resumoTela: String) {
        val pendente = prefs.getString("pedido_pendente", "").orEmpty()
        if (pendente.isBlank()) return

        val partes = pendente.split("|")
        val numero = partes.getOrNull(0).orEmpty()
        val gb = partes.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val mbInt = partes.getOrNull(2)?.toIntOrNull() ?: (gb * 1024.0).toInt()
        val simEscolhido = partes.getOrNull(3)?.toIntOrNull() ?: 1

        prefs.edit().remove("pedido_pendente").apply()

        val sim1 = prefs.getInt("sim1_count", 0)
        val sim2 = prefs.getInt("sim2_count", 0)
        if (simEscolhido == 1) {
            prefs.edit().putInt("sim1_count", sim1 + 1).apply()
        } else {
            prefs.edit().putInt("sim2_count", sim2 + 1).apply()
        }

        val totalGb = prefs.getFloat("total_gb", 0f) + gb.toFloat()
        val totalAtendidos = prefs.getInt("total_atendidos", 0) + 1

        val historico = lerLista("historico_pedidos").toMutableList()
        val gbFormatado = String.format(Locale("pt", "MZ"), "%.2f", gb)
        val registo = "✓ $numero • ${gbFormatado}GB ($mbInt MB) • SIM $simEscolhido"
        historico.add(0, registo)
        if (historico.size > 30) historico.removeAt(historico.lastIndex)

        prefs.edit()
            .putFloat("total_gb", totalGb)
            .putInt("total_atendidos", totalAtendidos)
            .apply()
        guardarLista("historico_pedidos", historico)

        atualizarInterface()
        Toast.makeText(this, "Confirmado: $resumoTela", Toast.LENGTH_LONG).show()

        if (!prefs.getBoolean("fila_pausada", false) && lerLista("fila_pedidos").isNotEmpty()) {
            Handler(Looper.getMainLooper()).postDelayed({
                processarProximoDaFila()
            }, 1500)
        }
    }

    private fun cancelarPedidoPendente(resumoTela: String) {
        prefs.edit().remove("pedido_pendente").apply()
        atualizarInterface()
        Toast.makeText(this, "Não contabilizado: $resumoTela", Toast.LENGTH_LONG).show()
    }

    private fun chamarPorIntent(codigoUssd: String, slotSim: Int): Boolean {
        return try {
            val codificado = codigoUssd.replace("#", "%23")
            val uri = Uri.parse("tel:$codificado")
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val handles = if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    telecomManager?.callCapablePhoneAccounts
                } else {
                    emptyList()
                }
                val indice = (slotSim - 1).coerceAtLeast(0)
                if (!handles.isNullOrEmpty() && indice < handles.size) {
                    putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handles[indice])
                }
                putExtra("com.android.phone.extra.slot", slotSim - 1)
                putExtra("simSlot", slotSim - 1)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun mostrarHistorico() {
        val historico = lerLista("historico_pedidos")
        val pendente = prefs.getString("pedido_pendente", "").orEmpty()
        val statusPendente = if (pendente.isNotBlank()) "\n\nPendente: $pendente" else ""
        val extraUssd = if (UssdAccessibilityService.ultimoTextoUssd.isNotBlank()) {
            "\nÚltima janela USSD:\n${UssdAccessibilityService.ultimoTextoUssd}"
        } else {
            ""
        }
        val msg = (if (historico.isEmpty()) "Sem pedidos no histórico." else historico.joinToString("\n")) +
            statusPendente + extraUssd
        AlertDialog.Builder(this)
            .setTitle("Histórico de Envios")
            .setMessage(msg)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun mostrarDialogoTabelaMegas() {
        val textoAtual = prefs.getString("tabela_megas", "10:100\n20:250\n50:600") ?: ""
        val input = EditText(this).apply {
            setText(textoAtual)
            setPadding(40, 30, 40, 30)
            minLines = 5
        }

        AlertDialog.Builder(this)
            .setTitle("Tabela de Megas (M-Pesa)")
            .setMessage("Um por linha no formato valor:mb\nEx: 10:100")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putString("tabela_megas", input.text.toString().trim()).apply()
                Toast.makeText(this, "Tabela de megas guardada!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun lerTabelaMegas(): Map<Double, Int> {
        val bruto = prefs.getString("tabela_megas", "10:100\n20:250\n50:600") ?: ""
        val mapa = mutableMapOf<Double, Int>()
        bruto.lines().forEach { linha ->
            val limpa = linha.trim()
            if (limpa.isNotEmpty() && limpa.contains(":")) {
                val partes = limpa.split(":")
                val valor = partes.getOrNull(0)?.trim()?.replace(",", ".")?.toDoubleOrNull()
                val mb = partes.getOrNull(1)?.trim()?.toIntOrNull()
                if (valor != null && valor > 0.0 && mb != null && mb > 0) {
                    mapa[valor] = mb
                }
            }
        }
        return mapa
    }

    fun processarPagamentoMpesa(textoMensagem: String) {
        val lower = textoMensagem.lowercase()
        if (!lower.contains("m-pesa") && !lower.contains("mpesa")) {
            return
        }

        val regexNumero = Regex("""\b(8[2-7]\d{7})\b""")
        val numero = regexNumero.find(textoMensagem)?.value ?: return

        val regexValor = Regex("""(\d+(?:[.,]\d{1,2})?)\s*(?:mt|mzn)""", RegexOption.IGNORE_CASE)
        val valorPago = regexValor.findAll(textoMensagem)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() }
            .firstOrNull { it > 0.0 } ?: return

        val mb = lerTabelaMegas()[valorPago] ?: return
        val gb = mb / 1024.0
        val filaAtual = lerLista("fila_pedidos").toMutableList()
        filaAtual.add("$numero|$gb")
        guardarLista("fila_pedidos", filaAtual)
        atualizarInterface()

        if (!prefs.getBoolean("fila_pausada", false)) {
            processarProximoDaFila()
        }
    }

    private fun atualizarInterface() {
        val tvGbVendidos = findViewById<TextView>(R.id.tvGbVendidos)
        val tvTotalAtendidos = findViewById<TextView>(R.id.tvTotalAtendidos)
        val tvSim1Count = findViewById<TextView>(R.id.tvSim1Count)
        val tvSim2Count = findViewById<TextView>(R.id.tvSim2Count)
        val tvFilaEspera = findViewById<TextView>(R.id.tvFilaEspera)
        val btnPausar = findViewById<Button>(R.id.btnPausar)
        val tvUltimosPedidos = findViewById<TextView>(R.id.tvUltimosPedidos)

        val totalGb = prefs.getFloat("total_gb", 0f)
        val totalAtendidos = prefs.getInt("total_atendidos", 0)
        val sim1 = prefs.getInt("sim1_count", 0)
        val sim2 = prefs.getInt("sim2_count", 0)
        val fila = lerLista("fila_pedidos")
        val pausada = prefs.getBoolean("fila_pausada", false)
        val historico = lerLista("historico_pedidos")

        tvGbVendidos.text = formatarGb(totalGb)
        tvTotalAtendidos.text = totalAtendidos.toString()
        tvSim1Count.text = "$sim1/$limitePorSim"
        tvSim2Count.text = "$sim2/$limitePorSim"
        tvFilaEspera.text = "Aguardando processamento: ${fila.size}"
        btnPausar.text = if (pausada) "Retomar" else "Pausar"

        tvUltimosPedidos.text = if (historico.isEmpty()) {
            "Sem pedidos ainda."
        } else {
            historico.take(5).joinToString("\n")
        }
    }

    private fun formatarGb(valor: Float): String {
        return String.format(Locale("pt", "MZ"), "%.2f", valor)
    }

    private fun lerLista(chave: String): List<String> {
        val bruto = prefs.getString(chave, "") ?: ""
        if (bruto.isBlank()) return emptyList()
        return bruto.split(";;").filter { it.isNotBlank() }
    }

    private fun guardarLista(chave: String, lista: List<String>) {
        prefs.edit().putString(chave, lista.joinToString(";;")).apply()
    }
}
