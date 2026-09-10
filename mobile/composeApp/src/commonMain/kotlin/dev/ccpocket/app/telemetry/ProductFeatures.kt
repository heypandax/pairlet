package dev.ccpocket.app.telemetry

/** Eligible-install denominator: first actual presentation of each feature/mode per process.
 * Recomposition and lease heartbeats never multiply exposure. Reuse is derived in GA4 by install
 * and observation window from feature_used/value_reached, not guessed from an in-memory counter. */
internal object ProductFeatures {
    private val exposed = mutableSetOf<Pair<ProductFeature, String>>()
    fun expose(feature: ProductFeature, dimensions: Map<TelKey, Any>) {
        val mode = dimensions[TelKey.UsageMode] as? String ?: "unknown"
        if (exposed.add(feature to mode)) Telemetry.track(TelEvent.FeatureExposed,
            dimensions + mapOf(TelKey.Feature to feature.name.lowercase()))
    }
    fun used(feature: ProductFeature, dimensions: Map<TelKey, Any>) {
        Telemetry.track(TelEvent.FeatureUsed, dimensions + mapOf(TelKey.Feature to feature.name.lowercase()))
    }
}
