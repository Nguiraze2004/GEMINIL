package com.nguiraze.net

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 1. MODELOS
// ============================================================

data class PedidoFila(
    val id: String,
    val numeroCliente: String,
    val megas: Int,
    val hora: String
)

data class TransacaoConcluida(
    val numeroCliente: String,
    val megas: Int,
    val valorMT: Int,
    val hora: String,
    val simInfo: String,
    val status: String // Entregue, Falhou ou Incerto
)

data class AgenteExecutor(
    val id: Int,
    val nome: String,
    val sigla: String,
    var online: Boolean,
    var vendas: Int,
    val simTag: String
)

// ============================================================
// 2. ACTIVITY PRINCIPAL
// ============================================================

class MainActivity : Activity() {

    // -------------------- CORES --------------------

    private val bgTop = Color.rgb(5, 9, 16)
    private val bgBot = Color.rgb(5, 9, 16)
    private val cardNavy = Color.rgb(14, 20, 30)
    private val cardDark = Color.rgb(12, 18, 27)
    private val borderBlue = Color.rgb(29, 40, 55)

    private val cyan = Color.rgb(65, 133, 245)
    private val green = Color.rgb(48, 198, 128)
    private val greenBg = Color.rgb(13, 49, 39)
    private val yellow = Color.rgb(244, 185, 55)
    private val red = Color.rgb(235, 70, 75)

    private val white = Color.WHITE
    private val lightBlue = Color.rgb(151, 161, 177)

    // -------------------- ELEMENTOS PRINCIPAIS --------------------

    private lateinit var content: LinearLayout
    private lateinit var navBar: LinearLayout

    private var abaAtual = "Início"

    // -------------------- DADOS DA APLICAÇÃO --------------------

    private val concluidos = mutableListOf<TransacaoConcluida>()
    private val filaEspera = mutableListOf<PedidoFila>()
    private val executores = mutableListOf<AgenteExecutor>()

    private var pedidoEmCurso: PedidoFila? = null

    // -------------------- CONTROLO DOS SIMs --------------------

    private var simSelecionado = 1
    private var sim1Usados = 0
    private var sim2Usados = 0
    private val limiteSim = 10

    private var filtroConclusao = "Todos"
    private var filaPausada = false
    private var metaGb = 10

    private val preferencias by lazy {
        getSharedPreferences("nguiraze_net_dados", Context.MODE_PRIVATE)
    }

        private fun isAcessibilidadeAtiva(): Boolean {
        val esperado = "$packageName/${UssdAccessibilityService::class.java.canonicalName}"
        val ativos = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return ativos.split(':').any { it.equals(esperado, ignoreCase = true) }
    }

    private val receptorUssdSucesso = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val atual = pedidoEmCurso ?: return
            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            if (simSelecionado == 1) sim1Usados++ else sim2Usados++
            guardarContadores()

