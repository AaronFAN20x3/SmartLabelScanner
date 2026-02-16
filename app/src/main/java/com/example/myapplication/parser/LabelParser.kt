package com.example.myapplication.parser

import java.text.Normalizer

class LabelParser {

    fun parse(text: String): ScanResult {

        val rawLines = text.lines()

        // 1) 清洗：trim + 去空行 + 压缩空白
        val lines = rawLines
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        var palletId: String? = null
        var qty: String? = null
        var stockCode: String? = null

        // 记录 Qty 出现在哪一行（为了抓下面的 stockcode）
        var qtyLineIndex: Int? = null

        for (i in lines.indices) {

            val lineRaw = lines[i]
            val lineNorm = normalize(lineRaw) // lower + 去音标
            val noSpace = lineNorm.replace(" ", "")

            // ---------------- Pallet ID ----------------
            // "Pallet ID 79773" 或 "Pallet ID" 下一行是数字
            if (palletId == null && isPalletIdLabel(lineNorm)) {
                palletId = findNearby(lines, i, window = 2) { c ->
                    c.matches(Regex("^\\d{4,10}$")) // pallet id 可能 4~10 位
                }
                continue
            }

            // ---------------- Qty ----------------
            // "Qty 1086" 或 "Qty" 下一行是数字
            if (qty == null && isQtyLabel(noSpace, lineRaw)) {
                qty = findNearby(lines, i, window = 2) { c ->
                    c.matches(Regex("^\\d{1,7}$")) && c.toIntOrNull() in 1..500000
                }
                qtyLineIndex = i
                continue
            }
        }

        // ---------------- StockCode（Qty 下方那一行） ----------------
        // 你的新规则：Qty 下面的第一条“纯数字行”，就是 stockcode
        if (stockCode == null && qtyLineIndex != null) {
            stockCode = findStockCodeUnderQty(lines, qtyLineIndex!!, palletId, qty)
        }

        // （可选兜底）如果没找到，再尝试全局找一个“像 stockcode 的纯数字”
        // 但会排除 palletId / qty，避免误判
        if (stockCode == null) {
            val excluded = setOfNotNull(palletId, qty)
            stockCode = lines
                .asSequence()
                .flatMap { tokenize(it).asSequence() }
                .map { it.trim() }
                .filter { it.matches(Regex("^\\d{4,10}$")) } // 纯数字 4~10 位
                .filterNot { it in excluded }
                .firstOrNull()
        }

        return ScanResult(
            stockCode = stockCode,
            palletId = palletId,
            qty = qty
        )
    }

    // ===============================
    // 标签识别
    // ===============================

    private fun isPalletIdLabel(line: String): Boolean {
        val noSpace = line.replace(" ", "")
        if (noSpace.contains("palletid")) return true
        if (line.contains("pallet") && (line.contains("id") || line.contains("1d") || line.contains("ld"))) return true
        return false
    }

    private fun isQtyLabel(noSpaceNorm: String, raw: String): Boolean {
        val n = normalize(raw).replace(" ", "")

        // Qty / qtv / q1y / 0ty 等 OCR 混淆
        if (n.matches(Regex("^.?ty:?$"))) return true
        if (n.matches(Regex("^q.{0,2}:?$"))) return true
        if (n.contains("qty")) return true

        return false
    }

    // ===============================
    // StockCode: Qty 下方提取
    // ===============================

    private fun findStockCodeUnderQty(
        lines: List<String>,
        qtyIndex: Int,
        palletId: String?,
        qty: String?
    ): String? {
        val excluded = setOfNotNull(palletId, qty)

        // 从 Qty 行往下找：第一条“纯数字 token”，就是 stockcode
        for (j in (qtyIndex + 1) until minOf(lines.size, qtyIndex + 6)) {
            val line = lines[j].trim()

            // 如果遇到明显不是 stockcode 的标题，可以选择跳过/继续
            // 这里保持简单：只要找到纯数字就返回
            val token = tokenize(line).firstOrNull { it.matches(Regex("^\\d{4,10}$")) }
            if (token != null && token !in excluded) {
                return token
            }
        }
        return null
    }

    // ===============================
    // 通用：扫窗口 + token
    // ===============================

    private fun findNearby(
        lines: List<String>,
        index: Int,
        window: Int,
        accept: (String) -> Boolean
    ): String? {

        extractAfterColon(lines[index])?.let { if (accept(it)) return it }
        tokenize(lines[index]).forEach { if (accept(it)) return it }

        for (d in 1..window) {
            val up = index - d
            val down = index + d

            if (up >= 0) {
                extractAfterColon(lines[up])?.let { if (accept(it)) return it }
                tokenize(lines[up]).forEach { if (accept(it)) return it }
            }
            if (down < lines.size) {
                extractAfterColon(lines[down])?.let { if (accept(it)) return it }
                tokenize(lines[down]).forEach { if (accept(it)) return it }
            }
        }
        return null
    }

    private fun extractAfterColon(line: String): String? {
        val idx = line.indexOf(':')
        if (idx < 0) return null
        val tail = line.substring(idx + 1).trim()
        if (tail.isBlank()) return null
        return tail.split(" ").firstOrNull()
    }

    private fun tokenize(line: String): List<String> {
        return line
            .replace(":", " ")
            .replace(",", " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    // ===============================
    // normalize：去音标 + lower
    // ===============================

    private fun normalize(s: String): String {
        val noDiacritics = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noDiacritics.lowercase()
    }
}
