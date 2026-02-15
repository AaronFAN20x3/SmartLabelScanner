package com.example.myapplication.parser

import java.text.Normalizer

class LabelParser {

    fun parse(text: String): ScanResult {

        val rawLines = text.lines()

        // 1) 清洗：trim + 去空行 + 压缩空白
        val lines = rawLines
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        var stockCode: String? = null
        var salesOrder: String? = null
        var qty: String? = null
        var po: String? = null
        var weight: String? = null

        // 用来“排除污染”的字段（我们不一定要返回它，但要识别出来避免误判）
        var partNumber: String? = null
        var supplierId: String? = null
        var date: String? = null
        var barcodeLike: String? = null

        // 2) 主循环：优先“标签 → 值”
        for (i in lines.indices) {

            val lineRaw = lines[i]
            val lineNorm = normalize(lineRaw) // lower + 去音标
            val noSpace = lineNorm.replace(" ", "")

            // ---------------- PO ----------------
            // PO: GRO024 / PO : GRO024 / P0:
            if (po == null && isPoLabel(noSpace)) {
                po = extractAfterColonOrNearbyToken(lines, i) { token ->
                    // PO 一般：字母+数字，允许 O/0 混淆
                    token.matches(Regex("^[A-Z]{2,8}[0-9]{2,10}$"))
                }?.let { fixPo(it) }
                continue
            }

            // ---------------- Weight ----------------
            // Weight: 2.3 kg
            if (weight == null && isWeightLabel(lineNorm)) {
                weight = Regex("\\d+(?:\\.\\d+)?").find(lineRaw)?.value
                    ?: findNearby(lines, i, window = 2) { c ->
                        c.matches(Regex("^\\d{1,4}(?:\\.\\d+)?$"))
                    }
                continue
            }

            // ---------------- Qty ----------------
            // Qty: 80 / aty: 80 / dty: 80 / zty: 80 / 0ty: 80 ...
            if (qty == null && isQtyLabel(noSpace, lineRaw)) {
                qty = findNearby(lines, i, window = 3) { c ->
                    c.matches(Regex("^\\d{1,5}$")) && c.toIntOrNull() in 1..50000
                }
                continue
            }

            // ---------------- Sales Order ----------------
            // Sales Order: 95237  (注意：这里是 5 位！)
            if (salesOrder == null && isSalesOrderLabel(lineNorm)) {
                salesOrder = findNearby(lines, i, window = 2) { candidate ->
                    candidate.matches(Regex("^\\d{5,12}$"))  // ✅ 关键修复：允许 5 位
                }?.also {
                    // 额外保护：如果刚好和 partNumber 一样，宁可不要
                    if (it == partNumber) salesOrder = null
                }
                continue
            }

            // ---------------- Stockcode ----------------
            // Stockcode: F-19 / Stock Code: N4C3K7P9
            if (stockCode == null && isStockCodeLabel(lineNorm)) {
                stockCode = extractAfterColonOrNearbyToken(lines, i) { token ->
                    looksLikeStockCodeToken(token)
                }
                continue
            }

            // ---------------- Part Number (用于排除污染) ----------------
            // Part Number: 808089
            if (partNumber == null && isPartNumberLabel(lineNorm)) {
                partNumber = findNearby(lines, i, window = 2) { c ->
                    c.matches(Regex("^\\d{4,12}$"))
                }
                continue
            }

            // ---------------- Supplier ID (用于排除污染) ----------------
            if (supplierId == null && isSupplierIdLabel(lineNorm)) {
                supplierId = findNearby(lines, i, window = 2) { c ->
                    c.matches(Regex("^\\d{4,12}$"))
                }
                continue
            }

            // ---------------- Date (用于排除污染) ----------------
            if (date == null && isDateLabel(lineNorm)) {
                date = findNearby(lines, i, window = 2) { c ->
                    c.matches(Regex("^\\d{4}$"))
                }
                continue
            }

            // ---------------- 可能的条码编号（用于排除污染） ----------------
            // 例如：03E45971 这种经常被误当 stockCode
            if (barcodeLike == null && looksLikeBarcodeId(lineRaw)) {
                barcodeLike = lineRaw.trim()
                continue
            }
        }

        // 3) fallback：如果 stockCode 没拿到，才允许“孤立行猜”
        // 但会排除 partNumber / supplierId / date / barcodeLike / salesOrder / qty / weight
        if (stockCode == null) {
            stockCode = lines
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter { looksLikeStockCodeToken(it) }
                .filterNot { it == partNumber }
                .filterNot { it == supplierId }
                .filterNot { it == date }
                .filterNot { it == barcodeLike }
                .filterNot { it == salesOrder }
                .filterNot { it == qty }
                .firstOrNull()
        }

        // 4) fallback：SalesOrder 兜底（排除 partNumber / date / supplierId）
        if (salesOrder == null) {
            val excluded = setOfNotNull(partNumber, supplierId, date)
            salesOrder = lines
                .mapNotNull { Regex("\\d{5,12}").find(it)?.value } // ✅ 允许 5 位
                .firstOrNull { it !in excluded && it != qty }
        }

        // 5) fallback：Qty 兜底
        if (qty == null) {
            qty = pickBestQtyFallback(lines, salesOrder, weight, partNumber, supplierId, date)
        }

        return ScanResult(
            stockCode = stockCode,
            salesOrder = salesOrder,
            qty = qty,
            po = po,
            weight = weight
        )
    }