            concluidos.add(
                0,
                TransacaoConcluida(
                    numeroCliente = atual.numeroCliente,
                    megas = atual.megas,
                    valorMT = atual.megas / 10,
                    hora = hora,
                    simInfo = "SIM $simSelecionado",
                    status = "Entregue"
                )
            )
            pedidoEmCurso = null
            navegarPara(abaAtual)
            processarProximoDaFila()
        }
    }

    private val receptorUssdFalha = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val atual = pedidoEmCurso ?: return
            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            concluidos.add(
                0,
                TransacaoConcluida(
                    numeroCliente = atual.numeroCliente,
                    megas = atual.megas,
                    valorMT = atual.megas / 10,
                    hora = hora,
                    simInfo = "SIM $simSelecionado",
                    status = "Falhou"
                )
            )
            pedidoEmCurso = null
            navegarPara(abaAtual)
            processarProximoDaFila()
        }
    }

    private val receptorNovoSms = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val numero = intent?.getStringExtra("numero") ?: return
            val megas = intent.getIntExtra("megas", 0)
            if (megas <= 0) return

            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            filaEspera.add(
                PedidoFila(
                    id = System.currentTimeMillis().toString(),
                    numeroCliente = numero,
                    megas = megas,
                    hora = hora
                )
            )
            navegarPara(abaAtual)
            processarProximoDaFila()
        }
    }

    override fun onResume() {
        super.onResume()
        val f1 = android.content.IntentFilter(UssdAccessibilityService.ACAO_SUCESSO_USSD)
        val f2 = android.content.IntentFilter(UssdAccessibilityService.ACAO_FALHA_USSD)
        val f3 = android.content.IntentFilter(SmsReceiver.ACAO_NOVO_PEDIDO_SMS)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receptorUssdSucesso, f1, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(receptorUssdFalha, f2, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(receptorNovoSms, f3, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receptorUssdSucesso, f1)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receptorUssdFalha, f2)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receptorNovoSms, f3)
        }
        navegarPara(abaAtual)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receptorUssdSucesso)
        unregisterReceiver(receptorUssdFalha)
        unregisterReceiver(receptorNovoSms)
    }


    // ============================================================
    // 3. INICIALIZAÇÃO
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        carregarContadores()
        verificarNovoDia()

        window.statusBarColor = bgTop
        window.navigationBarColor = bgBot

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgTop)
        }

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(6, 16, 40))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        root.addView(
            navBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            )
        )

        setContentView(root)
        navegarPara("Início")
    }

    // ============================================================
    // 4. NAVEGAÇÃO
    // ============================================================

    private fun navegarPara(aba: String) {
        abaAtual = aba
        content.removeAllViews()

        when (aba) {
            "Início" -> telaInicio()
            "Fila" -> telaFilaExecutores()
            "Concluídos" -> telaConcluidos()
            "SIMs" -> telaSims()
            "Config" -> telaConfig()
            else -> telaInicio()
        }

        renderizarBarraInferior()
    }

    // ============================================================
    // 5. TELA INICIAL
    // ============================================================

    private fun telaInicio() {
        val entreguesHoje = concluidos.filter { it.status == "Entregue" }
        val totalConcluidas = entreguesHoje.size
        val totalMegas = entreguesHoje.sumBy { it.megas }
        val gbCalculados = totalMegas / 1024.0

        val progresso = if (metaGb > 0) {
            (gbCalculados / metaGb.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

        val gbStr = String.format(Locale("pt", "MZ"), "%.2f", gbCalculados)

        // CABEÇALHO
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatar = txt("🤖", 19f, white).apply {
            gravity = Gravity.CENTER
            background = oval(Color.rgb(12, 42, 95), cyan)
        }

        header.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)

            addView(txt("NGUIRAZE NET", 16f, cyan, true))
            addView(txt("Versão instalada: 1.86 (86)", 11f, lightBlue))
        }

        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

                val ligado = isAcessibilidadeAtiva()
        header.addView(
            pillBadge(
                if (ligado) "● USSD ligado" else "● USSD não ligado",
                if (ligado) green else yellow,
                if (ligado) greenBg else Color.rgb(55, 43, 12),
                if (ligado) green else yellow
            ).apply {
                setOnClickListener {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        )


        content.addView(header)

        // CARTÃO DE RESUMO
            val summaryCard = cardBox(cardNavy, borderBlue).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                RingProgressView(
                    this@MainActivity,
                    progresso,
                    gbStr,
                    "GB VENDIDOS"
                ),
                LinearLayout.LayoutParams(dp(132), dp(132)).apply {
                    rightMargin = dp(4)
                }
            )

            val pedidosCircle = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = oval(Color.rgb(8, 48, 39), green)

                addView(
                    txt("$totalConcluidas", 25f, white, true).apply {
                        gravity = Gravity.CENTER
                    }
                )
                addView(txt("TOTAL", 10f, green, true).apply {
                    gravity = Gravity.CENTER
                })
                addView(txt("ATENDIDOS", 10f, green, true).apply {
                    gravity = Gravity.CENTER
                })
            }

            addView(
                pedidosCircle,
                LinearLayout.LayoutParams(dp(132), dp(132)).apply {
                    leftMargin = dp(24)
                }
            )
        }


        content.addView(summaryCard)

        // ATALHOS
        val iconsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(16)
            }
        }

        val atalhos = listOf(
            Triple("➤", "Enviar megas", { navegarPara("Fila") }),
            Triple("☰", "Fila", { navegarPara("Fila") }),
            Triple("▤", "Histórico", { navegarPara("Concluídos") }),
            Triple("◉", "Meta", {
                Toast.makeText(
                    this,
                    "Meta diária: $metaGb GB",
                    Toast.LENGTH_SHORT
                ).show()
            })
        )

        atalhos.forEach { item ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                val circle = txt(item.first, 17f, white, true).apply {
                    gravity = Gravity.CENTER
                    background = oval(
                        Color.rgb(14, 65, 165),
                        Color.rgb(45, 135, 255)
                    )
                }

                addView(circle, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(
                    txt(item.second, 11f, white, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(5), 0, 0)
                    }
                )

                setOnClickListener { item.third() }
            }

            iconsRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }

        content.addView(iconsRow)

        // CABEÇALHO DOS SIMs
        val simHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            }

            addView(
                txt("CARTÕES SIM", 13f, lightBlue, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(
                pillBadge("+ Gerir SIMs", cyan, cardDark, cyan).apply {
                    setOnClickListener { navegarPara("SIMs") }
                }
            )
        }

        content.addView(simHeader)

        // CARTÕES SIM
        val simsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            }
        }

        fun miniSimBox(numero: Int, usados: Int): LinearLayout {
            val ativo = simSelecionado == numero

            return cardBox(
                if (ativo) Color.rgb(10, 38, 36) else cardDark,
                if (ativo) green else borderBlue
            ).apply {
                val top = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL

                    addView(
                        txt(
                            "SIM $numero",
                            12f,
                            if (ativo) green else lightBlue,
                            true
                        ),
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )

                    if (ativo) addView(txt("●", 12f, green))
                }

                addView(top)
                addView(txt("$usados/$limiteSim", 22f, white, true))
                addView(
                    txt("transferências", 11f, lightBlue).apply {
                        setPadding(0, 0, 0, dp(8))
                    }
                )

                val btns = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                if (!ativo) {
                    btns.addView(
                        smallActionBtn("Usar", green) {
                            simSelecionado = numero
                            guardarContadores()
                            navegarPara("Início")
                        },
                        LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                            rightMargin = dp(4)
                        }
                    )
                }

                btns.addView(
                    smallActionBtn("Reset", yellow) {
                        if (numero == 1) sim1Usados = 0 else sim2Usados = 0
                        guardarContadores()
                        navegarPara("Início")
                    },
                    LinearLayout.LayoutParams(0, dp(30), 1f)
                )

                addView(btns)
            }
        }

        simsRow.addView(
            miniSimBox(1, sim1Usados),
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                rightMargin = dp(6)
            }
        )

        simsRow.addView(
            miniSimBox(2, sim2Usados),
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(6)
            }
        )

        content.addView(simsRow)

        content.addView(
            txt("Contadores diários dos cartões SIM", 11f, lightBlue).apply {
                setPadding(0, 0, 0, dp(10))
            }
        )

        // FILA
        val filaBar = cardBox(cardDark, borderBlue).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                txt("FILA DE ESPERA (${filaEspera.size})", 12f, lightBlue, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(
                smallActionBtn(
                    if (filaPausada) "▶ Retomar" else "❚❚ Pausar",
                    yellow
                ) {
                    filaPausada = !filaPausada
                    navegarPara("Início")
                },
                LinearLayout.LayoutParams(dp(95), dp(32))
            )
        }

        content.addView(filaBar)

        // ÚLTIMOS PEDIDOS
        content.addView(
            txt("Últimos pedidos", 15f, white, true).apply {
                setPadding(0, dp(4), 0, dp(8))
            }
        )

        val ultimos = concluidos
            .filter { it.status == "Entregue" }
            .take(3)

        if (ultimos.isEmpty()) {
            content.addView(
                txt(
                    "Ainda não existem transferências confirmadas.",
                    12f,
                    lightBlue
                ).apply {
                    setPadding(0, dp(4), 0, dp(8))
                }
            )
        } else {
            ultimos.forEach { content.addView(buildTransacaoRow(it)) }
        }
    }

     // ============================================================
    // 6. TELA FILA E EXECUTOR
    // ============================================================

    private fun telaFilaExecutores() {

        // CABEÇALHO
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val voltar = txt("‹", 28f, white, true).apply {
                setPadding(0, 0, dp(12), 0)
                setOnClickListener { navegarPara("Início") }
            }
            addView(voltar)

            val titulo = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(txt("Painel do Executor", 17f, white, true))
                addView(txt("Este aparelho · Painel local", 11f, lightBlue))
            }
            addView(titulo, LinearLayout.LayoutParams(0, -2, 1f))

            addView(txt("▣", 20f, lightBlue))
            addView(txt("  $", 20f, yellow))
        }

        content.addView(topBar)


    // PESQUISA
    val pesquisa = EditText(this).apply {
        hint = "⌕  Buscar por número ou nome do Agente..."
        setHintTextColor(lightBlue)
        setTextColor(white)
        textSize = 13f
        setSingleLine(true)
        setPadding(dp(16), 0, dp(12), 0)
        background = roundRect(cardDark, borderBlue, 28)
    }

    content.addView(
        pesquisa,
        LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(14)
            bottomMargin = dp(12)
        }
    )

    // INDICADORES
    val indicadores = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun indicador(valor: String, descricao: String, cor: Int): LinearLayout {
        return cardBox(cardDark, borderBlue).apply {
            gravity = Gravity.CENTER
            addView(txt(valor, 22f, cor, true))
            addView(txt(descricao, 10f, lightBlue))
        }
    }

    val online = executores.count { it.online }

    val cards = listOf(
        indicador("${online}/${executores.size}", "online", green),
        indicador("${if (pedidoEmCurso != null) 1 else 0}", "em curso", yellow),
        indicador("${filaEspera.size}", "em espera", cyan),
        indicador("0", "bloqueados", red)
    )

    cards.forEachIndexed { index, card ->
        indicadores.addView(
            card,
            LinearLayout.LayoutParams(0, dp(90), 1f).apply {
                if (index != 0) leftMargin = dp(4)
                if (index != 3) rightMargin = dp(4)
            }
        )
    }

    content.addView(indicadores)

    // TÍTULO ENVIO MANUAL
    content.addView(
        txt("▶  ENVIO MANUAL", 11f, lightBlue, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
    )

    // CAMPOS DE ENTRADA
    val numeroCampo = EditText(this).apply {
        hint = "▣  Número do cliente"
        setHintTextColor(lightBlue)
        setTextColor(white)
        textSize = 14f
        inputType = InputType.TYPE_CLASS_PHONE
        isSingleLine = true
        setPadding(dp(16), 0, dp(12), 0)
        background = roundRect(Color.BLACK, borderBlue, 18)
    }

    val megasCampo = EditText(this).apply {
        hint = "⌁  Megabytes"
        setHintTextColor(lightBlue)
        setTextColor(white)
        textSize = 14f
        inputType = InputType.TYPE_CLASS_NUMBER
        isSingleLine = true
        setPadding(dp(16), 0, dp(12), 0)
        background = roundRect(Color.BLACK, borderBlue, 18)
    }

    // CARTÃO DE ENVIO
    val envioCard = cardBox(cardDark, borderBlue)

    envioCard.addView(
        txt("➤   Envio manual", 14f, white, true)
    )

    envioCard.addView(
        txt(
            "Preencha os dados para registar um pedido.",
            11f,
            lightBlue
        ).apply {
            setPadding(0, dp(4), 0, dp(12))
        }
    )

    envioCard.addView(
        txt("PACOTES RÁPIDOS", 10f, lightBlue, true)
    )

    val pacotes = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val pacote100 = TextView(this).apply {
        text = "100MB\n10 MT"
        textSize = 12f
        setTextColor(green)
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = roundRect(Color.BLACK, green, 14)
        setPadding(dp(18), dp(8), dp(18), dp(8))

        setOnClickListener {
            megasCampo.setText("100")
        }
    }

    pacotes.addView(pacote100)
    pacotes.addView(
        txt("  ›", 22f, white).apply {
            setPadding(dp(12), 0, 0, 0)
        }
    )

    envioCard.addView(
        pacotes,
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(14)
        }
    )

    envioCard.addView(
        View(this).apply {
            setBackgroundColor(borderBlue)
        },
        LinearLayout.LayoutParams(-1, dp(1)).apply {
            bottomMargin = dp(14)
        }
    )

    envioCard.addView(
        numeroCampo,
        LinearLayout.LayoutParams(-1, dp(46)).apply {
            bottomMargin = dp(14)
        }
    )

    envioCard.addView(
        megasCampo,
        LinearLayout.LayoutParams(-1, dp(46)).apply {
            bottomMargin = dp(6)
        }
    )

    envioCard.addView(
        txt(
            "100MB a 10GB. Acima disso, 20, 30, 40 ou 50GB.",
            10f,
            lightBlue
        ).apply {
            setPadding(dp(4), dp(4), 0, dp(12))
        }
    )

    val despachar = smallActionBtn("➤  Despachar para um Agente", green) {

        val numero = numeroCampo.text.toString().trim()
        val megas = megasCampo.text.toString().trim().toIntOrNull()

        if (numero.isEmpty()) {
            Toast.makeText(this, "Introduza o número do cliente.", Toast.LENGTH_SHORT).show()
        } else if (numero.length < 9) {
            Toast.makeText(this, "Verifique o número do cliente.", Toast.LENGTH_SHORT).show()
        } else if (megas == null || megas <= 0) {
            Toast.makeText(this, "Introduza uma quantidade válida de MB.", Toast.LENGTH_SHORT).show()
        } else {
            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            filaEspera.add(
                PedidoFila(
                    System.currentTimeMillis().toString(),
                    numero,
                    megas,
                    hora
                )
            )

            processarProximoDaFila()

            Toast.makeText(
                this,
                "A processar pedido na fila...",
                Toast.LENGTH_SHORT
            ).show()

            navegarPara("Fila")
        }
    }


    envioCard.addView(
        despachar,
        LinearLayout.LayoutParams(-1, dp(42))
    )

    content.addView(envioCard)

    // EXECUTORES
    content.addView(
        txt("▣  EXECUTORES (${executores.size})", 11f, lightBlue, true).apply {
            setPadding(0, dp(14), 0, dp(8))
        }
    )

    if (executores.isEmpty()) {
        val aparelho = cardBox(cardDark, borderBlue).apply {
            addView(txt("ES   Este aparelho", 13f, white, true))
            addView(txt("Dispositivo local", 10f, lightBlue))
            addView(
                txt("● USSD não ligado", 11f, yellow).apply {
                    setPadding(0, dp(8), 0, dp(4))
                }
            )
            addView(txt("0 vendas confirmadas", 11f, lightBlue))
        }
        content.addView(aparelho)
    } else {
        executores.forEach { agente ->
            val agenteCard = cardBox(cardDark, borderBlue).apply {
                addView(txt("${agente.sigla}   ${agente.nome}", 14f, white, true))
                addView(txt("#${agente.id}", 10f, lightBlue))
                addView(
                    txt(
                        if (agente.online) "● Online" else "● Offline",
                        11f,
                        if (agente.online) green else yellow
                    ).apply {
                        setPadding(0, dp(6), 0, dp(4))
                    }
                )
                addView(txt("${agente.vendas} vendas · ${agente.simTag}", 11f, lightBlue))
            }
            content.addView(agenteCard)
        }
    }

    // EM CURSO
    content.addView(
        txt("⌛  EM CURSO AGORA", 11f, lightBlue, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
    )

    val emCurso = cardBox(cardDark, borderBlue)

    if (pedidoEmCurso == null) {
        emCurso.addView(txt("Nenhuma ordem em curso.", 12f, lightBlue))
    } else {
        emCurso.addView(
            txt("${pedidoEmCurso!!.numeroCliente} · ${pedidoEmCurso!!.megas} MB", 13f, white, true)
        )
        emCurso.addView(txt("Aguardando confirmação da operadora.", 11f, yellow))
    }

    content.addView(emCurso)

    // FILA DE ESPERA
    content.addView(
        txt("⌛  FILA DE ESPERA (${filaEspera.size})", 11f, lightBlue, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
    )

    if (filaEspera.isEmpty()) {
        content.addView(
            cardBox(cardDark, borderBlue).apply {
                addView(txt("Sem pedidos à espera de Agente livre.", 12f, lightBlue))
            }
        )
    } else {
        filaEspera.forEachIndexed { index, pedido ->
            content.addView(
                cardBox(cardDark, borderBlue).apply {
                    addView(
                        txt(
                            "${index + 1}.  ${pedido.numeroCliente}",
                            13f,
                            white,
                            true
                        )
                    )
                    addView(
                        txt("${pedido.megas} MB · ${pedido.hora}", 11f, lightBlue)
                    )
                }
            )
        }
    }

    // HISTÓRICO RECENTE
    content.addView(
        txt("▤  HISTÓRICO RECENTE (${concluidos.size})", 11f, lightBlue, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
    )

    val recentes = concluidos.take(5)

    if (recentes.isEmpty()) {
        content.addView(
            cardBox(cardDark, borderBlue).apply {
                addView(txt("Ainda não há transferências.", 12f, lightBlue))
            }
        )
    } else {
        recentes.forEach { content.addView(buildTransacaoRow(it)) }
    }
}


    // ============================================================
    // 7. TELA DE HISTÓRICO
    // ============================================================

    private fun telaConcluidos() {
        content.addView(txt("Conclusão", 19f, white, true))

        content.addView(
            txt("Histórico de transferências", 12f, lightBlue).apply {
                setPadding(0, 0, 0, dp(14))
            }
        )

        val filtros = listOf("Todos", "Entregue", "Falhou", "Incerto", "Em fila")
        val filtrosRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        filtros.forEach { filtro ->
            val selecionado = filtroConclusao == filtro

            val btn = TextView(this).apply {
                text = filtro
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (selecionado) cyan else lightBlue)
                background = roundRect(
                    if (selecionado) Color.rgb(8, 38, 68) else cardDark,
                    if (selecionado) cyan else borderBlue,
                    12
                )
                setOnClickListener {
                    filtroConclusao = filtro
                    navegarPara("Concluídos")
                }
            }

            filtrosRow.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (filtro != "Todos") leftMargin = dp(3)
                    if (filtro != "Em fila") rightMargin = dp(3)
                }
            )
        }

        content.addView(
            filtrosRow,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(16)
            }
        )

        val lista = when (filtroConclusao) {
            "Todos" -> concluidos.toList()
            "Entregue" -> concluidos.filter { it.status == "Entregue" }
            "Falhou" -> concluidos.filter { it.status == "Falhou" }
            "Incerto" -> concluidos.filter { it.status == "Incerto" }
            else -> emptyList()
        }

        val mostrarFila = filtroConclusao == "Todos" ||
            filtroConclusao == "Em fila"

        val total = lista.size + if (mostrarFila) filaEspera.size else 0

        content.addView(
            txt("$total REGISTOS", 12f, cyan, true).apply {
                setPadding(0, 0, 0, dp(10))
            }
        )

        lista.forEach { content.addView(buildTransacaoRow(it)) }

        if (mostrarFila) {
            filaEspera.forEach { pedido ->
                val linha = cardBox(cardDark, borderBlue).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    val info = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(txt(pedido.numeroCliente, 15f, white, true))
                        addView(
                            txt(
                                "${pedido.megas} MB · ${pedido.hora}",
                                11f,
                                lightBlue
                            )
                        )
                    }

                    addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(
                        pillBadge(
                            "Em fila",
                            yellow,
                            Color.rgb(55, 43, 12),
                            yellow
                        )
                    )
                }
                content.addView(linha)
            }
        }

        if (total == 0) {
            content.addView(
                cardBox(cardDark, borderBlue).apply {
                    addView(
                        txt(
                            "Não existem registos nesta categoria.",
                            13f,
                            lightBlue
                        )
                    )
                }
            )
        }
    }

