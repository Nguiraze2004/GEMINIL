package com.nguiraze.net

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val codigoPermissaoChamada = 101
    private val prefsNome = "nguiraze_net_prefs"
    private val limiteDiarioPorSim = 10

    private lateinit var refs: PainelRefs
    private var filaPausada = false
    private var simPreferido: Int? = null

    private val receptorSucessoUssd = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences(prefsNome, MODE_PRIVATE)
            val simUsado = prefs.getInt("sim_em_uso", 1)
            val atualSim = prefs.getInt("sim_${simUsado}_count", 0) + 1
            val concluidas = prefs.getInt("total_concluidas", 0) + 1
            val mbEnviados = prefs.getInt("ultimo_mb_enviado", 0)
            val gbAcumulados = prefs.getFloat("gb_vendidos", 0f) + (mbEnviados / 1024f)

            prefs.edit()
                .putInt("sim_${simUsado}_count", atualSim)
                .putInt("total_concluidas", concluidas)
                .putFloat("gb_vendidos", gbAcumulados)
                .remove("pedido_pendente")
                .apply()

            atualizarPainel()
            processarProximoDaFila()
        }
    }

    private val receptorFalhaUssd = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val motivo = intent?.getStringExtra("motivo") ?: "Falha no fluxo USSD"
            getSharedPreferences(prefsNome, MODE_PRIVATE)
                .edit()
                .remove("pedido_pendente")
                .apply()

            Toast.makeText(this@MainActivity, motivo, Toast.LENGTH_LONG).show()
            atualizarPainel()
            processarProximoDaFila()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val montagem = montarPainelPrincipal(
            onEnvioManual = {
                enfileirarPedido("852194010", 100, simPreferido)
                Toast.makeText(this, "Pedido de teste (100MB) adicionado à fila", Toast.LENGTH_SHORT).show()
            },
            onResumo = { atualizarPainel() },
            onSim1 = {
                simPreferido = 1
                Toast.makeText(this, "SIM 1 selecionado como preferencial", Toast.LENGTH_SHORT).show()
            },
            onSim2 = {
                simPreferido = 2
                Toast.makeText(this, "SIM 2 selecionado como preferencial", Toast.LENGTH_SHORT).show()
            },
            onTabela = {
                Toast.makeText(this, "Tabela activa: 10 MT = 100 MB", Toast.LENGTH_SHORT).show()
            },
            onResetSim1 = { resetarContadorSim(1) },
            onResetSim2 = { resetarContadorSim(2) },
            onComprovantes = { atualizarPainel() },
            onDisparar = { processarProximoDaFila() },
            onPausar = {
                filaPausada = !filaPausada
                refs.btnPausar.text = if (filaPausada) "Retomar" else "II Pausar"
                if (!filaPausada) processarProximoDaFila()
            }
        )

        refs = montagem.refs
        setContentView(montagem.view)
        solicitarPermissaoChamadaSeNecessario()
        atualizarPainel()
    }

    override fun onResume() {
        super.onResume()
        val filtroSucesso = IntentFilter(UssdAccessibilityService.ACAO_SUCESSO_USSD)
        val filtroFalha = IntentFilter(UssdAccessibilityService.ACAO_FALHA_USSD)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receptorSucessoUssd, filtroSucesso, RECEIVER_NOT_EXPORTED)
            registerReceiver(receptorFalhaUssd, filtroFalha, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receptorSucessoUssd, filtroSucesso)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receptorFalhaUssd, filtroFalha)
        }
        atualizarPainel()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receptorSucessoUssd)
        unregisterReceiver(receptorFalhaUssd)
    }

    // --- Controle de Fila e Disparo USSD ---

    fun enfileirarPedido(numeroCliente: String, megabytes: Int, simEscolhido: Int? = null) {
        val prefs = getSharedPreferences(prefsNome, MODE_PRIVATE)
        val fila = JSONArray(prefs.getString("fila_pedidos", "[]"))
        val item = JSONObject().apply {
            put("numero", numeroCliente.trim())
            put("mb", megabytes)
            if (simEscolhido != null) put("sim", simEscolhido)
        }
        fila.put(item)
        prefs.edit().putString("fila_pedidos", fila.toString()).apply()

        atualizarPainel()
        if (!filaPausada) {
            processarProximoDaFila()
        }
    }

    fun processarProximoDaFila() {
        if (filaPausada) return
        if (!temPermissaoChamada()) {
            solicitarPermissaoChamadaSeNecessario()
            return
        }

        val prefs = getSharedPreferences(prefsNome, MODE_PRIVATE)
        if (prefs.contains("pedido_pendente")) return

        val fila = JSONArray(prefs.getString("fila_pedidos", "[]"))
        if (fila.length() == 0) {
            atualizarPainel()
            return
        }

        val simEscolhido = escolherSimDisponivel(prefs)
        if (simEscolhido == null) {
            Toast.makeText(this, "Limite diário atingido em ambos os SIMs", Toast.LENGTH_LONG).show()
            return
        }

        val pedido = fila.getJSONObject(0)
        val novaFila = JSONArray()
        for (i in 1 until fila.length()) {
            novaFila.put(fila.getJSONObject(i))
        }

        val numero = pedido.getString("numero")
        val mb = pedido.getInt("mb")
        val simFinal = if (pedido.has("sim")) pedido.getInt("sim") else simEscolhido

        prefs.edit()
            .putString("fila_pedidos", novaFila.toString())
            .putString("pedido_pendente", pedido.toString())
            .putInt("sim_em_uso", simFinal)
            .putInt("ultimo_mb_enviado", mb)
            .apply()

        UssdAccessibilityService.iniciarFluxo(numero, mb)
        val iniciou = chamarPorIntent("*162#", simFinal)
        if (!iniciou) {
            UssdAccessibilityService.pararFluxo()
            prefs.edit().remove("pedido_pendente").apply()
            Toast.makeText(this, "Não foi possível iniciar a chamada USSD", Toast.LENGTH_SHORT).show()
        }
        atualizarPainel()
    }

    private fun escolherSimDisponivel(prefs: android.content.SharedPreferences): Int? {
        val c1 = prefs.getInt("sim_1_count", 0)
        val c2 = prefs.getInt("sim_2_count", 0)

        val pref = simPreferido
        if (pref == 1 && c1 < limiteDiarioPorSim) return 1
        if (pref == 2 && c2 < limiteDiarioPorSim) return 2
        if (c1 < limiteDiarioPorSim) return 1
        if (c2 < limiteDiarioPorSim) return 2
        return null
    }

    private fun chamarPorIntent(codigoUssd: String, slotSim: Int): Boolean {
        if (!temPermissaoChamada()) return false

        return try {
            val codificado = codigoUssd.replace("#", "%23")
            val uri = Uri.parse("tel:$codificado")
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("com.android.phone.extra.slot", slotSim - 1)
                putExtra("simSlot", slotSim - 1)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && temPermissaoTelefone()) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val contas = telecomManager?.callCapablePhoneAccounts
                if (contas != null && contas.size >= slotSim) {
                    intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, contas[slotSim - 1])
                }
            }

            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resetarContadorSim(slot: Int) {
        getSharedPreferences(prefsNome, MODE_PRIVATE)
            .edit()
            .putInt("sim_${slot}_count", 0)
            .apply()
        atualizarPainel()
        Toast.makeText(this, "Contador do SIM $slot zerado", Toast.LENGTH_SHORT).show()
    }

    private fun atualizarPainel() {
        val prefs = getSharedPreferences(prefsNome, MODE_PRIVATE)
        val fila = JSONArray(prefs.getString("fila_pedidos", "[]"))
        val concluidas = prefs.getInt("total_concluidas", 0)
        val gbVendidos = prefs.getFloat("gb_vendidos", 0f)
        val c1 = prefs.getInt("sim_1_count", 0)
        val c2 = prefs.getInt("sim_2_count", 0)

        refs.tvFilaCount.text = fila.length().toString()
        refs.tvGbVendidos.text = String.format("%.2f", gbVendidos)
        refs.tvConcluidasCount.text = concluidas.toString()
        refs.tvSim1Count.text = "$c1/$limiteDiarioPorSim"
        refs.tvSim2Count.text = "$c2/$limiteDiarioPorSim"

        if (fila.length() == 0) {
            refs.tvFilaEspera.text = "Sem pedidos na fila."
        } else {
            val sb = StringBuilder()
            for (i in 0 until fila.length()) {
                val item = fila.getJSONObject(i)
                sb.append("• ${item.getString("numero")} — ${item.getInt("mb")} MB\n")
            }
            refs.tvFilaEspera.text = sb.toString().trim()
        }
    }

    private fun temPermissaoChamada(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun temPermissaoTelefone(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun solicitarPermissaoChamadaSeNecessario() {
        val pendentes = mutableListOf<String>()
        if (!temPermissaoChamada()) pendentes.add(Manifest.permission.CALL_PHONE)
        if (!temPermissaoTelefone()) pendentes.add(Manifest.permission.READ_PHONE_STATE)
        if (pendentes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, pendentes.toTypedArray(), codigoPermissaoChamada)
        }
    }

    // --- Montagem Visual (Helpers) ---

    data class PainelRefs(
        val tvFilaCount: TextView,
        val tvGbVendidos: TextView,
        val tvConcluidasCount: TextView,
        val tvSim1Count: TextView,
        val tvSim2Count: TextView,
        val tvFilaEspera: TextView,
        val btnPausar: TextView
    )

    data class PainelMontado(
        val view: View,
        val refs: PainelRefs
    )

    private fun montarPainelPrincipal(
        onEnvioManual: () -> Unit,
        onResumo: () -> Unit,
        onSim1: () -> Unit,
        onSim2: () -> Unit,
        onTabela: () -> Unit,
        onResetSim1: () -> Unit,
        onResetSim2: () -> Unit,
        onComprovantes: () -> Unit,
        onDisparar: () -> Unit,
        onPausar: () -> Unit
    ): PainelMontado {
        val corFundoTela = Color.parseColor("#06080A")
        val corCartao = Color.parseColor("#0C1014")
        val corBorda = Color.parseColor("#1D242B")
        val corVerde = Color.parseColor("#2EE573")
        val corVermelho = Color.parseColor("#FF5A6E")
        val corAzul = Color.parseColor("#4FA3FF")
        val corAmarelo = Color.parseColor("#F5B727")
        val corCinza = Color.parseColor("#8A96A3")
        val corBranco = Color.WHITE

        val scroll = ScrollView(this).apply {
            setBackgroundColor(corFundoTela)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cardStatus = cardBox(corCartao, corBorda).apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                            addView(txt("BOT ONLINE", 15f, corBranco, true))
                            addView(txt("Ligado ao bot • M-Pesa SMS ativo", 12f, corCinza, false))
                        }
                    )

                    addView(
                        pillBadge(
                            "• Ativo",
                            corVerde,
                            Color.parseColor("#0B1410"),
                            Color.parseColor("#163826")
                        )
                    )
                }
            )

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                    addView(smallActionBtn("ENVIO MANUAL", corVerde, 1f, 0, onEnvioManual))
                }
            )
        }
        raiz.addView(cardStatus)

        val linha1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(smallActionBtn("Resumo", corVerde, 1f, dp(6), onResumo))
            addView(smallActionBtn("SIM 1", corVermelho, 1f, dp(6), onSim1))
            addView(smallActionBtn("SIM 2", corAzul, 1f, dp(6), onSim2))
            addView(smallActionBtn("Tabela", corAmarelo, 1f, 0, onTabela))
        }
        raiz.addView(linha1)

        val linha2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            addView(smallActionBtn("Reset S1", corBranco, 1f, dp(6), onResetSim1))
            addView(smallActionBtn("Reset S2", corBranco, 1f, dp(6), onResetSim2))
            addView(smallActionBtn("Comprov.", corBranco, 1f, dp(6), onComprovantes))
            addView(smallActionBtn("Disparar", corVerde, 1f, 0, onDisparar))
        }
        raiz.addView(linha2)

        val tvFilaCount = txt("0", 22f, corBranco, true).apply { setPadding(0, dp(6), 0, dp(2)) }
        val tvGbVendidos = txt("0.00", 14f, corVermelho, true)
        val tvConcluidasCount = txt("0", 22f, corBranco, true).apply { setPadding(0, dp(6), 0, dp(2)) }

        val linhaMetricas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            val cardFila = cardBox(Color.parseColor("#120D10"), Color.parseColor("#3A181E")).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { rightMargin = dp(6) }
                addView(txt("NA FILA", 11f, corVermelho, true))
                addView(tvFilaCount)
                addView(tvGbVendidos)
                addView(txt("GB despachados", 11f, corCinza, false))
            }

            val cardConcluidas = cardBox(Color.parseColor("#0B1410"), Color.parseColor("#163826")).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { leftMargin = dp(6) }
                addView(txt("CONCLUÍDAS", 11f, corVerde, true))
                addView(tvConcluidasCount)
                addView(txt("Confirmadas ('transferiste')", 11f, corCinza, false))
            }

            addView(cardFila)
            addView(cardConcluidas)
        }
        raiz.addView(linhaMetricas)

        val tvSim1Count = txt("0/10", 20f, corBranco, true).apply { setPadding(0, dp(4), 0, 0) }
        val tvSim2Count = txt("0/10", 20f, corBranco, true).apply { setPadding(0, dp(4), 0, 0) }

        val linhaSims = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            val cardSim1 = cardBox(corCartao, corBorda).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { rightMargin = dp(6) }
                addView(txt("SIM 1", 11f, corCinza, true))
                addView(tvSim1Count)
            }

            val cardSim2 = cardBox(corCartao, corBorda).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { leftMargin = dp(6) }
                addView(txt("SIM 2", 11f, corCinza, true))
                addView(tvSim2Count)
            }

            addView(cardSim1)
            addView(cardSim2)
        }
        raiz.addView(linhaSims)

        val btnPausar = smallActionBtn("II Pausar", corAmarelo, 0.35f, 0, onPausar)
        val tvFilaEspera = txt("Sem pedidos na fila.", 13f, corBranco, false).apply {
            setPadding(0, dp(8), 0, 0)
        }

        val cardFilaEspera = cardBox(corCartao, corBorda).apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        txt("FILA DE ESPERA", 12f, corCinza, true).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        }
                    )
                    addView(btnPausar)
                }
            )
            addView(tvFilaEspera)
        }
        raiz.addView(cardFilaEspera)

        scroll.addView(raiz)

        return PainelMontado(
            view = scroll,
            refs = PainelRefs(
                tvFilaCount = tvFilaCount,
                tvGbVendidos = tvGbVendidos,
                tvConcluidasCount = tvConcluidasCount,
                tvSim1Count = tvSim1Count,
                tvSim2Count = tvSim2Count,
                tvFilaEspera = tvFilaEspera,
                btnPausar = btnPausar
            )
        )
    }

    private fun dp(valor: Int): Int {
        return (valor * resources.displayMetrics.density).toInt()
    }

    private fun cardBox(corFundo: Int, corBorda: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            background = roundRect(corFundo, corBorda, 14)
        }
    }

    private fun smallActionBtn(
        texto: String,
        cor: Int,
        peso: Float = 1f,
        margemFimDp: Int = 0,
        acao: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = texto
            textSize = 11f
            setTextColor(cor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), peso).apply {
                rightMargin = dp(margemFimDp)
            }
            background = roundRect(Color.rgb(8, 22, 48), cor, 10)
            setOnClickListener { acao() }
        }
    }

    private fun pillBadge(
        texto: String,
        corTexto: Int,
        corFundo: Int,
        corBorda: Int
    ): TextView {
        return TextView(this).apply {
            this.text = texto
            textSize = 10f
            setTextColor(corTexto)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundRect(corFundo, corBorda, 20)
        }
    }

    private fun roundRect(
        corFundo: Int,
        corBorda: Int,
        raioDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(corFundo)
            cornerRadius = dp(raioDp).toFloat()
            setStroke(dp(1), corBorda)
        }
    }

    private fun oval(corFundo: Int, corBorda: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(corFundo)
            setStroke(dp(1), corBorda)
        }
    }

    private fun txt(
        texto: String,
        tamanho: Float,
        cor: Int,
        negrito: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = texto
            textSize = tamanho
            setTextColor(cor)
            if (negrito) setTypeface(null, Typeface.BOLD)
        }
    }
}
