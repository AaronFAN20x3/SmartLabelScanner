package com.example.myapplication.parser

data class ScanResult(
    val stockCode: String? = null,
    val salesOrder: String? = null,
    val qty: String? = null,
    val po: String? = null,
    val weight: String? = null
)
