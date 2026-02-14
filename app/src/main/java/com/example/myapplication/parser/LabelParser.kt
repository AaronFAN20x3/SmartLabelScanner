package com.example.myapplication.parser

import java.text.Normalizer

class LabelParser {

    fun parse(text: String): ScanResult {
        val rawLines = text.lines()

        // 1) 清洗：trim + 去空行 + 去奇怪空白
        val lines = rawLines
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        var stockCode: String? = null
        var salesOrder: String? = null
        var qty: String? = null
        var po: String? = null
        var weight: String? = null

        // 2) 主循环：优先按“标签 → 值”的方式抓
        for (i in lines.indices) {
            val lineRaw = lines[i]
            val line = normalize(lineRaw)          // 去音标 + lower + 去多余符号
            val lineNoSpace = line.replace(" ", "")

            // ----- PO -----
            // PO 典型：PO: GRO024
            // OCR 噪声：P0 / po / P O
            if (po == null && isPoLabel(lineNoSpace)) {
                po = extractAfterColonOrNearbyToken(lines, i) { token ->
                    // PO 一般是字母+数字组合，如 GRO024
                    token.matches(Regex("^[A-Z]{2,6}\\d{2,8}$"))
                }
                continue
            }

            // ----- Sales Order -----
            // 典型：Sales Order: 74985213 或 Sales Order 74985213 或 下一行是数字
            if (salesOrder == null && isSalesOrderLabel(line)) {
                salesOrder = findNearby(lines, i, window = 3) { candidate ->
                    candidate.matches(Regex("^\\d{6,12}$")) // 你的是 8 位，留宽一点
                }
                continue
            }

            // ----- Weight -----
            // 典型：Weight: 12.5 kg
            // OCR 噪声：Wéight / Wěight / ight / We1ght
            if (weight == null && isWeightLabel(line)) {
                // 先从本行找带小数的数字
                weight = Regex("\\d+(?:\\.\\d+)?").find(lineRaw)?.value
                    ?: findNearby(lines, i, window = 2) { c ->
                        // weight 允许 1~3 位整数 + 可选小数，例如 12.5
                        c.matches(Regex("^\\d{1,3}(?:\\.\\d+)?$"))
                    }
                continue
            }

            // ----- Qty -----
            // 典型：Qty: 250
            // OCR 噪声：aty / oty / dty / zty / 0ty / åty / qtv / qly
            if (qty == null && isQtyLabel(lineNoSpace, lineRaw)) {
                qty = findNearby(lines, i, window = 3) { c ->
                    // qty 通常是 1~4 位整数（你也可以改成 1..5000）
                    c.matches(Regex("^\\d{1,4}$")) && c.toIntOrNull() in 1..5000
                }
                continue
            }

            // ----- Stock Code -----
            // 典型：N4C3K7P9（字母数字混合）
            // 过滤：不能是纯数字；长度 6~12；至少包含 1 个字母 1 个数字
            if (stockCode == null && looksLikeStockCode(lineRaw)) {
                stockCode = lineRaw.trim()
            }
        }

        // 3) fallback：如果 qty 还没抓到，用“孤立数字评分”做兜底
        if (qty == null) {
            qty = pickBestQtyFallback(lines, salesOrder, weight)
        }

        // 4) fallback：salesOrder 兜底：找最像 8 位的数字（但排除 weight/qty）
        if (salesOrder == null) {
            salesOrder = lines
                .mapNotNull { Regex("\\d{6,12}").find(it)?.value }
                .firstOrNull { it.length >= 8 }  // 你要严格 8 位就改 == 8
        }

        return ScanResult(
            stockCode = stockCode,
            salesOrder = salesOrder,
            qty = qty,
            po = po,
            weight = weight
        )
    }

    // -----------------------------
    // 标签识别（核心：不要写死词）
    // -----------------------------

    private fun isSalesOrderLabel(line: String): Boolean {
        // 允许 "sales order" 被 OCR 打乱：sa1es / salcs / so
        // 但你要求不太死，所以只要出现 sales + order 就算
        return line.contains("sales") && line.contains("order")
    }

    private fun isPoLabel(lineNoSpace: String): Boolean {
        // PO / P0 / P O:
        return lineNoSpace.startsWith("po") || lineNoSpace.startsWith("p0")
    }

