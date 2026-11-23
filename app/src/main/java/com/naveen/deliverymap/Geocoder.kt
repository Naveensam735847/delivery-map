package com.naveen.deliverymap

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A possible map point. qi = index of the query that found it (lower = more specific). */
data class Cand(val p: LatLng, val qi: Int)

object Geocoder {
    private val nominatimLock = Mutex()
    private var lastNominatim = 0L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Photon (free, OpenStreetMap data). All queries run in parallel. */
    suspend fun photonAll(queries: List<String>, ref: LatLng): List<Cand> = coroutineScope {
        queries.mapIndexed { qi, q ->
            async {
                val out = ArrayList<Cand>()
                val body = Net.get(
                    "https://photon.komoot.io/api/?q=${enc(q)}&limit=5&lat=${ref.lat}&lon=${ref.lng}"
                )
                if (body != null) {
                    try {
                        val feats = JSONObject(body).optJSONArray("features") ?: JSONArray()
                        for (i in 0 until feats.length()) {
                            val c = feats.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates")
                            out.add(Cand(LatLng(c.getDouble(1), c.getDouble(0)), qi))
                        }
                    } catch (_: Exception) {
                    }
                }
                out
            }
        }.awaitAll().flatten()
    }

    /** Nominatim (1 request per second rule). Returns candidates of the first query that has any. */
    suspend fun nominatimFirst(queries: List<String>, ref: LatLng): List<Cand> {
        for ((qi, q) in queries.take(3).withIndex()) {
            val body = nominatimLock.withLock {
                val wait = 1100 - (System.currentTimeMillis() - lastNominatim)
                if (wait > 0) delay(wait)
                val r = Net.get(
                    "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&countrycodes=in" +
                        "&viewbox=${ref.lng - 0.8},${ref.lat + 0.8},${ref.lng + 0.8},${ref.lat - 0.8}" +
                        "&q=${enc(q)}"
                )
                lastNominatim = System.currentTimeMillis()
                r
            } ?: continue
            try {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val out = ArrayList<Cand>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.add(Cand(LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()), qi))
                    }
                    return out
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }
}

object AddressQueries {
    fun build(title: String, address: String): List<String> {
        val truncated = address.contains("…") || address.trim().endsWith("...")
        var a = address.replace("…", "").replace("...", "")
        PlusCode.find(a)?.let { a = a.uppercase().replace(it, "") }
        val segs = a.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.equals("India", true) }.toMutableList()
        if (truncated && segs.size > 1) segs.removeAt(segs.size - 1)
        val noNum = if (segs.size > 1 && segs[0].any { it.isDigit() } && segs[0].length <= 10) segs.drop(1) else segs

        val list = ArrayList<String>()
        if (segs.isNotEmpty()) list.add(segs.joinToString(", "))
        if (noNum.size != segs.size && noNum.isNotEmpty()) list.add(noNum.joinToString(", "))
        if (noNum.size > 3) list.add(noNum.takeLast(4).joinToString(", "))
        if (title.isNotBlank()) list.add((title + " " + noNum.takeLast(3).joinToString(" ")).trim())
        return list.distinct().filter { it.isNotBlank() }.take(4)
    }
}
