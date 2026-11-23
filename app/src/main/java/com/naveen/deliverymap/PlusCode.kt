package com.naveen.deliverymap

import kotlin.math.pow

/** Minimal Open Location Code (Plus Code) support: find, recover short codes, decode. */
object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private val REGEX = Regex("\\b([23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{2,7})")

    fun find(text: String): String? = REGEX.find(text.uppercase())?.groupValues?.get(1)

    private fun prefix(refLat: Double, refLng: Double): String {
        val lat = refLat.coerceIn(-90.0, 89.999999) + 90.0
        val lng = ((refLng + 180.0) % 360.0 + 360.0) % 360.0
        val a = (lat / 20).toInt()
        val b = (lng / 20).toInt()
        val c = (lat - a * 20).toInt()
        val d = (lng - b * 20).toInt()
        return "${ALPHABET[a]}${ALPHABET[b]}${ALPHABET[c]}${ALPHABET[d]}"
    }

    private fun decode(full: String): LatLng? {
        val d = full.replace("+", "")
        if (d.length < 2) return null
        var lat = -90.0
        var lng = -180.0
        var res = 20.0
        var latRes = res
        var lngRes = res
        var i = 0
        while (i < minOf(10, d.length) - 1) {
            val a = ALPHABET.indexOf(d[i]); val b = ALPHABET.indexOf(d[i + 1])
            if (a < 0 || b < 0) return null
            lat += a * res; lng += b * res
            latRes = res; lngRes = res
            res /= 20.0
            i += 2
        }
        for (j in 10 until d.length) {
            val idx = ALPHABET.indexOf(d[j])
            if (idx < 0) return null
            latRes /= 5.0; lngRes /= 4.0
            lat += (idx / 4) * latRes
            lng += (idx % 4) * lngRes
        }
        return LatLng(lat + latRes / 2, lng + lngRes / 2)
    }

    /** Works for full codes and for short codes (needs a reference point close by). */
    fun resolve(code: String, refLat: Double, refLng: Double): LatLng? {
        val plus = code.indexOf('+')
        if (plus < 0) return null
        if (plus >= 8) return decode(code)
        if (plus % 2 != 0) return null
        val pad = 8 - plus
        val full = prefix(refLat, refLng).substring(0, pad) + code
        val c = decode(full) ?: return null
        val res = 20.0.pow((2 - pad / 2).toDouble())
        val half = res / 2
        var lat = c.lat
        var lng = c.lng
        if (refLat + half < lat && lat - res >= -90) lat -= res
        else if (refLat - half > lat && lat + res <= 90) lat += res
        if (refLng + half < lng) lng -= res
        else if (refLng - half > lng) lng += res
        return LatLng(lat, lng)
    }
}
