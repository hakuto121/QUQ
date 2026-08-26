package com.example.nodepool

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.widget.*
import java.io.BufferedReader
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
    private lateinit var sourceList: TextView

    private val defaults = listOf(
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs_base64.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
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
        val saved = prefs.getStringSet("sources", null)
        sources.clear()
        if (saved.isNullOrEmpty()) sources.addAll(defaults) else sources.addAll(saved)
    }

    private fun saveSources() {
        prefs.edit().putStringSet("sources", sources).apply()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "节点池管理器"
            textSize = 28f
            setPadding(0,0,0,18)
        })

        root.addView(TextView(this).apply {
            text = "把 TXT、Base64、Clash/V2Ray/Xray 订阅地址粘贴到下面。"
            textSize = 15f
        })

        sourceInput = EditText(this).apply {
            hint = "https://……"
            minLines = 2
        }
        root.addView(sourceInput)

        val addBtn = Button(this).apply { text = "＋ 添加订阅源" }
        root.addView(addBtn)

        sourceList = TextView(this).apply { setPadding(0, 10, 0, 10) }
        root.addView(sourceList)
        refreshSourceList()

        val updateBtn = Button(this).apply { text = "① 更新全部订阅并去重" }
        root.addView(updateBtn)

        val speedBtn = Button(this).apply { text = "② 开始测速" }
        root.addView(speedBtn)

        timeoutInput = EditText(this).apply {
            hint = "测速超时（毫秒）"
            setText("3000")
            inputType = 2
        }
        root.addView(timeoutInput)

        countInput = EditText(this).apply {
            hint = "导出前 N 个"
            setText("100")
            inputType = 2
        }
        root.addView(countInput)

        val exportTxt = Button(this).apply { text = "③ 导出 TXT" }
        root.addView(exportTxt)

        val exportClash = Button(this).apply { text = "④ 导出 Clash YAML" }
        root.addView(exportClash)

        val clearBtn = Button(this).apply { text = "清空当前节点结果" }
        root.addView(clearBtn)

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 18, 0, 0)
        }
        root.addView(status)

        setContentView(scroll)
        status.text = "准备好了：${sources.size} 个订阅源"

        addBtn.setOnClickListener {
            val u = sourceInput.text.toString().trim()
            if (u.startsWith("http://") || u.startsWith("https://")) {
                sources.add(u)
                saveSources()
                sourceInput.text.clear()
                refreshSourceList()
                status.text = "已添加订阅源，共 ${sources.size} 个"
            } else {
                status.text = "请输入 http:// 或 https:// 开头的订阅地址"
            }
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
        sourceList.text = buildString {
            append("当前订阅源：${sources.size} 个\n")
            sources.forEachIndexed { i, s ->
                append("${i + 1}. ${s}\n")
            }
        }
    }

    private fun updateAll() {
        status.text = "正在下载 ${sources.size} 个订阅源……"
        executor.execute {
            val result = LinkedHashSet<String>()
            var ok = 0
            for (url in sources) {
                try {
                    val text = download(url)
                    val extracted = extractNodes(text)
                    if (extracted.isNotEmpty()) ok++
                    result.addAll(extracted)
                } catch (_: Exception) {}
                runOnUiThread {
                    status.text = "正在更新：已处理 ${ok}/${sources.size} 个源，当前 ${result.size} 个节点"
                }
            }
            nodes.clear()
            nodes.addAll(result)
            scored.clear()
            runOnUiThread {
                status.text = "更新完成：${nodes.size} 个去重节点"
            }
        }
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

        // Whole-file Base64 / URL-safe Base64
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

        // Base64-encoded individual lines (common in mixed subscription files)
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
            status.text = "请先点击“更新全部订阅并去重”"
            return
        }
        val timeout = timeoutInput.text.toString().toIntOrNull()?.coerceIn(500, 15000) ?: 3000
        val snapshot = nodes.toList()
        status.text = "正在测速：0/${snapshot.size}"
        executor.execute {
            val results = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
            val done = AtomicInteger(0)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(24)
            snapshot.forEach { node ->
                pool.submit {
                    val hp = parseHostPort(node)
                    if (hp != null) {
                        val start = System.currentTimeMillis()
                        try {
                            Socket().use {
                                it.connect(InetSocketAddress(hp.first, hp.second), timeout)
                            }
                            results.add(node to (System.currentTimeMillis() - start))
                        } catch (_: Exception) {}
                    }
                    val d = done.incrementAndGet()
                    if (d % 10 == 0 || d == snapshot.size) {
                        runOnUiThread { status.text = "正在测速：$d/${snapshot.size}，成功 ${results.size}" }
                    }
                }
            }
            pool.shutdown()
            while (!pool.isTerminated) Thread.sleep(50)
            results.sortBy { it.second }
            scored.clear(); scored.addAll(results)
            nodes.clear(); nodes.addAll(results.map { it.first })
            runOnUiThread {
                status.text = "测速完成：${results.size}/${snapshot.size} 个节点 TCP 可达，已按延迟排序"
            }
        }
    }

    private fun parseHostPort(node: String): Pair<String, Int>? {
        return try {
            var u = node
            if (u.startsWith("vmess://")) {
                val b = u.removePrefix("vmess://")
                val decoded = Base64.decode(b, Base64.DEFAULT or Base64.NO_WRAP).toString(Charsets.UTF_8)
                val obj = org.json.JSONObject(decoded)
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
            buildString {
                append("proxies:\n")
                selected.forEachIndexed { i, node ->
                    val safe = node.replace("\"", "\\\"")
                    append("  - name: \"Node-${i + 1}\"\n")
                    append("    type: \"").append(node.substringBefore("://")).append("\"\n")
                    append("    url: \"").append(safe).append("\"\n")
                }
            }
        }
        val ext = if (clash) "yaml" else "txt"
        val name = "nodepool_top_${selected.size}.$ext"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = if (clash) "text/yaml" else "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, if (clash) 9002 else 9001)
        pendingExport = body
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
