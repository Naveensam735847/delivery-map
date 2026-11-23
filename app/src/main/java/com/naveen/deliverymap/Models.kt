package com.naveen.deliverymap

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** One end of an order. km = distance printed in the popup (may be null). */
data class Leg(val title: String, val address: String, val km: Double?)

data class Order(val app: String, val pickup: Leg, val drop: Leg) {
    val key: String
        get() = (pickup.title + "|" + pickup.address + "|" + drop.title + "|" + drop.address).lowercase()
}

/** solid = confident, hollow dot on the map when false */
data class Place(val point: LatLng, val solid: Boolean)

object Geo {
    fun km(a: LatLng, b: LatLng): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