// ============================================================
// 8. TELA DE GESTÃO DOS SIMs
// ============================================================

private fun telaSims() {

    // Cabeçalho
    content.addView(
        txt("Gestão de SIMs", 21f, white, true)
    )

    content.addView(
        txt("Gerir cartões, transferências e configurações", 12f, lightBlue)
            .apply {
                setPadding(0, 0, 0, dp(14))
            }
    )

    val usadosAtivo =
        if (simSelecionado == 1) sim1Usados else sim2Usados

    val restantes = (limiteSim - usadosAtivo).coerceAtLeast(0)

    // ========================================================
    // CARTÃO SIM ATIVO
    // ========================================================

    val ativoBox = cardBox(
        Color.rgb(10, 38, 36),
        green
    ).apply {

        addView(
            txt(
                "SIM $simSelecionado · ACTIVO",
                18f,
                green,
                true
            )
        )

        addView(
            txt(
                if (simSelecionado == 1)
                    "VODACOM · Slot 1"
                else
                    "MOVITEL · Slot 2",
                13f,
                lightBlue
            )
        )

        addView(
            txt(
                "Número do cartão",
                12f,
                lightBlue
            ).apply {
                setPadding(0, dp(12), 0, dp(2))
            }
        )

        addView(
            txt(
                "Número não configurado",
                15f,
                white,
                true
            )
        )

        addView(
            txt(
                "Transferências de hoje",
                12f,
                lightBlue
            ).apply {
                setPadding(0, dp(12), 0, dp(4))
            }
        )

        addView(
            txt(
                "$usadosAtivo / $limiteSim",
                25f,
                white,
                true
            )
        )

        addView(
            txt(
                "Restam $restantes transferências",
                13f,
                green,
                true
            ).apply {
                setPadding(0, dp(4), 0, dp(12))
            }
        )

        addView(
            smallActionBtn("Actualizar SIM activo", cyan) {
                navegarPara("SIMs")
            },
            LinearLayout.LayoutParams(-1, dp(40))
        )
    }

    content.addView(ativoBox)

    // ========================================================
    // SEGUNDO SIM
    // ========================================================

    content.addView(
        txt("OUTRO CARTÃO SIM", 13f, lightBlue, true)
            .apply {
                setPadding(0, dp(16), 0, dp(8))
            }
    )

    val outroNumero = if (simSelecionado == 1) 2 else 1
    val outrosUsados = if (outroNumero == 1) sim1Usados else sim2Usados
    val outroAtivo = simSelecionado == outroNumero

    val outroBox = cardBox(
        cardDark,
        borderBlue
    ).apply {

        addView(
            txt(
                "SIM $outroNumero",
                17f,
                white,
                true
            )
        )

        addView(
            txt(
                if (outroNumero == 1)
                    "VODACOM · Slot 1"
                else
                    "MOVITEL · Slot 2",
                13f,
                lightBlue
            )
        )

        addView(
            txt(
                "Transferências: $outrosUsados / $limiteSim",
                14f,
                white,
                true
            ).apply {
                setPadding(0, dp(8), 0, dp(10))
            }
        )

        if (!outroAtivo) {
            addView(
                smallActionBtn("Usar este SIM", green) {
                    simSelecionado = outroNumero
                    guardarContadores()
                    navegarPara("SIMs")
                },
                LinearLayout.LayoutParams(-1, dp(40))
            )
        }

        addView(
            smallActionBtn("Reiniciar contador", yellow) {
                if (outroNumero == 1) {
                    sim1Usados = 0
                } else {
                    sim2Usados = 0
                }

                guardarContadores()
                navegarPara("SIMs")
            },
            LinearLayout.LayoutParams(-1, dp(40)).apply {
                topMargin = dp(7)
            }
        )
    }

    content.addView(outroBox)

    // ========================================================
    // CONFIGURAÇÕES
    // ========================================================

    content.addView(
        txt("CONFIGURAÇÕES", 13f, lightBlue, true)
            .apply {
                setPadding(0, dp(16), 0, dp(8))
            }
    )

    val configBox = cardBox(
        cardDark,
        borderBlue
    ).apply {

        addView(
            txt("Limite diário por SIM", 15f, white, true)
        )

        addView(
            txt(
                "$limiteSim transferências por dia",
                13f,
                lightBlue
            ).apply {
                setPadding(0, dp(4), 0, dp(12))
            }
        )

        addView(
            txt("Sobra automática de megabytes", 15f, white, true)
        )

        addView(
            txt(
                "Configuração ainda não activada",
                13f,
                yellow
            ).apply {
                setPadding(0, dp(4), 0, dp(12))
            }
        )

        addView(
            txt("Reinício dos contadores", 15f, white, true)
        )

        addView(
            txt(
                "Todos os dias às 00:00",
                13f,
                lightBlue
            ).apply {
                setPadding(0, dp(4), 0, 0)
            }
        )
    }

    content.addView(configBox)

    // ========================================================
    // DIAGNÓSTICO USSD
    // ========================================================

    content.addView(
        txt("DIAGNÓSTICO USSD", 13f, lightBlue, true)
            .apply {
                setPadding(0, dp(16), 0, dp(8))
            }
    )

    val dadosUssd = getSharedPreferences(
        "ussd_debug",
        Context.MODE_PRIVATE
    )

    val ultimaLeitura = dadosUssd.getString(
        "ultima_leitura",
        "Ainda não foi capturado nenhum texto USSD."
    ) ?: "Sem leitura"

    val ultimaJanela = dadosUssd.getString(
        "ultima_janela",
        "Nenhuma janela detectada"
    ) ?: "Desconhecida"

    val diagnosticoBox = cardBox(
        cardDark,
        borderBlue
    ).apply {

        addView(
            txt("Estado do serviço", 14f, white, true)
        )

        addView(
            txt(
                "Acessibilidade activada",
                12f,
                green
            ).apply {
                setPadding(0, dp(5), 0, dp(10))
            }
        )

        addView(
            txt("Aplicação detectada", 13f, white, true)
        )

        addView(
            txt(
                ultimaJanela,
                11f,
                cyan
            ).apply {
                setPadding(0, dp(4), 0, dp(10))
            }
        )

        addView(
            txt("Último texto capturado", 13f, white, true)
        )

        addView(
            txt(
                ultimaLeitura,
                12f,
                lightBlue
            ).apply {
                setPadding(0, dp(6), 0, dp(12))
            }
        )

        addView(
            smallActionBtn("↻ Actualizar leitura", cyan) {
                navegarPara("SIMs")
            },
            LinearLayout.LayoutParams(-1, dp(40))
        )
    }

    content.addView(diagnosticoBox)


    // Aviso final
    content.addView(
        txt(
            if (usadosAtivo >= limiteSim) {
                "Limite atingido. Seleccione o outro SIM para continuar."
            } else {
                "SIM seleccionado: $simSelecionado · Restam $restantes transferências."
            },
            12f,
            if (usadosAtivo >= limiteSim) yellow else lightBlue
        ).apply {
            setPadding(0, dp(12), 0, dp(16))
        }
    )
}

    // ============================================================
    // 9. CONFIGURAÇÕES
    // ============================================================

    private fun telaConfig() {
        content.addView(txt("Configurações", 19f, white, true))

        content.addView(
            txt(
                "Ajustes do motor USSD e Acessibilidade",
                12f,
                lightBlue
            ).apply {
                setPadding(0, 0, 0, dp(12))
            }
        )

        content.addView(
            cardBox(cardDark, borderBlue).apply {
                addView(txt("Serviço de Acessibilidade USSD", 15f, white, true))
                addView(
                    txt(
                        "A leitura das respostas da operadora ainda precisa de ser ligada e testada.",
                        12f,
                        lightBlue
                    )
                )
            }
        )

        content.addView(
            cardBox(cardDark, borderBlue).apply {
                addView(txt("Confirmação de transferências", 15f, white, true))
                addView(
                    txt(
                        "O aplicativo não deve marcar megas como entregues sem confirmação real da Vodacom.",
                        12f,
                        lightBlue
                    )
                )
            }
        )

        content.addView(
            cardBox(cardDark, borderBlue).apply {
                addView(txt("Meta diária", 15f, white, true))
                addView(txt("Meta actual: $metaGb GB", 13f, cyan, true))
            }
        )
    }

    // ============================================================
    // 10. BARRA DE NAVEGAÇÃO
    // ============================================================

    private fun renderizarBarraInferior() {
        navBar.removeAllViews()

        val abas = listOf(
            "Início" to "⌂",
            "Fila" to "☰",
            "Concluídos" to "✓",
            "SIMs" to "▤",
            "Config" to "⚙"
        )

        abas.forEach { (nome, icone) ->
            val selecionada = nome == abaAtual

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), dp(4))

                if (selecionada) {
                    background = roundRect(
                        Color.rgb(12, 46, 98),
                        cyan,
                        12
                    )
                }

                addView(
                    txt(
                        icone,
                        16f,
                        if (selecionada) cyan else lightBlue,
                        true
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                )

                addView(
                    txt(
                        nome,
                        10f,
                        if (selecionada) cyan else lightBlue,
                        selecionada
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                )

                setOnClickListener { navegarPara(nome) }
            }

            navBar.addView(
                item,
                LinearLayout.LayoutParams(0, -1, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
        }
    }

    // ============================================================
    // 11. LINHA DO HISTÓRICO
    // ============================================================

    private fun buildTransacaoRow(item: TransacaoConcluida): LinearLayout {
        val entregue = item.status == "Entregue"
        val incerto = item.status == "Incerto"

        val statusText = when {
            entregue -> "✓ Entregue"
            incerto -> "● Incerto"
            else -> "✕ Falhou"
        }

        val corEstado = when {
            entregue -> green
            incerto -> yellow
            else -> red
        }

        val fundoEstado = when {
            entregue -> greenBg
            incerto -> Color.rgb(55, 43, 12)
            else -> Color.rgb(65, 18, 26)
        }

        return cardBox(cardDark, borderBlue).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val icon = txt(
                when {
                    entregue -> "✓"
                    incerto -> "●"
                    else -> "✕"
                },
                14f,
                corEstado,
                true
            ).apply {
                gravity = Gravity.CENTER
                background = oval(fundoEstado, corEstado)
            }

            addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))

            val meio = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)

                addView(txt(item.numeroCliente, 15f, white, true))
                addView(
                    txt(
                        "Megas · ${item.hora} · ${item.simInfo}",
                        11f,
                        lightBlue
                    )
                )
            }

            addView(meio, LinearLayout.LayoutParams(0, -2, 1f))

            val direita = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END

                addView(txt("${item.megas} MB", 13f, cyan, true))
                addView(
                    pillBadge(
                        statusText,
                        corEstado,
                        fundoEstado,
                        corEstado
                    )
                )
            }

            addView(direita)
        }
    }

    // ============================================================
    // 12. CONTADORES DIÁRIOS DOS SIMs
    // ============================================================

    private fun carregarContadores() {
        sim1Usados = preferencias.getInt("sim1_usados", 0)
        sim2Usados = preferencias.getInt("sim2_usados", 0)
        simSelecionado = preferencias.getInt("sim_selecionado", 1)
            .coerceIn(1, 2)
    }

    private fun guardarContadores() {
        preferencias.edit()
            .putInt("sim1_usados", sim1Usados)
            .putInt("sim2_usados", sim2Usados)
            .putInt("sim_selecionado", simSelecionado)
            .putString("data_contadores", dataHoje())
            .apply()
    }

    private fun verificarNovoDia() {
        val dataGuardada = preferencias.getString("data_contadores", "")
        val hoje = dataHoje()

        if (dataGuardada != hoje) {
            sim1Usados = 0
            sim2Usados = 0
            guardarContadores()
        }
    }

    private fun dataHoje(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    // ============================================================
    // 13. COMPONENTE DO CÍRCULO DE PROGRESSO
    // ============================================================

    private inner class RingProgressView(
        context: Context,
        private val percentagem: Float,
        private val valorPrincipal: String,
        private val textoInferior: String
    ) : View(context) {

        private val paintFundo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(9).toFloat()
            color = Color.rgb(14, 52, 118)
        }

        private val paintProgresso = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(9).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = cyan
        }

        private val paintValor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = dp(19).toFloat()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        private val paintTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lightBlue
            textSize = dp(11).toFloat()
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val tamanho = width.coerceAtMost(height).toFloat()
            val padding = dp(12).toFloat()
            val rect = RectF(
                padding,
                padding,
                tamanho - padding,
                tamanho - padding
            )

            canvas.drawArc(rect, 0f, 360f, false, paintFundo)
            canvas.drawArc(
                rect,
                -90f,
                360f * percentagem,
                false,
                paintProgresso
            )

            canvas.drawText(
                valorPrincipal,
                tamanho / 2f,
                tamanho / 2f + dp(2),
                paintValor
            )

            canvas.drawText(
                textoInferior,
                tamanho / 2f,
                tamanho / 2f + dp(18),
                paintTexto
            )
        }
    }

        // ============================================================
    // 13B. DISPARO USSD E FILA
    // ============================================================

    private fun processarProximoDaFila() {
        if (filaPausada || pedidoEmCurso != null || filaEspera.isEmpty()) return

        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.CALL_PHONE,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS
                ),
                100
            )
            return
        }

        val usadosAtual = if (simSelecionado == 1) sim1Usados else sim2Usados
        if (usadosAtual >= limiteSim) {
            Toast.makeText(this, "Limite do SIM $simSelecionado atingido!", Toast.LENGTH_SHORT).show()
            return
        }

        val proximo = filaEspera.removeAt(0)
        pedidoEmCurso = proximo
        UssdAccessibilityService.iniciarFluxo(proximo.numeroCliente, proximo.megas)

        if (!dispararUssd("*162#", simSelecionado)) {
            UssdAccessibilityService.pararFluxo()
            pedidoEmCurso = null
            filaEspera.add(0, proximo)
        }
        navegarPara(abaAtual)
    }

    private fun dispararUssd(codigo: String, slotSim: Int): Boolean {
        return try {
            val uri = android.net.Uri.parse("tel:" + codigo.replace("#", "%23"))
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL, uri).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("com.android.phone.extra.slot", slotSim - 1)
                putExtra("simSlot", slotSim - 1)
            }

            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                val contas = tm?.callCapablePhoneAccounts
                if (contas != null && contas.size >= slotSim) {
                    intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, contas[slotSim - 1])
                }
            }

            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ============================================================
    // 14. HELPERS VISUAIS
    // ============================================================

    private fun dp(valor: Int): Int {
        return (valor * resources.displayMetrics.density).toInt()
    }

    private fun cardBox(corFundo: Int, corBorda: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
            background = roundRect(corFundo, corBorda, 14)
        }
    }

    private fun smallActionBtn(
        texto: String,
        cor: Int,
        acao: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = texto
            textSize = 11f
            setTextColor(cor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
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
                 
