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
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.regex.Pattern

// 1. 高并发调度管理器
object NodeFetchManager {
    // 动态并发线程池：针对移动端优化核心线程与最大线程
    private val cpuCount = Runtime.getRuntime().availableProcessors()
    val executor: ExecutorService = ThreadPoolExecutor(
        cpuCount * 2,
        cpuCount * 4,
        30L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(256),
        Executors.defaultThreadFactory()
    )

    // 并发拉取所有订阅源
    fun fetchAllSubscriptions(urls: List<String>, onProgress: (Int, Int) -> Unit): List<String> {
        val total = urls.size
        var completed = 0
        val rawResults = CopyOnWriteArrayList<String>()
        val futures = mutableListOf<Future<*>>()

        for (urlStr in urls) {
            val task = executor.submit {
                val content = fetchUrlWithRetry(urlStr.trim(), maxRetries = 2)
                if (!content.isNullOrBlank()) {
                    rawResults.add(content)
                }
                synchronized(this) {
                    completed++
                    onProgress(completed, total)
                }
            }
            futures.add(task)
        }

        // 等待所有拉取任务结束或超时 (统一等待最长 35 秒)
        for (f in futures) {
            try {
                f.get(35, TimeUnit.SECONDS)
            } catch (e: Exception) {
                f.cancel(true)
            }
        }

        return rawResults
    }

    // 带超时与重试的网络请求
    private fun fetchUrlWithRetry(urlStr: String, maxRetries: Int): String? {
        var currentTry = 0
        while (currentTry <= maxRetries) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ClashMeta/v1.18.0 clash-verge/v1.5.1 v2rayNG/1.8.5")
                    setRequestProperty("Accept", "*/*")
                }

                if (conn.responseCode in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                        return reader.readText()
                    }
                }
            } catch (e: Exception) {
                currentTry++
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }
}

// 2. 自适应协议与格式解析器
object NodeAdaptiveParser {
    private val PROTOCOL_PREFIXES = listOf(
        "vmess://", "vless://", "ss://", "ssr://",
        "trojan://", "hysteria://", "hysteria2://", "hy2://", "tuic://"
    )

    // 主入口：将各类格式（Base64、YAML、纯文本混合）统一提取为协议 URL 列表
    fun parseToNodeLinks(rawContent: String): List<String> {
        val resultNodes = mutableListOf<String>()
        val trimmed = rawContent.trim()

        // 1. 尝试直接识别纯文本协议链接
        val directLinks = extractDirectLinks(trimmed)
        if (directLinks.isNotEmpty()) {
            resultNodes.addAll(directLinks)
        }

        // 2. 尝试 Base64 解码并提取
        val decodedBase64 = tryDecodeBase64(trimmed)
        if (!decodedBase64.isNullOrBlank()) {
            val base64Links = extractDirectLinks(decodedBase64)
            resultNodes.addAll(base64Links)
        }

        // 3. 尝试 Clash YAML 格式解析
        if (trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")) {
            val yamlNodes = parseClashYamlToNodes(trimmed)
            resultNodes.addAll(yamlNodes)
        }

        return resultNodes.distinct()
    }

    // 正则直接抓取协议链接
    private fun extractDirectLinks(text: String): List<String> {
        val list = mutableListOf<String>()
        text.lines().forEach { line ->
            val cleanLine = line.trim()
            if (PROTOCOL_PREFIXES.any { cleanLine.startsWith(it, ignoreCase = true) }) {
                list.add(cleanLine)
            }
        }
        return list
    }