    // ===============================
    // 标签识别
    // ===============================

    private fun isSalesOrderLabel(line: String): Boolean {
        return line.contains("sales") && line.contains("order")
    }

    private fun isPoLabel(noSpace: String): Boolean {
        // po / p0 / p o
        return noSpace.startsWith("po") || noSpace.startsWith("p0")
    }

    private fun isWeightLabel(line: String): Boolean {
        // weight / we1ght / wéight / ight:
        if (line.contains("weight")) return true
        if (line.contains("we1ght")) return true
        if (line.contains("ight") && line.contains(":")) return true
        return false
    }

    private fun isQtyLabel(noSpaceNorm: String, raw: String): Boolean {
        val n = normalize(raw).replace(" ", "")

        // 匹配：Qty/Oty/aty/dty/zty/0ty/åty + optional :
        if (n.matches(Regex("^.?ty:?$"))) return true

        // qtv / qly / q1y / qu 等
        if (n.matches(Regex("^q.{0,2}:?$"))) {
            if (!n.contains("search") && !n.contains("query")) return true
        }

        if (n.contains("qty")) return true
        return false
    }

    private fun isStockCodeLabel(line: String): Boolean {
        // stockcode / stock code / stock-code / stock:
        val noSpace = line.replace(" ", "")
        if (noSpace.contains("stockcode")) return true
        if (line.contains("stock") && line.contains("code")) return true
        return false
    }

    private fun isPartNumberLabel(line: String): Boolean {
        val noSpace = line.replace(" ", "")
        return noSpace.contains("partnumber") || (line.contains("part") && line.contains("number"))
    }

    private fun isSupplierIdLabel(line: String): Boolean {
        val noSpace = line.replace(" ", "")
        return noSpace.contains("supplierid") || (line.contains("supplier") && line.contains("id"))
    }

    private fun isDateLabel(line: String): Boolean {
        return line.startsWith("date") || line.contains("date:")
    }

    // ===============================
    // 值提取：扫窗口 + token
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

    private fun extractAfterColonOrNearbyToken(
        lines: List<String>,
        index: Int,
        accept: (String) -> Boolean
    ): String? {
        extractAfterColon(lines[index])?.let { token ->
            val t = token.trim()
            if (accept(t)) return t
        }

        return findNearby(lines, index, window = 2) { token ->
            val t = token.trim()
            accept(t)
        }
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
    // StockCode token 规则（支持 F-19）
    // ===============================

    private fun looksLikeStockCodeToken(tokenRaw: String): Boolean {
        val t = tokenRaw.trim()

        // 允许字母数字 + 可选 - _
        if (!t.matches(Regex("^[A-Z0-9][A-Z0-9_-]{1,19}$", RegexOption.IGNORE_CASE))) return false

        // 必须至少含 1 字母
        if (!t.any { it.isLetter() }) return false

        // F-19 这种必须含数字（可选：如果你确实有全字母stockcode就把这行删掉）
        if (!t.any { it.isDigit() }) return false

        val lower = normalize(t)
        if (lower.contains("premium") || lower.contains("labelparser") || lower.contains("camera")) return false
        if (lower.contains("example.com") || lower.contains("describe")) return false

        return true
    }

    // 条码编号：像 03E45971 这种，经常不是 stockcode
    private fun looksLikeBarcodeId(raw: String): Boolean {
        val s = raw.trim()
        // 典型：2~3位数字 + 1字母 + 4~8位数字
        return s.matches(Regex("^\\d{2,3}[A-Z]\\d{4,8}$", RegexOption.IGNORE_CASE))
    }

    // ===============================
    // Qty fallback（排除污染数字）
    // ===============================

    private fun pickBestQtyFallback(
        lines: List<String>,
        salesOrder: String?,
        weight: String?,
        partNumber: String?,
        supplierId: String?,
        date: String?
    ): String? {

        val excluded = setOfNotNull(salesOrder, weight, partNumber, supplierId, date)

        val candidates = mutableListOf<Int>()
        for (l in lines) {
            tokenize(l).forEach { t ->
                if (t in excluded) return@forEach
                val n = t.toIntOrNull()
                if (n != null && n in 1..50000) candidates.add(n)
            }
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0].toString()

        return candidates
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.toString()
    }

    // ===============================
    // PO 修正：0/O 混淆
    // ===============================

    private fun fixPo(poRaw: String): String {
        // 常见：GRO024 被 OCR 成 GR0024（O->0）
        // 简单规则：前缀字母段里把 0 改成 O
        val up = poRaw.trim().uppercase()
        val prefix = up.takeWhile { !it.isDigit() }.replace('0', 'O')
        val suffix = up.dropWhile { !it.isDigit() }
        return prefix + suffix
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
