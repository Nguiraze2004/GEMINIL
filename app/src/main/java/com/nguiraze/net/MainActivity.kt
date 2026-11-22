package com.nguiraze.net

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("nguiraze_net_prefs", Context.MODE_PRIVATE)
    }

    private val limitePorSim = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnEnviar = findViewById<Button>(R.id.btnEnviar)
        val btnFila = findViewById<Button>(R.id.btnFila)
        val btnHistorico = findViewById<Button>(R.id.btnHistorico)
        val btnMeta = findViewById<Button>(R.id.btnMeta)
        val btnResetSim1 = findViewById<Button>(R.id.btnResetSim1)
        val btnResetSim2 = findViewById<Button>(R.id.btnResetSim2)
        val btnPausar = findViewById<Button>(R.id.btnPausar)

        btnEnviar.setOnClickListener { mostrarDialogoNovoPedido() }
        btnFila.setOnClickListener { processarProximoDaFila() }
        btnHistorico.setOnClickListener { mostrarHistorico() }
        btnMeta.setOnClickListener { mostrarDialogoMeta() }

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

        atualizarInterface()
    }

    private fun mostrarDialogoNovoPedido() {
        val input = EditText(this).apply {
            hint = "Ex: 841234567:1.5"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Adicionar à Fila")
            .setMessage("Formato: numero:gb (ex: 841234567:1.5)")
            .setView(input)
            .setPositiveButton("Enfileirar") { _, _ ->
                val texto = input.text.toString().trim()
                val partes = texto.split(":")
                val numero = partes.getOrNull(0)?.trim().orEmpty()
                val gb = partes.getOrNull(1)?.trim()?.replace(",", ".")?.toDoubleOrNull()

                if (numero.length >= 9 && gb != null && gb > 0.0) {
                    val filaAtual = lerLista("fila_pedidos").toMutableList()
                    filaAtual.add("$numero|$gb")
                    guardarLista("fila_pedidos", filaAtual)
                    atualizarInterface()
                    if (!prefs.getBoolean("fila_pausada", false)) {
                        processarProximoDaFila()
                    }
                } else {
                    Toast.makeText(this, "Formato inválido. Use numero:gb", Toast.LENGTH_SHORT).show()
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

        val filaAtual = lerLista("fila_pedidos").toMutableList()
        if (filaAtual.isEmpty()) {
            Toast.makeText(this, "Fila de espera vazia.", Toast.LENGTH_SHORT).show()
            return
        }

        val sim1 = prefs.getInt("sim1_count", 0)
        val sim2 = prefs.getInt("sim2_count", 0)

        val simEscolhido = when {
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

        if (simEscolhido == 1) {
            prefs.edit().putInt("sim1_count", sim1 + 1).apply()
        } else {
            prefs.edit().putInt("sim2_count", sim2 + 1).apply()
        }

        val totalGb = prefs.getFloat("total_gb", 0f) + gb.toFloat()
        val totalAtendidos = prefs.getInt("total_atendidos", 0) + 1

        val historico = lerLista("historico_pedidos").toMutableList()
        val registo = "$numero • ${gb}GB • SIM $simEscolhido"
        historico.add(0, registo)
        if (historico.size > 30) historico.removeAt(historico.lastIndex)

        prefs.edit()
            .putFloat("total_gb", totalGb)
            .putInt("total_atendidos", totalAtendidos)
            .apply()
        guardarLista("historico_pedidos", historico)

        atualizarInterface()
        Toast.makeText(this, "Atendido: $registo", Toast.LENGTH_SHORT).show()
    }

    private fun mostrarHistorico() {
        val historico = lerLista("historico_pedidos")
        val msg = if (historico.isEmpty()) "Sem pedidos no histórico." else historico.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Histórico de Envios")
            .setMessage(msg)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun mostrarDialogoMeta() {
        val metaAtual = prefs.getFloat("meta_gb", 1000f)
        val totalAtual = prefs.getFloat("total_gb", 0f)
        val input = EditText(this).apply {
            setText(metaAtual.toString())
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Meta de Vendas (GB)")
            .setMessage("Atual: ${formatarGb(totalAtual)} GB / Meta: ${formatarGb(metaAtual)} GB")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val novaMeta = input.text.toString().trim().replace(",", ".").toFloatOrNull()
                if (novaMeta != null && novaMeta > 0f) {
                    prefs.edit().putFloat("meta_gb", novaMeta).apply()
                    Toast.makeText(this, "Meta atualizada para ${formatarGb(novaMeta)} GB", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        tvFilaEspera.text = "FILA DE ESPERA (${fila.size})"
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