    private fun isWeightLabel(line: String): Boolean {
        // weight / we1ght / wéight / ight (缺 w)
        // 这里用宽松：包含 "weight" 或者以 "ight" 结尾并带冒号
        if (line.contains("weight")) return true
        if (line.contains("we1ght")) return true
        if (line.contains("ight") && line.contains(":")) return true
        return false
    }

    private fun isQtyLabel(lineNoSpace: String, raw: String): Boolean {
        val n = normalize(raw).replace(" ", "")

        // 1) 最稳定：结构匹配：任意1个字符 + "ty" + 可选冒号
        //    匹配 Qty/Oty/aty/dty/zty/0ty/åty 等等
        if (n.matches(Regex("^.?ty:?$"))) return true

        // 2) 第二类：qtv / qly / qu (OCR 把 y 识别错) —— 只要 "q" 开头且末尾像 ":" 也算
        if (n.matches(Regex("^q.{0,2}:?$"))) {
            // 但排除 "query" 这种
            if (!n.contains("search") && !n.contains("query")) return true
        }

        // 3) 最后兜底：包含 "qty"（如果 OCR 正常）
        if (n.contains("qty")) return true

        return false
    }

    // -----------------------------
    // 值提取：别只看 ±1，扫窗口 + 过滤
    // -----------------------------

    private fun findNearby(lines: List<String>, index: Int, window: Int, accept: (String) -> Boolean): String? {
        // 先看本行冒号后
        extractAfterColon(lines[index])?.let { if (accept(it)) return it }

        // 看本行有没有合格 token
        tokenize(lines[index]).forEach { if (accept(it)) return it }

        // 再扫上下窗口
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

    private fun extractAfterColonOrNearbyToken(
        lines: List<String>,
        index: Int,
        accept: (String) -> Boolean
    ): String? {
        // 先取冒号后
        extractAfterColon(lines[index])?.let { token ->
            val t = token.trim().uppercase()
            if (accept(t)) return t
        }

        // 再扫附近 token
        return findNearby(lines, index, window = 2) { token ->
            val t = token.trim().uppercase()
            accept(t)
        }?.uppercase()
    }

    private fun extractAfterColon(line: String): String? {
        val idx = line.indexOf(':')
        if (idx < 0) return null
        val tail = line.substring(idx + 1).trim()
        if (tail.isBlank()) return null
        // 只拿第一个 token，避免 "12.5 kg" 被拿成 "12.5 kg"
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

    // -----------------------------
    // Stock Code 识别（更稳）
    // -----------------------------

    private fun looksLikeStockCode(raw: String): Boolean {
        val s = raw.trim()
        if (!s.matches(Regex("^[A-Z0-9]{6,12}$", RegexOption.IGNORE_CASE))) return false
        if (s.all { it.isDigit() }) return false
        val hasLetter = s.any { it.isLetter() }
        val hasDigit = s.any { it.isDigit() }
        if (!hasLetter || !hasDigit) return false
        // 排除常见噪声
        val lower = normalize(s)
        if (lower.contains("premium") || lower.contains("labelparser") || lower.contains("camera")) return false
        return true
    }

    // -----------------------------
    // Qty fallback（当没有任何标签命中）
    // -----------------------------

    private fun pickBestQtyFallback(lines: List<String>, salesOrder: String?, weight: String?): String? {
        // 收集候选：1..5000 的纯数字
        val candidates = mutableListOf<Int>()
        for (l in lines) {
            tokenize(l).forEach { t ->
                val n = t.toIntOrNull()
                if (n != null && n in 1..5000) candidates.add(n)
            }
        }
        if (candidates.isEmpty()) return null

        // 如果只有一个候选，直接用
        if (candidates.size == 1) return candidates[0].toString()

        // 多个候选：优先选择“最常出现的值”
        return candidates
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }?.key
            ?.toString()
    }

    // -----------------------------
    // 文本 normalize：去音标 + lower
    // -----------------------------

    private fun normalize(s: String): String {
        // 去掉 é/ě/å 这类音标，变成 e/e/a
        val noDiacritics = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noDiacritics.lowercase()
    }
}
