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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("node_pool", MODE_PRIVATE) }
    private val sources = LinkedHashSet<String>()
    
    // 原始全量池与测速结果池
    private val rawNodes = Collections.synchronizedList(ArrayList<String>())
    private val validNodes = Collections.synchronizedList(ArrayList<Pair<String, Long>>())

    private lateinit var sourceInput: EditText
    private lateinit var countInput: EditText
    private lateinit var timeoutInput: EditText
    private lateinit var status: TextView
    private lateinit var sourceContainer: LinearLayout

    // 扩充高质量海量抓取源
    private val defaultSources = listOf(
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs_base64.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/clash.yaml",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/vless.txt",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/light.txt",
        "https://raw.githubusercontent.com/ninjastrikers/Nexus-nodes/main/configs/all.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/vmess.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/trojan.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/ss.txt",
        "https://raw.githubusercontent.com/mft-1/v2ray-share/master/vless.txt",
        "https://raw.githubusercontent.com/mft-1/v2ray-share/master/trojan.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/Leon406/SubCrawler/main/sub/share/all"
    )

    private var pendingExport: String? = null

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
            text = "征兵处 - 节点池中枢 (三大独立流程)"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })

        sourceInput = EditText(this).apply {
            hint = "输入订阅源地址 (http/https)..."
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
            text = "重置默认源"
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

        // 核心三大功能按钮
        val step1Btn = Button(this).apply { text = "【步骤 1】全量抓取节点 (暴力全收)" }
        val step2Btn = Button(this).apply { text = "【步骤 2】深度筛选可用节点 (协议探测)" }
        val step3Btn = Button(this).apply { text = "【步骤 3】按延迟升序排序" }

        root.addView(step1Btn)
        root.addView(step2Btn)
        root.addView(step3Btn)

        val configRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        timeoutInput = EditText(this).apply {
            hint = "超时(ms)"
            setText("2000")
            inputType = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        countInput = EditText(this).apply {
            hint = "导出上限"
            setText("5000")
            inputType = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        configRow.addView(timeoutInput)
        configRow.addView(countInput)
        root.addView(configRow)

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        val exportTxt = Button(this).apply {
            text = "导出 URI (TXT)"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val exportClash = Button(this).apply {
            text = "导出 Clash (YAML)"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow2.addView(exportTxt)
        btnRow2.addView(exportClash)
        root.addView(btnRow2)

        val clearBtn = Button(this).apply { text = "清空所有已抓取/筛选数据" }
        root.addView(clearBtn)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, 20, 0, 0)
        }
        root.addView(status)

        setContentView(scroll)
        updateStatusDisplay()

        addBtn.setOnClickListener {
            val u = sourceInput.text.toString().trim()
            if (u.startsWith("http://", true) || u.startsWith("https://", true)) {
                sources.add(u)
                saveSources()
                sourceInput.text.clear()
                refreshSourceList()
            }
        }

        clearSourcesBtn.setOnClickListener {
            sources.clear()
            sources.addAll(defaultSources)
            saveSources()
            refreshSourceList()
        }

        step1Btn.setOnClickListener { actionScrapeAll() }
        step2Btn.setOnClickListener { actionFilterAlive() }
        step3Btn.setOnClickListener { actionSortNodes() }
        exportTxt.setOnClickListener { exportFile(false) }
        exportClash.setOnClickListener { exportFile(true) }
        clearBtn.setOnClickListener {
            rawNodes.clear()
            validNodes.clear()
            updateStatusDisplay()
        }
    }

    private fun updateStatusDisplay() {
        status.text = "状态：已抓取原始节点 ${rawNodes.size} 个 | 筛选可用节点 ${validNodes.size} 个"
    }

    private fun refreshSourceList() {
        sourceContainer.removeAllViews()
        val title = TextView(this).apply {
            text = "已启用订阅源（${sources.size} 个）："
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        sourceContainer.addView(title)

        sources.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 2, 0, 2)
            }
            val labelView = TextView(this).apply {
                text = "${i + 1}. $s"
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delBtn = Button(this).apply {
                text = "删"
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    sources.remove(s)
                    saveSources()
                    refreshSourceList()
                }
            }
            row.addView(labelView)
            row.addView(delBtn)
            sourceContainer.addView(row)
        }
    }

    // ==================== 步骤 1：全量抓取 ====================

    private fun actionScrapeAll() {
        if (sources.isEmpty()) {
            status.text = "请先添加订阅源"
            return
        }
        status.text = "正在启动多线程全量暴力抓取..."

        val pool = Executors.newFixedThreadPool(sources.size.coerceIn(8, 32))
        val collected = Collections.synchronizedList(ArrayList<String>())
        val completedCount = AtomicInteger(0)
        val total = sources.size

        sources.forEach { url ->
            pool.submit {
                try {
                    val content = downloadWithTimeout(url)
                    val extracted = extractNodesFull(content)
                    if (extracted.isNotEmpty()) {
                        collected.addAll(extracted)
                    }
                } catch (_: Exception) {}

                val c = completedCount.incrementAndGet()
                runOnUiThread {
                    status.text = "抓取进度: $c/$total 源完成，已提取 ${collected.size} 个原始节点..."
                }
            }
        }

        pool.shutdown()
        Executors.newSingleThreadExecutor().execute {
            try { pool.awaitTermination(120, TimeUnit.SECONDS) } catch (_: Exception) {}
            rawNodes.clear()
            rawNodes.addAll(collected)
            runOnUiThread {
                updateStatusDisplay()
                status.append("\n全量抓取完毕！共获取 ${rawNodes.size} 个节点。请继续点击【步骤 2】进行可用性筛选。")
            }
        }
    }

    // ==================== 步骤 2：深度可用性筛选 ====================

    private fun actionFilterAlive() {
        if (rawNodes.isEmpty()) {
            status.text = "原始节点池为空，请先执行【步骤 1】全量抓取"
            return
        }
        val timeout = timeoutInput.text.toString().toIntOrNull()?.coerceIn(500, 10000) ?: 2000
        val snapshot = rawNodes.toList()
        validNodes.clear()

        val cpuPool = Executors.newFixedThreadPool(80)
        val done = AtomicInteger(0)

        snapshot.forEach { node ->
            cpuPool.submit {
                val target = parseNodeMeta(node)
                if (target != null) {
                    val latency = testConnectionStrict(target, timeout)
                    if (latency > 0) {
                        validNodes.add(node to latency)
                    }
                }
                val d = done.incrementAndGet()
                if (d % 50 == 0 || d == snapshot.size) {
                    runOnUiThread {
                        status.text = "筛选进度: $d/${snapshot.size}，验证可用存活: ${validNodes.size} 个"
                    }
                }
            }
        }

        cpuPool.shutdown()
        Executors.newSingleThreadExecutor().execute {
            try { cpuPool.awaitTermination(180, TimeUnit.SECONDS) } catch (_: Exception) {}
            runOnUiThread {
                updateStatusDisplay()
                status.append("\n筛选完成！已保留 ${validNodes.size} 个真实有效节点。可点击【步骤 3】排序或直接导出。")
            }
        }
    }

    // ==================== 步骤 3：延迟排序 ====================

    private fun actionSortNodes() {
        if (validNodes.isEmpty()) {
            status.text = "可用节点池为空，请先执行【步骤 2】筛选"
            return
        }
        synchronized(validNodes) {
            validNodes.sortBy { it.second }
        }
        updateStatusDisplay()
        status.append("\n排序完成！已按延迟升序重排，最低延迟: ${validNodes.firstOrNull()?.second ?: 0}ms")
    }

    // ==================== 严格网络连通探测 ====================

    private data class NodeMeta(val host: String, val port: Int, val isTls: Boolean, val sni: String)

    private fun testConnectionStrict(meta: NodeMeta, timeout: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            if (meta.isTls) {
                // 对 TLS/Reality/Trojan 发送真正的 Client Hello 握手探测
                val socketFactory = SSLSocketFactory.getDefault()
                val sslSocket = socketFactory.createSocket() as SSLSocket
                sslSocket.soTimeout = timeout
                if (meta.sni.isNotBlank()) {
                    val sslParams = SSLParameters()
                    sslParams.serverNames = listOf(SNIHostName(meta.sni))
                    sslSocket.sslParameters = sslParams
                }
                sslSocket.connect(InetSocketAddress(meta.host, meta.port), timeout)
                // 开启握手，直接验证是否被 GFW RST
                sslSocket.startHandshake()
                val latency = System.currentTimeMillis() - start
                sslSocket.close()
                latency
            } else {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.soTimeout = timeout
                    socket.connect(InetSocketAddress(meta.host, meta.port), timeout)
                }
                System.currentTimeMillis() - start
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun parseNodeMeta(node: String): NodeMeta? {
        return try {
            val u = node.trim()
            when {
                u.startsWith("vmess://", ignoreCase = true) -> {
                    val b = u.substring(8)
                    val padLen = (4 - b.length % 4) % 4
                    val decoded = Base64.decode(b + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP).toString(StandardCharsets.UTF_8)
                    val obj = JSONObject(decoded)
                    val host = obj.optString("add").ifBlank { return null }
                    val port = obj.optInt("port", 443)
                    val tls = obj.optString("tls") == "tls"
                    val sni = obj.optString("sni", host)
                    NodeMeta(host, port, tls, sni)
                }
                u.startsWith("vless://", ignoreCase = true) || u.startsWith("trojan://", ignoreCase = true) -> {
                    val raw = u.substringAfter("://").substringBefore("#")
                    val serverPart = raw.substringBefore("?")
                    val hp = if (serverPart.contains("@")) serverPart.substringAfter("@") else serverPart
                    val host = hp.substringBefore(":")
                    val port = hp.substringAfter(":", "443").toIntOrNull() ?: 443
                    val query = parseQuery(raw.substringAfter("?", ""))
                    val isTls = query["security"] == "tls" || query["security"] == "reality" || u.startsWith("trojan://", true)
                    val sni = query["sni"] ?: query["peer"] ?: host
                    NodeMeta(host, port, isTls, sni)
                }
                u.startsWith("ss://", ignoreCase = true) -> {
                    val body = u.substring(5).substringBefore("#")
                    val serverPart = if (body.contains("@")) body.substringAfter("@") else body
                    val cleanHostPort = if (serverPart.contains(":")) serverPart else {
                        val padLen = (4 - serverPart.length % 4) % 4
                        Base64.decode(serverPart + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP).toString(StandardCharsets.UTF_8)
                    }
                    val hp = cleanHostPort.substringAfter("@", cleanHostPort)
                    val host = hp.substringBefore(":")
                    val port = hp.substringAfter(":").toIntOrNull() ?: 8388
                    if (host.isNotBlank()) NodeMeta(host, port, false, "") else null
                }
                else -> {
                    val raw = u.substringAfter("://").substringBefore("#").substringBefore("?")
                    val hostPort = if (raw.contains("@")) raw.substringAfter("@") else raw
                    val host = hostPort.substringBefore(":")
                    val port = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
                    if (host.isNotBlank()) NodeMeta(host, port, false, "") else null
                }
            }
        } catch (_: Exception) { null }
    }

    // ==================== 暴力下载与抓取核心 ====================

    private fun downloadWithTimeout(urlStr: String): String {
        var result = ""
        var currentTry = 0
        while (currentTry < 2 && result.isEmpty()) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ClashMeta/v1.18.0 (Android; arm64-v8a)")
                    setRequestProperty("Accept", "*/*")
                }
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                            val sb = StringBuilder()
                            val buffer = CharArray(32768)
                            var read: Int
                            while (reader.read(buffer).also { read = it } != -1) {
                                sb.append(buffer, 0, read)
                            }
                            result = sb.toString()
                        }
                    }
                }
            } catch (_: Exception) {
                currentTry++
            } finally {
                conn?.disconnect()
            }
        }
        return result
    }

    private fun extractNodesFull(rawText: String): List<String> {
        val candidates = ArrayList<String>()
        val prefixes = listOf("vmess://", "vless://", "trojan://", "ss://", "ssr://", "hy2://", "hysteria2://", "tuic://")

        // 正则强行提取 URI（防止各种破损混排）
        val uriRegex = Regex("(?:vmess|vless|trojan|ss|ssr|hy2|hysteria2|tuic)://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
        uriRegex.findAll(rawText).forEach { match ->
            candidates.add(match.value.trim().trimEnd(',', ';', ']', ')', '}', '\r', '\n'))
        }

        fun tryBase64Decode(s: String): String? {
            val clean = s.replace(Regex("[^A-Za-z0-9+/=\\-_]"), "")
            if (clean.length < 16) return null
            val padLen = (4 - clean.length % 4) % 4
            val padded = clean + "=".repeat(padLen)
            return try {
                val data = Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE)
                val decoded = String(data, StandardCharsets.UTF_8)
                if (prefixes.any { decoded.contains(it, true) }) decoded else null
            } catch (_: Exception) { null }
        }

        fun parseRecursive(text: String, depth: Int = 0) {
            if (depth > 2 || text.isBlank()) return
            text.lines().forEach { line ->
                val l = line.trim()
                if (l.length >= 16 && !l.contains("://")) {
                    tryBase64Decode(l)?.let { decodedLine ->
                        uriRegex.findAll(decodedLine).forEach { match ->
                            candidates.add(match.value.trim())
                        }
                        parseRecursive(decodedLine, depth + 1)
                    }
                }
            }
            tryBase64Decode(text)?.let { decodedFull ->
                uriRegex.findAll(decodedFull).forEach { match ->
                    candidates.add(match.value.trim())
                }
                parseRecursive(decodedFull, depth + 1)
            }
            if (text.contains("proxies:", true) || text.contains("- name:", true)) {
                candidates.addAll(parseClashYamlToNodes(text))
            }
        }

        parseRecursive(rawText)
        return candidates
    }

    private fun parseClashYamlToNodes(yamlText: String): List<String> {
        val list = ArrayList<String>()
        val lines = yamlText.lines()
        var currentMap: MutableMap<String, String>? = null

        fun convertMapToUri(map: Map<String, String>): String? {
            return try {
                val type = map["type"]?.lowercase()?.trim() ?: return null
                val server = map["server"]?.trim() ?: return null
                val port = map["port"]?.trim() ?: "443"
                val name = map["name"]?.trim() ?: "Node"
                val encodedName = Uri.encode(name)

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
                                put("host", map["host"] ?: map["ws-headers-host"] ?: "")
                                put("path", map["path"] ?: map["ws-path"] ?: "")
                            }
                            val b64 = Base64.encodeToString(obj.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                            "vmess://$b64"
                        } else null
                    }
                    "vless" -> {
                        val uuid = map["uuid"] ?: ""
                        if (server.isNotBlank() && uuid.isNotBlank()) {
                            val tls = if (map["tls"] == "true") "tls" else "none"
                            val net = map["network"] ?: "tcp"
                            val sni = map["servername"] ?: map["sni"] ?: ""
                            val flow = map["flow"] ?: ""
                            val isReality = map["reality-opts"] != null || map["reality"] == "true" || map.containsKey("public-key")
                            val sec = if (isReality) "reality" else tls
                            val pbk = map["public-key"] ?: map["pbk"] ?: ""
                            val sid = map["short-id"] ?: map["sid"] ?: ""
                            val fp = map["client-fingerprint"] ?: map["fp"] ?: "chrome"
                            val path = Uri.encode(map["path"] ?: map["ws-path"] ?: "")
                            val host = Uri.encode(map["host"] ?: map["ws-headers-host"] ?: "")

                            val query = StringBuilder("security=$sec&type=$net&sni=$sni&flow=$flow")
                            if (sec == "reality") query.append("&pbk=$pbk&sid=$sid&fp=$fp")
                            if (path.isNotBlank()) query.append("&path=$path")
                            if (host.isNotBlank()) query.append("&host=$host")

                            "vless://$uuid@$server:$port?$query#$encodedName"
                        } else null
                    }
                    "trojan" -> {
                        val password = map["password"] ?: ""
                        if (server.isNotBlank() && password.isNotBlank()) {
                            val sni = map["sni"] ?: map["servername"] ?: map["peer"] ?: ""
                            "trojan://$password@$server:$port?sni=$sni#$encodedName"
                        } else null
                    }
                    "ss" -> {
                        val cipher = map["cipher"]?.takeIf { it != "null" && it.isNotBlank() } ?: "aes-128-gcm"
                        val password = map["password"] ?: ""
                        if (server.isNotBlank() && password.isNotBlank()) {
                            val userPart = Base64.encodeToString("$cipher:$password".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                            "ss://$userPart@$server:$port#$encodedName"
                        } else null
                    }
                    else -> null
                }
            } catch (_: Exception) { null }
        }

        fun flushCurrent() {
            currentMap?.let { map -> convertMapToUri(map)?.let { list.add(it) } }
            currentMap = null
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("- {") && line.endsWith("}")) {
                flushCurrent()
                val tempMap = HashMap<String, String>()
                line.removeSurrounding("- {", "}").split(",").forEach { pair ->
                    val kv = pair.split(":", limit = 2)
                    if (kv.size == 2) {
                        tempMap[kv[0].trim().replace("\"", "").replace("'", "")] = kv[1].trim().replace("\"", "").replace("'", "")
                    }
                }
                convertMapToUri(tempMap)?.let { list.add(it) }
                continue
            }
            if (line.startsWith("- name:") || line.startsWith("- type:")) {
                flushCurrent()
                currentMap = HashMap()
                val kv = line.removePrefix("-").trim().split(":", limit = 2)
                if (kv.size == 2) {
                    currentMap?.put(kv[0].trim().replace("\"", "").replace("'", ""), kv[1].trim().replace("\"", "").replace("'", ""))
                }
            } else if (currentMap != null && line.contains(":") && !line.startsWith("#")) {
                val kv = line.split(":", limit = 2)
                if (kv.size == 2) {
                    val v = kv[1].trim().replace("\"", "").replace("'", "")
                    if (v.isNotBlank()) currentMap?.put(kv[0].trim().replace("\"", "").replace("'", ""), v)
                }
            }
        }
        flushCurrent()
        return list
    }

    // ==================== 导出文件 ====================

    private fun exportFile(clash: Boolean) {
        val targetList = if (validNodes.isNotEmpty()) validNodes.map { it.first } else rawNodes
        if (targetList.isEmpty()) {
            status.text = "提示：当前没有任何节点可导出"
            return
        }
        val n = countInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 5000
        val selected = targetList.take(n)

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
                        val json = String(Base64.decode(b + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP), StandardCharsets.UTF_8)
                        val obj = JSONObject(json)
                        val name = cleanName(obj.optString("ps").ifBlank { "VMess" }, idx)
                        val server = obj.optString("add")
                        val port = obj.optInt("port", 443)
                        val uuid = obj.optString("id")
                        val alterId = obj.optInt("aid", 0)
                        var cipher = obj.optString("scy", "auto").ifBlank { "auto" }
                        if (cipher.equals("null", ignoreCase = true)) cipher = "auto"
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
                            } else if (net == "grpc") {
                                sb.append("    network: grpc\n")
                                sb.append("    grpc-opts:\n      grpc-service-name: \"$path\"\n")
                            }
                            proxyYamlList.add(sb.toString().trimEnd())
                            proxyNames.add(name)
                        }
                    }
                    node.startsWith("vless://", ignoreCase = true) -> {
                        val body = node.substring(8)
                        val uuid = body.substringBefore("@")
                        val rest = body.substringAfter("@")
                        val serverPort = rest.substringBefore("?").substringBefore("#")
                        val server = serverPort.substringBefore(":")
                        val port = serverPort.substringAfter(":", "443").toIntOrNull() ?: 443
                        val queryStr = if (rest.contains("?")) rest.substringAfter("?").substringBefore("#") else ""
                        val queryMap = parseQuery(queryStr)
                        val rawRemark = if (rest.contains("#")) URLDecoder.decode(rest.substringAfter("#"), "UTF-8") else "VLESS"
                        val name = cleanName(rawRemark, idx)

                        val sec = queryMap["security"] ?: ""
                        val flow = queryMap["flow"] ?: ""
                        val sni = queryMap["sni"] ?: ""
                        val net = queryMap["type"] ?: "tcp"
                        val pbk = queryMap["pbk"] ?: ""
                        val sid = queryMap["sid"] ?: ""
                        val fp = queryMap["fp"] ?: "chrome"

                        if (server.isNotBlank() && uuid.isNotBlank()) {
                            val sb = StringBuilder()
                            sb.append("  - name: \"$name\"\n")
                            sb.append("    type: vless\n")
                            sb.append("    server: $server\n")
                            sb.append("    port: $port\n")
                            sb.append("    uuid: $uuid\n")
                            sb.append("    udp: true\n")
                            if (flow.isNotBlank()) sb.append("    flow: $flow\n")

                            if (sec == "reality") {
                                sb.append("    tls: true\n")
                                if (sni.isNotBlank()) sb.append("    servername: $sni\n")
                                sb.append("    reality-opts:\n")
                                sb.append("      public-key: $pbk\n")
                                if (sid.isNotBlank()) sb.append("      short-id: $sid\n")
                                sb.append("    client-fingerprint: $fp\n")
                            } else if (sec == "tls") {
                                sb.append("    tls: true\n")
                                if (sni.isNotBlank()) sb.append("    servername: $sni\n")
                            }

                            if (net == "ws") {
                                sb.append("    network: ws\n")
                                sb.append("    ws-opts:\n")
                                queryMap["path"]?.let { sb.append("      path: \"$it\"\n") }
                                queryMap["host"]?.let { sb.append("      headers:\n        Host: $it\n") }
                            } else if (net == "grpc") {
                                sb.append("    network: grpc\n")
                                sb.append("    grpc-opts:\n      grpc-service-name: \"${queryMap["serviceName"] ?: queryMap["path"] ?: ""}\"\n")
                            }
                            proxyYamlList.add(sb.toString().trimEnd())
                            proxyNames.add(name)
                        }
                    }
                    node.startsWith("trojan://", ignoreCase = true) -> {
                        val body = node.substring(9)
                        val password = body.substringBefore("@")
                        val rest = body.substringAfter("@")
                        val serverPort = rest.substringBefore("?").substringBefore("#")
                        val server = serverPort.substringBefore(":")
                        val port = serverPort.substringAfter(":", "443").toIntOrNull() ?: 443
                        val queryStr = if (rest.contains("?")) rest.substringAfter("?").substringBefore("#") else ""
                        val queryMap = parseQuery(queryStr)
                        val rawRemark = if (rest.contains("#")) URLDecoder.decode(rest.substringAfter("#"), "UTF-8") else "Trojan"
                        val name = cleanName(rawRemark, idx)
                        val sni = queryMap["sni"] ?: queryMap["peer"] ?: ""

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
                        val body = node.substring(5)
                        val rawRemark = if (body.contains("#")) URLDecoder.decode(body.substringAfter("#"), "UTF-8") else "SS"
                        val name = cleanName(rawRemark, idx)
                        val mainPart = body.substringBefore("#")
                        var userInfo: String
                        val server: String
                        val port: Int

                        if (mainPart.contains("@")) {
                            val userBase = mainPart.substringBefore("@")
                            userInfo = if (!userBase.contains(":")) {
                                val padLen = (4 - userBase.length % 4) % 4
                                try { String(Base64.decode(userBase + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP), StandardCharsets.UTF_8) } catch (_: Exception) { "" }
                            } else userBase
                            val sp = mainPart.substringAfter("@")
                            server = sp.substringBefore(":")
                            port = sp.substringAfter(":", "8388").toIntOrNull() ?: 8388
                        } else {
                            val padLen = (4 - mainPart.length % 4) % 4
                            val decoded = try { String(Base64.decode(mainPart + "=".repeat(padLen), Base64.DEFAULT or Base64.NO_WRAP), StandardCharsets.UTF_8) } catch (_: Exception) { "" }
                            userInfo = decoded.substringBefore("@")
                            val sp = decoded.substringAfter("@", "")
                            server = sp.substringBefore(":")
                            port = sp.substringAfter(":", "8388").toIntOrNull() ?: 8388
                        }

                        val cipher = userInfo.substringBefore(":", "").trim()
                        val password = userInfo.substringAfter(":", "").trim()

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openOutputStream(data.data!!).use { out ->
                    out?.write((pendingExport ?: "").toByteArray(StandardCharsets.UTF_8))
                }
                status.text = "文件导出成功！"
            } catch (e: Exception) {
                status.text = "导出失败：${e.message}"
            }
        }
    }
}
