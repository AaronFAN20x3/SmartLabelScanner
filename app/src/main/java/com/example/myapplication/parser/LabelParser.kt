package com.example.myapplication.parser

class LabelParser {

    fun parse(text: String): ScanResult {

        val lines = text.lines().map { it.trim() }

        var stockCode: String? = null
        var salesOrder: String? = null
        var qty: String? = null
        var po: String? = null
        var weight: String? = null

        for (i in lines.indices) {

            val line = lines[i]

            val normalized = line.lowercase()

            // PO
            if (normalized.startsWith("po")) {
                po = line.substringAfter(":").trim()
            }

            // Sales Order
            if (normalized.contains("sales")) {
                salesOrder = line.substringAfter(":").trim()
            }

            // Qty 容错（处理 Ôty / Oty / Qty）
            if (
                normalized.contains("qty") ||
                normalized.contains("oty") ||
                normalized.contains("ôty")
            ) {
                qty = lines.getOrNull(i + 1)?.trim()
            }

            // Weight
            if (normalized.startsWith("weight")) {
                weight = line.substringAfter(":").trim()
            }

            // Stock Code
            if (
                line.matches(Regex("^[A-Za-z0-9]{6,12}$")) &&
                line.any { it.isLetter() } &&
                line.any { it.isDigit() }
            ) {
                stockCode = line
            }

            // 如果有纯数字 3~5 位，并且 qty 还没识别到
            if (
                qty == null &&
                line.matches(Regex("^\\d{2,5}$"))
            ) {
                qty = line
            }
        }

        return ScanResult(
            stockCode = stockCode,
            salesOrder = salesOrder,
            qty = qty,
            po = po,
            weight = weight
        )
    }
}
