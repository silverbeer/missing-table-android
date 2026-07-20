package com.missingtable.scorer.domain

/**
 * Formation presets with pitch coordinates. x/y are fractions of the pitch
 * box (0,0 = top-left; own goal at the bottom, attacking toward the top),
 * mirroring the PWA's FormationField layout. Codes are free-form strings on
 * the backend (max 10 chars).
 */
object Formations {

    data class Slot(val code: String, val x: Float, val y: Float)

    val presets: Map<String, List<Slot>> = linkedMapOf(
        "4-3-3" to listOf(
            Slot("GK", 0.50f, 0.92f),
            Slot("LB", 0.14f, 0.74f), Slot("LCB", 0.38f, 0.79f),
            Slot("RCB", 0.62f, 0.79f), Slot("RB", 0.86f, 0.74f),
            Slot("LCM", 0.28f, 0.52f), Slot("CDM", 0.50f, 0.62f), Slot("RCM", 0.72f, 0.52f),
            Slot("LW", 0.15f, 0.30f), Slot("ST", 0.50f, 0.22f), Slot("RW", 0.85f, 0.30f),
        ),
        "4-4-2" to listOf(
            Slot("GK", 0.50f, 0.92f),
            Slot("LB", 0.14f, 0.74f), Slot("LCB", 0.38f, 0.79f),
            Slot("RCB", 0.62f, 0.79f), Slot("RB", 0.86f, 0.74f),
            Slot("LM", 0.12f, 0.50f), Slot("LCM", 0.38f, 0.55f),
            Slot("RCM", 0.62f, 0.55f), Slot("RM", 0.88f, 0.50f),
            Slot("LS", 0.38f, 0.25f), Slot("RS", 0.62f, 0.25f),
        ),
        "4-2-3-1" to listOf(
            Slot("GK", 0.50f, 0.92f),
            Slot("LB", 0.14f, 0.74f), Slot("LCB", 0.38f, 0.79f),
            Slot("RCB", 0.62f, 0.79f), Slot("RB", 0.86f, 0.74f),
            Slot("LDM", 0.38f, 0.62f), Slot("RDM", 0.62f, 0.62f),
            Slot("LAM", 0.18f, 0.42f), Slot("CAM", 0.50f, 0.45f), Slot("RAM", 0.82f, 0.42f),
            Slot("ST", 0.50f, 0.22f),
        ),
        "3-5-2" to listOf(
            Slot("GK", 0.50f, 0.92f),
            Slot("LCB", 0.28f, 0.79f), Slot("CB", 0.50f, 0.82f), Slot("RCB", 0.72f, 0.79f),
            Slot("LWB", 0.10f, 0.54f), Slot("LCM", 0.32f, 0.57f), Slot("CDM", 0.50f, 0.65f),
            Slot("RCM", 0.68f, 0.57f), Slot("RWB", 0.90f, 0.54f),
            Slot("LS", 0.38f, 0.25f), Slot("RS", 0.62f, 0.25f),
        ),
        "3-4-3" to listOf(
            Slot("GK", 0.50f, 0.92f),
            Slot("LCB", 0.28f, 0.79f), Slot("CB", 0.50f, 0.82f), Slot("RCB", 0.72f, 0.79f),
            Slot("LM", 0.12f, 0.52f), Slot("LCM", 0.38f, 0.56f),
            Slot("RCM", 0.62f, 0.56f), Slot("RM", 0.88f, 0.52f),
            Slot("LW", 0.18f, 0.29f), Slot("ST", 0.50f, 0.22f), Slot("RW", 0.82f, 0.29f),
        ),
    )
}