    // 容错 Base64 解码器
    private fun tryDecodeBase64(input: String): String? {
        val clean = input.replace("\r", "").replace("\n", "").replace(" ", "").trim()
        val flags = listOf(Base64.DEFAULT, Base64.NO_WRAP, Base64.URL_SAFE)
        for (flag in flags) {
            try {
                val data = Base64.decode(clean, flag)
                val decoded = String(data, StandardCharsets.UTF_8)
                if (PROTOCOL_PREFIXES.any { decoded.contains(it, ignoreCase = true) }) {
                    return decoded
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // 轻量级 Clash YAML 解析（无需额外依赖库）
    private fun parseClashYamlToNodes(yamlText: String): List<String> {
        val nodes = mutableListOf<String>()
        try {
            val lines = yamlText.lines()
            var inProxies = false
            var currentProxyMap = mutableMapOf<String, String>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("proxies:")) {
                    inProxies = true
                    continue
                }
                if (inProxies && (trimmed.startsWith("proxy-groups:") || trimmed.startsWith("rules:"))) {
                    inProxies = false
                    if (currentProxyMap.isNotEmpty()) {
                        convertMapToUri(currentProxyMap)?.let { nodes.add(it) }
                        currentProxyMap.clear()
                    }
                    break
                }

                if (inProxies) {
                    if (trimmed.startsWith("- {")) {
                        // 单行 JSON 风格: - {name: "xxx", type: vmess, server: 1.1.1.1, ...}
                        val jsonStyle = trimmed.removePrefix("-").trim()
                        parseSingleLineYaml(jsonStyle)?.let { nodes.add(it) }
                    } else if (trimmed.startsWith("- name:") || (line.startsWith("  - ") && trimmed.contains(":"))) {
                        if (currentProxyMap.isNotEmpty()) {
                            convertMapToUri(currentProxyMap)?.let { nodes.add(it) }
                            currentProxyMap.clear()
                        }
                        val pair = trimmed.removePrefix("-").trim().split(":", limit = 2)
                        if (pair.size == 2) currentProxyMap[pair[0].trim()] = cleanQuotes(pair[1].trim())
                    } else if (trimmed.contains(":")) {
                        val pair = trimmed.split(":", limit = 2)
                        if (pair.size == 2) currentProxyMap[pair[0].trim()] = cleanQuotes(pair[1].trim())
                    }
                }
            }
            if (currentProxyMap.isNotEmpty()) {
                convertMapToUri(currentProxyMap)?.let { nodes.add(it) }
            }
        } catch (_: Exception) {}
        return nodes
    }

    private fun cleanQuotes(str: String): String = str.removeSurrounding("\"").removeSurrounding("'")

    // 将 YAML Map 转换回通用 URI 协议格式
    private fun convertMapToUri(map: Map<String, String>): String? {
        val type = map["type"]?.lowercase() ?: return null
        val server = map["server"] ?: return null
        val port = map["port"] ?: return null
        val name = map["name"] ?: "$server:$port"
        val encodedName = Uri.encode(name)

        return when (type) {
            "vmess" -> {
                val vmessJson = JSONObject().apply {
                    put("v", "2")
                    put("ps", name)
                    put("add", server)
                    put("port", port)
                    put("id", map["uuid"] ?: "")
                    put("aid", map["alterId"] ?: "0")
                    put("net", map["network"] ?: "tcp")
                    put("type", "none")
                    put("host", map["ws-opts.headers.Host"] ?: map["servername"] ?: "")
                    put("path", map["ws-opts.path"] ?: "")
                    put("tls", if (map["tls"] == "true") "tls" else "none")
                }
                "vmess://" + Base64.encodeToString(vmessJson.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            }
            "vless" -> {
                val uuid = map["uuid"] ?: ""
                "vless://$uuid@$server:$port?type=${map["network"] ?: "tcp"}&security=${if (map["tls"] == "true") "tls" else "none"}#$encodedName"
            }
            "ss" -> {
                val cipher = map["cipher"] ?: "aes-128-gcm"
                val password = map["password"] ?: ""
                val auth = Base64.encodeToString("$cipher:$password".toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                "ss://$auth@$server:$port#$encodedName"
            }
            "trojan" -> {
                val password = map["password"] ?: ""
                "trojan://$password@$server:$port?sni=${map["sni"] ?: server}#$encodedName"
            }
            "hysteria2", "hy2" -> {
                val auth = map["password"] ?: map["auth"] ?: ""
                "hy2://$auth@$server:$port?sni=${map["sni"] ?: ""}&insecure=${if (map["skip-cert-verify"] == "true") "1" else "0"}#$encodedName"
            }
            else -> null
        }
    }

    private fun parseSingleLineYaml(line: String): String? {
        val map = mutableMapOf<String, String>()
        val content = line.removePrefix("{").removeSuffix("}")
        val pairs = content.split(",")
        for (p in pairs) {
            val kv = p.split(":", limit = 2)
            if (kv.size == 2) {
                map[kv[0].trim()] = cleanQuotes(kv[1].trim())
            }
        }
        return convertMapToUri(map)
    }
}
