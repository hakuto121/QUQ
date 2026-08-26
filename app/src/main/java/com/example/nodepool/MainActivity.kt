package com.example.nodepool

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("node_pool", MODE_PRIVATE) }
    private val executor = Executors.newFixedThreadPool(24)
    private val sources = LinkedHashSet<String>()
    private val nodes = LinkedHashSet<String>()
    private val scored = ArrayList<Pair<String, Long>>()
    
    private lateinit var sourceInput: EditText
    private lateinit var countInput: EditText
    private lateinit var timeoutInput: EditText
    private lateinit var status: TextView
    private lateinit var sourceContainer: LinearLayout
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var rbEdgeTunnel: RadioButton
    private lateinit var rbGeneral: RadioButton

    // 内置国内优质 Anycast / CDN 优选地址
    private val cfFastIps = listOf(
        "104.16.160.1",
        "104.17.160.1",
        "172.67.160.1",
        "icook.tw",
        "www.visa.com.tw",
        "cf.090227.xyz",
        "time.is",
        "www.digitalocean.com"
    )

    private val defaultsGeneral = listOf(
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs_base64.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/vless.txt",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/light.txt",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/all.txt"
    )

    private val defaultsEdgeTunnel = listOf(
        "https://bestcf.pages.dev/vps789/top100.txt"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSources(isEdgeMode = true)
        buildUi()
    }

    private fun loadSources(isEdgeMode: Boolean) {
        val key = if (isEdgeMode) "sources_edge" else "sources_general"
        val saved = prefs.getStringSet(key, null)
        sources.clear()
        if (saved.isNullOrEmpty()) {
            sources.addAll(if (isEdgeMode) defaultsEdgeTunnel else defaultsGeneral)
        } else {
            sources.addAll(saved)
        }
    }

    private fun saveSources() {
        val isEdgeMode = rbEdgeTunnel.isChecked
        val key = if (isEdgeMode) "sources_edge" else "sources_general"
        prefs.edit().putStringSet(key, sources).apply()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "节点池管理器 (Edge & 通用双模版)"
            textSize = 24f
            setPadding(0, 0, 0, 16)
        })

        // 模式选择单选组
        root.addView(TextView(this).apply {
            text = "选择工作模式："
            textSize = 14f
            setPadding(0, 0, 0, 6)
        })

        modeRadioGroup = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        rbEdgeTunnel = RadioButton(this).apply {
            text = "edgetunnel / 优选裸连模式"
            isChecked = true
        }
        rbGeneral = RadioButton(this).apply {
            text = "全网聚合订阅模式"
        }

        modeRadioGroup.addView(rbEdgeTunnel)
        modeRadioGroup.addView(rbGeneral)
        root.addView(modeRadioGroup)

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isEdge = checkedId == rbEdgeTunnel.id
            loadSources(isEdge)
            refreshSourceList()
            nodes.clear()
            scored.clear()
            status.text = if (isEdge) "已切换至【edgetunnel / 优选裸连模式】，支持国内免 VPN 访问" else "已切换至【全网聚合订阅模式】"
        }

        root.addView(TextView(this).apply {
            text = "输入订阅源地址（TXT / 优选直链 / GitHub Raw）："
            textSize = 13f
        })

        sourceInput = EditText(this).apply {
            hint = "https://……"
            minLines = 2
        }
        root.addView(sourceInput)

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }
        val addBtn = Button(this).apply {
            text = "＋ 添加订阅源"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearSourcesBtn = Button(this).apply {
            text = "清空当前模式源"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow1.addView(addBtn)
        btnRow1.addView(clearSourcesBtn)
        root.addView(btnRow1)

        sourceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(sourceContainer)
        refreshSourceList()

        val updateBtn = Button(this).apply { text = "① 更新全部订阅" }
        root.addView(updateBtn)

        val speedBtn = Button(this).apply { text = "② 智能深度测速与排序" }
        root.addView(speedBtn)

        timeoutInput = EditText(this).apply {
            hint = "测速超时（毫秒）"
            setText("2500")
            inputType = 2
        }
        root.addView(timeoutInput)

        countInput = EditText(this).apply {
            hint = "导出前 N 个可用节点"
            setText("100")
            inputType = 2
        }
        root.addView(countInput)

        val exportTxt = Button(this).apply { text = "③ 导出 TXT (支持 Vertex 控制台)" }
        root.addView(exportTxt)

        val exportClash = Button(this).apply { text = "④ 导出 CLASH YAML (防报错合规)" }
        root.addView(exportClash)

        val clearBtn = Button(this).apply { text = "清空当前已测节点" }
        root.addView(clearBtn)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }
        root.addView(status)

        setContentView(scroll)
        status.text = "就绪：当前模式共有 ${sources.size} 个订阅源"

        addBtn.setOnClickListener {
            val u = sourceInput.text.toString().trim()
            if (u.startsWith("http://") || u.startsWith("https://")) {
                sources.add(u)
                saveSources()
                sourceInput.text.clear()
                refreshSourceList()
                status.text = "已添加，当前共 ${sources.size} 个源"
            } else {
                status.text = "请输入 http:// 或 https:// 开头的链接"
            }
        }

        clearSourcesBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("确定清空当前模式下的所有订阅源吗？")
                .setPositiveButton("清空") { _, _ ->
                    sources.clear()
                    saveSources()
                    refreshSourceList()
                    status.text = "已清空订阅源"
                }
                .setNegativeButton("取消", null)
                .show()
        }

        updateBtn.setOnClickListener { updateAll() }
        speedBtn.setOnClickListener { speedTest() }
        exportTxt.setOnClickListener { exportFile(false) }
        exportClash.setOnClickListener { exportFile(true) }
        clearBtn.setOnClickListener {
            nodes.clear(); scored.clear()
            status.text = "已清空当前节点"
        }
    }

    private fun refreshSourceList() {
        sourceContainer.removeAllViews()
        val title = TextView(this).apply {
            text = "当前订阅源列表（共 ${sources.size} 个）："
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        sourceContainer.addView(title)

        sources.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            val labelView = TextView(this).apply {
                text = "${i + 1}. $s"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delBtn = Button(this).apply {
                text = "删除"
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    sources.remove(s)
                    saveSources()
                    refreshSourceList()
                    status.text = "已删除 1 个订阅源，剩余 ${sources.size} 个"
                }
            }
            row.addView(labelView)
            row.addView(delBtn)
            sourceContainer.addView(row)
        }
    }

    private fun updateAll() {
        if (sources.isEmpty()) {
            status.text = "请先添加订阅源"
            return
        }
        val isEdge = rbEdgeTunnel.isChecked
        status.text = if (isEdge) "正在拉取并构建 edgetunnel 优选节点……" else "正在下载全网订阅并去重……"
        
        executor.execute {
            val result = LinkedHashSet<String>()
            var ok = 0
            for (url in sources) {
                try {
                    val content = download(url)
                    val extracted = extractNodes(content)
                    if (extracted.isNotEmpty()) ok++
                    
                    extracted.forEach { raw ->
                        if (isEdge) {
                            // edgetunnel 模式：强制替换并确保优选 CDN 第一跳
                            result.add(speedUpNode(raw))
                        } else {
                            result.add(raw)
                        }
                    }
                } catch (_: Exception) {}
                runOnUiThread {
                    status.text = "正在更新：已处理 ${ok}/${sources.size} 个源，获取 ${result.size} 个节点"
                }
            }
            nodes.clear()
            nodes.addAll(result)
            scored.clear()
            runOnUiThread {
                status.text = "更新完成：共获取 ${nodes.size} 个节点"
            }
        }
    }

    private fun speedUpNode(node: String): String {
        try {
            val u = node.trim()
            if (u.startsWith("vless://", ignoreCase = true)) {
                val uri = URI(u)
                val queryMap = parseQuery(uri.rawQuery ?: "")
                val oldHost = uri.host ?: ""
                val fastIp = cfFastIps.random()
                val hostParam = queryMap["host"]?.ifBlank { oldHost } ?: oldHost
                val sniParam = queryMap["sni"]?.ifBlank { oldHost } ?: oldHost
                val newQuery = (queryMap + mapOf("host" to hostParam, "sni" to sniParam, "type" to (queryMap["type"] ?: "ws")))
                    .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                val remark = uri.rawFragment ?: "CF_Direct"
                return "vless://${uri.userInfo}@$fastIp:${if (uri.port > 0) uri.port else 443}?$newQuery#$remark"
            } else if (u.startsWith("vmess://", ignoreCase = true)) {
                val json = String(Base64.decode(u.substring(8), Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                val obj = JSONObject(json)
                val oldHost = obj.optString("add")
                obj.put("host", obj.optString("host").ifBlank { oldHost })
                obj.put("sni", obj.optString("sni").ifBlank { oldHost })
                obj.put("add", cfFastIps.random())
                obj.put("net", "ws")
                val newB64 = Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                return "vmess://$newB64"
            }
        } catch (_: Exception) {}
        return node
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "NodePoolManager/2.0 Android")
        conn.instanceFollowRedirects = true
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun extractNodes(text: String): List<String> {
        val regex = Regex("(?i)(?:vmess|vless|trojan|ssr|ss|hysteria2|hysteria|hy2|tuic|socks5?|http)://[^\\s\"'<>]+")
        val candidates = LinkedHashSet<String>()

        fun scan(s: String) {
            regex.findAll(s).forEach { candidates.add(it.value.trimEnd(',', ';', ']', ')')) }
        }

        scan(text)

        val compact = text.replace("\\s+".toRegex(), "")
        if (candidates.isEmpty() && compact.length >= 16) {
            try {
                val decoded = Base64.decode(
                    compact,
                    Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE
                ).toString(Charsets.UTF_8)
                scan(decoded)
            } catch (_: Exception) {}
        }

        text.lineSequence().forEach { line ->
            val x = line.trim()
            if (x.length >= 16 && !x.contains("://")) {
                try {
                    val decoded = Base64.decode(
                        x,
                        Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE
                    ).toString(Charsets.UTF_8)
                    scan(decoded)
                } catch (_: Exception) {}
            }
        }

        return candidates.toList()
    }

    private fun speedTest() {
        if (nodes.isEmpty()) {
            status.text = "请先点击“更新全部订阅”"
            return
        }
        val timeout = timeoutInput.text.toString().toIntOrNull()?.coerceIn(500, 15000) ?: 2500
        val isEdge = rbEdgeTunnel.isChecked
        val snapshot = nodes.toList()
        status.text = if (isEdge) "正在执行国内直连测速与 Google 端点测试：0/${snapshot.size}" else "正在进行多线程极速测速：0/${snapshot.size}"

        executor.execute {
            val results = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
            val done = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(24)
            
            snapshot.forEach { node ->
                pool.submit {
                    val hp = parseHostPort(node)
                    if (hp != null) {
                        val start = System.currentTimeMillis()
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(hp.first, hp.second), timeout)
                            }
                            
                            // 针对裸连模式补充轻量请求校验
                            if (isEdge) {
                                val conn = URL("https://aiplatform.googleapis.com/generate_204").openConnection() as HttpURLConnection
                                conn.connectTimeout = timeout
                                conn.readTimeout = timeout
                                conn.responseCode
                            }

                            val latency = System.currentTimeMillis() - start
                            results.add(node to latency)
                        } catch (_: Exception) {}
                    }
                    val d = done.incrementAndGet()
                    if (d % 10 == 0 || d == snapshot.size) {
                        runOnUiThread { status.text = "测速中：$d/${snapshot.size}，可用节点 ${results.size}" }
                    }
                }
            }
            pool.shutdown()
            while (!pool.isTerminated) Thread.sleep(50)
            results.sortBy { it.second }
            scored.clear(); scored.addAll(results)
            nodes.clear(); nodes.addAll(results.map { it.first })
            runOnUiThread {
                status.text = "测速完成：精选出 ${results.size} 个高可用节点（已按延迟排好序）"
            }
        }
    }

    private fun parseHostPort(node: String): Pair<String, Int>? {
        return try {
            val u = node.trim()
            if (u.startsWith("vmess://", ignoreCase = true)) {
                val b = u.substring(8)
                val decoded = Base64.decode(b, Base64.DEFAULT or Base64.NO_WRAP).toString(Charsets.UTF_8)
                val obj = JSONObject(decoded)
                val host = obj.optString("add").ifBlank { return null }
                val port = obj.optInt("port", 443)
                return host to port
            }
            val uri = URI(u)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            host to port
        } catch (_: Exception) { null }
    }

    private fun exportFile(clash: Boolean) {
        if (nodes.isEmpty()) {
            status.text = "没有可导出的节点，请先更新/测速"
            return
        }
        val n = countInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 100
        val selected = nodes.take(n)

        val body = if (!clash) {
            selected.joinToString("\n") + "\n"
        } else {
            generateClashConfig(selected)
        }

        val ext = if (clash) "yaml" else "txt"
        val name = "nodepool_export_${selected.size}.$ext"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = if (clash) "text/yaml" else "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, if (clash) 9002 else 9001)
        pendingExport = body
    }

    private fun generateClashConfig(selected: List<String>): String {
        val proxyYamlList = ArrayList<String>()
        val proxyNames = ArrayList<String>()

        selected.forEachIndexed { index, rawNode ->
            val idx = index + 1
            val node = rawNode.trim()
            try {
                when {
                    node.startsWith("vmess://", ignoreCase = true) -> {
                        val json = String(Base64.decode(node.substring(8), Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                        val obj = JSONObject(json)
                        val rawName = obj.optString("ps").ifBlank { "VMess" }
                        val name = cleanName(rawName, idx)
                        val server = obj.optString("add")
                        val port = obj.optInt("port", 443)
                        val uuid = obj.optString("id")
                        val alterId = obj.optInt("aid", 0)
                        val cipher = obj.optString("scy", "auto").ifBlank { "auto" }
                        val net = obj.optString("net", "tcp")
                        val tls = obj.optString("tls") == "tls"
                        val sni = obj.optString("sni", "")
                        val path = obj.optString("path", "")
                        val host = obj.optString("host", "")

                        if (server.isNotBlank() && uuid.isNotBlank()) {
                            val sb = StringBuilder()
                            sb.append("  - name: \"$name\"\n")
                            sb.append("    type: vmess\n")
                            sb.append("    server: $server\n")
                            sb.append("    port: $port\n")
                            sb.append("    uuid: $uuid\n")
                            sb.append("    alterId: $alterId\n")
                            sb.append("    cipher: $cipher\n")
                            sb.append("    udp: true\n")
                            if (tls) {
                                sb.append("    tls: true\n")
                                if (sni.isNotBlank()) sb.append("    servername: $sni\n")
                            }
                            if (net == "ws") {
                                sb.append("    network: ws\n")
                                sb.append("    ws-opts:\n")
                                if (path.isNotBlank()) sb.append("      path: \"$path\"\n")
                                if (host.isNotBlank()) sb.append("      headers:\n        Host: $host\n")
                            }
                            proxyYamlList.add(sb.toString().trimEnd())
                            proxyNames.add(name)
                        }
                    }
                    node.startsWith("vless://", ignoreCase = true) -> {
                        val uri = URI(node)
                        val uuid = uri.userInfo ?: ""
                        val server = uri.host ?: ""
                        val port = if (uri.port > 0) uri.port else 443
                        val queryMap = parseQuery(uri.rawQuery ?: "")
                        val rawRemark = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "VLESS"
                        val name = cleanName(rawRemark, idx)
                        val tls = queryMap["security"] == "tls" || queryMap["security"] == "reality"
                        val flow = queryMap["flow"] ?: ""
                        val sni = queryMap["sni"] ?: ""
                        val net = queryMap["type"] ?: "tcp"

                        if (server.isNotBlank() && uuid.isNotBlank()) {
                            val sb = StringBuilder()
                            sb.append("  - name: \"$name\"\n")
                            sb.append("    type: vless\n")
                            sb.append("    server: $server\n")
                            sb.append("    port: $port\n")
                            sb.append("    uuid: $uuid\n")
                            sb.append("    udp: true\n")
                            if (flow.isNotBlank()) sb.append("    flow: $flow\n")
                            if (tls) {
                                sb.append("    tls: true\n")
                                if (sni.isNotBlank()) sb.append("    servername: $sni\n")
                                if (queryMap["security"] == "reality") {
                                    val pbk = queryMap["pbk"]
                                    val sid = queryMap["sid"]
                                    // 严格拦截残缺 Reality，防止 invalid short ID 崩溃
                                    if (!pbk.isNullOrBlank() && !sid.isNullOrBlank()) {
                                        sb.append("    reality-opts:\n")
                                        sb.append("      public-key: $pbk\n")
                                        sb.append("      short-id: $sid\n")
                                    }
                                }
                            }
                            if (net == "ws") {
                                sb.append("    network: ws\n")
                                sb.append("    ws-opts:\n")
                                queryMap["path"]?.let { sb.append("      path: \"$it\"\n") }
                                queryMap["host"]?.let { sb.append("      headers:\n        Host: $it\n") }
                            }
                            proxyYamlList.add(sb.toString().trimEnd())
                            proxyNames.add(name)
                        }
                    }
                    node.startsWith("trojan://", ignoreCase = true) -> {
                        val uri = URI(node)
                        val password = uri.userInfo ?: ""
                        val server = uri.host ?: ""
                        val port = if (uri.port > 0) uri.port else 443
                        val queryMap = parseQuery(uri.rawQuery ?: "")
                        val rawRemark = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "Trojan"
                        val name = cleanName(rawRemark, idx)
                        val sni = queryMap["sni"] ?: ""

                        if (server.isNotBlank() && password.isNotBlank()) {
                            val sb = StringBuilder()
                            sb.append("  - name: \"$name\"\n")
                            sb.append("    type: trojan\n")
                            sb.append("    server: $server\n")
                            sb.append("    port: $port\n")
                            sb.append("    password: $password\n")
                            sb.append("    udp: true\n")
                            if (sni.isNotBlank()) sb.append("    sni: $sni\n")
                            proxyYamlList.add(sb.toString().trimEnd())
                            proxyNames.add(name)
                        }
                    }
                    node.startsWith("ss://", ignoreCase = true) -> {
                        val uri = URI(node)
                        val rawRemark = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "SS"
                        val name = cleanName(rawRemark, idx)
                        var userInfo = uri.userInfo ?: ""
                        val server: String
                        val port: Int

                        if (userInfo.isNotBlank()) {
                            if (!userInfo.contains(":")) {
                                userInfo = try {
                                    String(Base64.decode(userInfo, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                                } catch (_: Exception) { "" }
                            }
                            server = uri.host ?: ""
                            port = if (uri.port > 0) uri.port else 8388
                        } else {
                            val rawPart = node.substring(5).substringBefore("#")
                            val decoded = try {
                                String(Base64.decode(rawPart.substringBefore("@"), Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                            } catch (_: Exception) { "" }
                            userInfo = decoded
                            val hostPort = rawPart.substringAfter("@", "")
                            server = hostPort.substringBefore(":")
                            port = hostPort.substringAfter(":", "8388").toIntOrNull() ?: 8388
                        }

                        val cipher = userInfo.substringBefore(":", "").trim()
                        val password = userInfo.substringAfter(":", "").trim()

                        // 关键拦截：必须确保 cipher 和 password 非空，彻底根治 key 'cipher' missing 报错
                        if (server.isNotBlank() && cipher.isNotBlank() && password.isNotBlank()) {
                            val sb = StringBuilder()
                            sb.append("  - name: \"$name\"\n")
                            sb.append("    type: ss\n")
                            sb.append("    server: $server\n")
                            sb.append("    port: $port\n")
                            sb.append("    cipher: $cipher\n")
                            sb.append("    password: $password\n")
                            sb.append("    udp: true\n")
                            proxyYamlList.add(sb.toString().trimEnd())
                            proxyNames.add(name)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return buildString {
            append("port: 7890\n")
            append("socks-port: 7891\n")
            append("allow-lan: false\n")
            append("mode: rule\n")
            append("log-level: info\n")
            append("external-controller: 127.0.0.1:9090\n\n")

            append("proxies:\n")
            proxyYamlList.forEach { append(it).append("\n") }
            append("\n")

            append("proxy-groups:\n")
            append("  - name: PROXY\n")
            append("    type: select\n")
            append("    proxies:\n")
            append("      - AUTO\n")
            proxyNames.forEach { append("      - \"$it\"\n") }

            append("  - name: AUTO\n")
            append("    type: url-test\n")
            append("    url: https://aiplatform.googleapis.com/generate_204\n")
            append("    interval: 300\n")
            append("    tolerance: 50\n")
            append("    proxies:\n")
            proxyNames.forEach { append("      - \"$it\"\n") }

            append("\nrules:\n")
            append("  - MATCH,PROXY\n")
        }
    }

    private fun cleanName(raw: String?, index: Int): String {
        val s = (raw ?: "").replace(Regex("[\\r\\n\\t\"]"), " ")
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
            .replace(Regex("[:'#\\[\\]{}|>]"), " ")
            .trim()
        val base = if (s.isBlank()) "Node" else s
        return String.format("%03d-%s", index, base)
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isBlank()) return map
        query.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.isNotEmpty()) {
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                map[key] = value
            }
        }
        return map
    }

    private var pendingExport: String? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openOutputStream(data.data!!).use { out ->
                    out?.write((pendingExport ?: "").toByteArray(Charsets.UTF_8))
                }
                status.text = "导出完成"
            } catch (e: Exception) {
                status.text = "导出失败：${e.message}"
            }
        }
    }
}
