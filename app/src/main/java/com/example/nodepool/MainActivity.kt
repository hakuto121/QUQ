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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("node_pool", MODE_PRIVATE) }
    private val sources = LinkedHashSet<String>()
    private val nodes = ArrayList<String>()
    private val scored = ArrayList<Pair<String, Long>>()

    private lateinit var sourceInput: EditText
    private lateinit var countInput: EditText
    private lateinit var timeoutInput: EditText
    private lateinit var status: TextView
    private lateinit var sourceContainer: LinearLayout

    private val defaultSources = listOf(
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs_base64.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/clash.yaml",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/vless.txt",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/light.txt",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/all.txt"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSources()
        buildUi()
    }

    private fun loadSources() {
        val saved = prefs.getStringSet("sources_pool", null)
        sources.clear()
        if (saved.isNullOrEmpty()) {
            sources.addAll(defaultSources)
        } else {
            sources.addAll(saved)
        }
    }

    private fun saveSources() {
        prefs.edit().putStringSet("sources_pool", sources).apply()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 32)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "征兵处 - 节点池管理器"
            textSize = 22f
            setPadding(0, 0, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "输入订阅源地址 (TXT / Base64 / YAML)："
            textSize = 13f
            setPadding(0, 0, 0, 4)
        })

        sourceInput = EditText(this).apply {
            hint = "https://raw.githubusercontent.com/..."
            minLines = 2
        }
        root.addView(sourceInput)

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }
        val addBtn = Button(this).apply {
            text = "添加订阅源"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearSourcesBtn = Button(this).apply {
            text = "清空所有源"
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

        val updateBtn = Button(this).apply { text = "提取节点" }
        root.addView(updateBtn)

        val speedBtn = Button(this).apply { text = "智能测速" }
        root.addView(speedBtn)

        timeoutInput = EditText(this).apply {
            hint = "测速超时（毫秒）"
            setText("2000")
            inputType = 2
        }
        root.addView(timeoutInput)

        countInput = EditText(this).apply {
            hint = "导出节点数量"
            setText("200")
            inputType = 2
        }
        root.addView(countInput)

        val exportTxt = Button(this).apply { text = "导出 TXT" }
        root.addView(exportTxt)

        val exportClash = Button(this).apply { text = "导出 YAML" }
        root.addView(exportClash)

        val clearBtn = Button(this).apply { text = "清空节点池" }
        root.addView(clearBtn)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
        }
        root.addView(status)

        setContentView(scroll)
        status.text = "就绪：已加载 ${sources.size} 个内置订阅源"

        addBtn.setOnClickListener {
            val u = sourceInput.text.toString().trim()
            if (u.startsWith("http://") || u.startsWith("https://")) {
                sources.add(u)
                saveSources()
                sourceInput.text.clear()
                refreshSourceList()
                status.text = "添加成功，当前共 ${sources.size} 个源"
            } else {
                status.text = "请输入 http:// 或 https:// 开头的链接"
            }
        }

        clearSourcesBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("确定清空所有订阅源吗？")
                .setPositiveButton("清空") { _, _ ->
                    sources.clear()
                    saveSources()
                    refreshSourceList()
                    status.text = "已清空所有订阅源"
                }
                .setNegativeButton("取消", null)
                .show()
        }

        updateBtn.setOnClickListener { updateAllAsync() }
        speedBtn.setOnClickListener { speedTest() }
        exportTxt.setOnClickListener { exportFile(false) }
        exportClash.setOnClickListener { exportFile(true) }
        clearBtn.setOnClickListener {
            nodes.clear()
            scored.clear()
            status.text = "当前节点池已清空"
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
                    status.text = "已删除 1 个源"
                }
            }
            row.addView(labelView)
            row.addView(delBtn)
            sourceContainer.addView(row)
        }
    }

    private fun updateAllAsync() {
        if (sources.isEmpty()) {
            status.text = "请先添加订阅源"
            return
        }
        status.text = "正在全量并发提取节点中……"

        val pool = Executors.newFixedThreadPool(sources.size.coerceIn(1, 24))
        val collectedNodes = java.util.Collections.synchronizedList(ArrayList<String>())
        val completedCount = AtomicInteger(0)
        val total = sources.size

        sources.forEach { url ->
            pool.submit {
                try {
                    val content = downloadWithTimeout(url)
                    val extracted = extractNodesFull(content)
                    if (extracted.isNotEmpty()) {
                        collectedNodes.addAll(extracted)
                    }
                } catch (_: Exception) {}

                val current = completedCount.incrementAndGet()
                runOnUiThread {
                    status.text = "提取进度: $current/$total 源，已捕获 ${collectedNodes.size} 个节点"
                }
            }
        }

        pool.shutdown()

        Executors.newSingleThreadExecutor().execute {
            try {
                pool.awaitTermination(60, TimeUnit.SECONDS)
            } catch (_: Exception) {}

            val distinctList = collectedNodes.distinct()
            nodes.clear()
            nodes.addAll(distinctList)
            scored.clear()

            runOnUiThread {
                status.text = "提取完成！共捕获 ${nodes.size} 个代理节点"
            }
        }
    }

    private fun downloadWithTimeout(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 40000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        conn.instanceFollowRedirects = true

        return conn.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(32768)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    sb.append(buffer, 0, charsRead)
                }
                sb.toString()
            }
        }
    }

    // 全量无截断节点扫描器
    private fun extractNodesFull(rawText: String): List<String> {
        val candidates = ArrayList<String>()
        val regex = Regex("(?i)(?:vmess|vless|trojan|ss|ssr|hysteria2|hysteria|hy2|tuic)://[^\\s\"'<>]+")

        fun scan(text: String) {
            regex.findAll(text).forEach { match ->
                val node = match.value.trimEnd(',', ';', ']', ')', '}', '\r', '\n')
                if (node.length > 15) {
                    candidates.add(node)
                }
            }
        }

        fun decodeSafe(str: String): String? {
            val clean = str.trim().replace("\\s+".toRegex(), "")
            if (clean.length < 8) return null
            val padLen = (4 - clean.length % 4) % 4
            val padded = clean + "=".repeat(padLen)
            return try {
                String(Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) { null }
        }

        // 1. 全文直接扫描
        scan(rawText)

        // 2. 整段 Base64 尝试
        decodeSafe(rawText)?.let { scan(it) }

        // 3. 逐行深度扫描（针对多行 base64 混合源）
        rawText.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.length >= 16) {
                if (l.contains("://")) {
                    scan(l)
                } else {
                    decodeSafe(l)?.let { scan(it) }
                }
            }
        }

        // 4. 解析 YAML 配置文件
        if (rawText.contains("proxies:", ignoreCase = true) || rawText.contains("- name:", ignoreCase = true)) {
            val yamlNodes = parseClashYamlToNodes(rawText)
            candidates.addAll(yamlNodes)
        }

        return candidates
    }

    private fun parseClashYamlToNodes(yamlText: String): List<String> {
        val list = ArrayList<String>()
        val lines = yamlText.lines()
        var currentMap: MutableMap<String, String>? = null

        fun flushCurrent() {
            currentMap?.let { map ->
                try {
                    val type = map["type"]?.lowercase() ?: ""
                    val server = map["server"] ?: ""
                    val port = map["port"] ?: "443"
                    val name = map["name"] ?: "Node"
                    val encodedName = URLEncoder.encode(name, "UTF-8")

                    when (type) {
                        "vmess" -> {
                            val uuid = map["uuid"] ?: ""
                            if (server.isNotBlank() && uuid.isNotBlank()) {
                                val obj = JSONObject().apply {
                                    put("v", "2")
                                    put("ps", name)
                                    put("add", server)
                                    put("port", port)
                                    put("id", uuid)
                                    put("aid", map["alterId"] ?: "0")
                                    put("scy", map["cipher"]?.takeIf { it != "null" && it.isNotBlank() } ?: "auto")
                                    put("net", map["network"] ?: "tcp")
                                    put("tls", if (map["tls"] == "true") "tls" else "")
                                    put("sni", map["servername"] ?: map["sni"] ?: "")
                                    put("host", map["host"] ?: "")
                                    put("path", map["path"] ?: "")
                                }
                                val b64 = Base64.encodeToString(obj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                list.add("vmess://$b64")
                            }
                        }
                        "vless" -> {
                            val uuid = map["uuid"] ?: ""
                            if (server.isNotBlank() && uuid.isNotBlank()) {
                                val tls = if (map["tls"] == "true") "tls" else "none"
                                val net = map["network"] ?: "tcp"
                                val sni = map["servername"] ?: map["sni"] ?: ""
                                val uri = "vless://$uuid@$server:$port?security=$tls&type=$net&sni=$sni#$encodedName"
                                list.add(uri)
                            }
                        }
                        "trojan" -> {
                            val password = map["password"] ?: ""
                            if (server.isNotBlank() && password.isNotBlank()) {
                                val sni = map["sni"] ?: map["servername"] ?: ""
                                val uri = "trojan://$password@$server:$port?sni=$sni#$encodedName"
                                list.add(uri)
                            }
                        }
                        "ss" -> {
                            val cipher = map["cipher"]?.takeIf { it != "null" && it.isNotBlank() } ?: ""
                            val password = map["password"] ?: ""
                            if (server.isNotBlank() && cipher.isNotBlank() && password.isNotBlank()) {
                                val userPart = Base64.encodeToString("$cipher:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                list.add("ss://$userPart@$server:$port#$encodedName")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            currentMap = null
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("- name:") || line.startsWith("- {name:")) {
                flushCurrent()
                currentMap = HashMap()
                if (line.contains("{") && line.contains("}")) {
                    val content = line.substringAfter("{").substringBeforeLast("}")
                    content.split(",").forEach { pair ->
                        val kv = pair.split(":", limit = 2)
                        if (kv.size == 2) {
                            val k = kv[0].trim().replace("\"", "").replace("'", "")
                            val v = kv[1].trim().replace("\"", "").replace("'", "")
                            currentMap?.put(k, v)
                        }
                    }
                    flushCurrent()
                } else {
                    val n = line.substringAfter("- name:").trim().replace("\"", "").replace("'", "")
                    currentMap?.put("name", n)
                }
            } else if (currentMap != null && line.contains(":")) {
                val kv = line.split(":", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().replace("\"", "").replace("'", "")
                    val v = kv[1].trim().replace("\"", "").replace("'", "")
                    currentMap?.put(k, v)
                }
            }
        }
        flushCurrent()
        return list
    }

    private fun speedTest() {
        if (nodes.isEmpty()) {
            status.text = "提示：请先点击“提取节点”"
            return
        }
        val timeout = timeoutInput.text.toString().toIntOrNull()?.coerceIn(500, 10000) ?: 2000
        val snapshot = nodes.toList()
        status.text = "正在执行快速测速：0/${snapshot.size}"

        val cpuPool = Executors.newFixedThreadPool(48)
        val results = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
        val done = AtomicInteger(0)

        snapshot.forEach { node ->
            cpuPool.submit {
                val hp = parseHostPort(node)
                if (hp != null) {
                    val start = System.currentTimeMillis()
                    try {
                        Socket().use { socket ->
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(hp.first, hp.second), timeout)
                        }
                        val latency = System.currentTimeMillis() - start
                        results.add(node to latency)
                    } catch (_: Exception) {}
                }
                val d = done.incrementAndGet()
                if (d % 100 == 0 || d == snapshot.size) {
                    runOnUiThread {
                        status.text = "测速中: $d/${snapshot.size}，可用节点: ${results.size}"
                    }
                }
            }
        }
        cpuPool.shutdown()

        Executors.newSingleThreadExecutor().execute {
            try {
                cpuPool.awaitTermination(90, TimeUnit.SECONDS)
            } catch (_: Exception) {}

            results.sortBy { it.second }
            scored.clear()
            scored.addAll(results)
            nodes.clear()
            nodes.addAll(results.map { it.first })

            runOnUiThread {
                status.text = "测速完成！精选出 ${results.size} 个可用节点（已按延迟排序）"
            }
        }
    }

    private fun parseHostPort(node: String): Pair<String, Int>? {
        return try {
            val u = node.trim()
            if (u.startsWith("vmess://", ignoreCase = true)) {
                val b = u.substring(8)
                val padLen = (4 - b.length % 4) % 4
                val decoded = Base64.decode(b + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP).toString(Charsets.UTF_8)
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
            status.text = "提示：没有可导出的节点"
            return
        }
        val n = countInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 200
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
                        val b = node.substring(8)
                        val padLen = (4 - b.length % 4) % 4
                        val json = String(Base64.decode(b + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                        val obj = JSONObject(json)
                        val rawName = obj.optString("ps").ifBlank { "VMess" }
                        val name = cleanName(rawName, idx)
                        val server = obj.optString("add")
                        val port = obj.optInt("port", 443)
                        val uuid = obj.optString("id")
                        val alterId = obj.optInt("aid", 0)

                        var cipher = obj.optString("scy", "auto")
                        if (cipher.isBlank() || cipher.equals("null", ignoreCase = true)) {
                            cipher = "auto"
                        }

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
                        val isReality = queryMap["security"] == "reality"
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

                            if (isReality || queryMap["security"] == "tls") {
                                sb.append("    tls: true\n")
                                if (sni.isNotBlank()) sb.append("    servername: $sni\n")
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
                                    val padLen = (4 - userInfo.length % 4) % 4
                                    String(Base64.decode(userInfo + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                                } catch (_: Exception) { "" }
                            }
                            server = uri.host ?: ""
                            port = if (uri.port > 0) uri.port else 8388
                        } else {
                            val rawPart = node.substring(5).substringBefore("#")
                            val userBase = rawPart.substringBefore("@")
                            val padLen = (4 - userBase.length % 4) % 4
                            val decoded = try {
                                String(Base64.decode(userBase + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                            } catch (_: Exception) { "" }
                            userInfo = decoded
                            val hostPort = rawPart.substringAfter("@", "")
                            server = hostPort.substringBefore(":")
                            port = hostPort.substringAfter(":", "8388").toIntOrNull() ?: 8388
                        }

                        val cipher = userInfo.substringBefore(":", "").trim()
                        val password = userInfo.substringAfter(":", "").trim()

                        if (server.isNotBlank() && cipher.isNotBlank() && password.isNotBlank() && !cipher.equals("null", ignoreCase = true)) {
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
                status.text = "文件导出成功！"
            } catch (e: Exception) {
                status.text = "导出失败：${e.message}"
            }
        }
    }
}
