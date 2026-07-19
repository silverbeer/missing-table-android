package com.missingtable.scorer.domain

/**
 * Formation presets: ordered position slots, GK first then back line → front
 * line. Codes are free-form strings on the backend (max 10 chars) and match
 * the PWA's position vocabulary.
 */
object Formations {
    val presets: Map<String, List<String>> = linkedMapOf(
        "4-3-3" to listOf("GK", "LB", "LCB", "RCB", "RB", "LCM", "CDM", "RCM", "LW", "ST", "RW"),
        "4-4-2" to listOf("GK", "LB", "LCB", "RCB", "RB", "LM", "LCM", "RCM", "RM", "LS", "RS"),
        "4-2-3-1" to listOf("GK", "LB", "LCB", "RCB", "RB", "LDM", "RDM", "LAM", "CAM", "RAM", "ST"),
        "3-5-2" to listOf("GK", "LCB", "CB", "RCB", "LWB", "LCM", "CDM", "RCM", "RWB", "LS", "RS"),
        "3-4-3" to listOf("GK", "LCB", "CB", "RCB", "LM", "LCM", "RCM", "RM", "LW", "ST", "RW"),
    )
}
