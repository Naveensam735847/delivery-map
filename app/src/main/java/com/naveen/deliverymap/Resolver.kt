package com.naveen.deliverymap

private data class Pick(val point: LatLng, val err: Double, val solid: Boolean)

enum class Fallback { ALWAYS, ONLY_IF_NONE, NEVER }

/** Turns a popup address into a map point, using the popup's own distances to pick the right match. */
object Resolver {
    private const val ROAD_FACTOR = 1.25

    suspend fun resolve(
        leg: Leg,
        ref: LatLng,
        distRefs: List<LatLng>,
        slackKm: Double = 0.0,
        maxQueries: Int = 4,
        timeoutMs: Int = 6000,
        fallback: Fallback = Fallback.ALWAYS
    ): Place? {
        // 1) Plus Code in the text = exact and instant
        PlusCode.find(leg.address)?.let { code ->
            PlusCode.resolve(code, ref.lat, ref.lng)?.let { return Place(it, true) }
        }
        // 2) Geocode the address text
        val queries = AddressQueries.build(leg.title, leg.address, maxQueries)
        if (queries.isEmpty()) return null

        var best = pick(Geocoder.photonAll(queries, ref, timeoutMs), leg.km, distRefs, slackKm)
        val tryNominatim = when (fallback) {
            Fallback.ALWAYS -> best == null || !best.solid
            Fallback.ONLY_IF_NONE -> best == null
            Fallback.NEVER -> false
        }
        if (tryNominatim) {
            val nb = pick(Geocoder.nominatimFirst(queries, ref, timeoutMs), leg.km, distRefs, slackKm)
            if (nb != null && (best == null || nb.err < best.err)) best = nb
        }
        return best?.let { Place(it.point, it.solid) }
    }

    private fun pick(cands: List<Cand>, km: Double?, distRefs: List<LatLng>, slackKm: Double): Pick? {
        if (cands.isEmpty()) return null
        if (km == null || distRefs.isEmpty()) {
            return Pick(cands.minByOrNull { it.qi }!!.p, 99.0, false)
        }
        var bestC: Cand? = null
        var bestErr = Double.MAX_VALUE
        for (c in cands) {
            val e = distRefs.minOf { Math.abs(Geo.km(it, c.p) * ROAD_FACTOR - km) } + c.qi * 0.05
            if (e < bestErr) { bestErr = e; bestC = c }
        }
        val limit = maxOf(0.6, 0.30 * km) + slackKm
        return Pick(bestC!!.p, bestErr, bestErr <= limit)
    }
}
