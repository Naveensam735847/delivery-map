package com.naveen.deliverymap

import org.json.JSONObject

object Router {
    /** Real road route through the points (OSRM public server, free). Null if it fails. */
    suspend fun route(points: List<LatLng>): List<LatLng>? {
        if (points.size < 2) return null
        val coords = points.joinToString(";") { "${it.lng},${it.lat}" }
        val body = Net.get(
            "https://router.project-osrm.org/route/v1/driving/$coords?overview=simplified&geometries=geojson",
            8000
        ) ?: return null
        return try {
            val routes = JSONObject(body).getJSONArray("routes")
            val g = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            val out = ArrayList<LatLng>(g.length())
            for (i in 0 until g.length()) {
                val c = g.getJSONArray(i)
                out.add(LatLng(c.getDouble(1), c.getDouble(0)))
            }
            if (out.size >= 2) out else null
        } catch (e: Exception) {
            null
        }
    }
}
