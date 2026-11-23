package com.naveen.deliverymap

import android.content.Context

interface AppReader {
    val name: String
    fun packages(c: Context): Set<String>
    /** texts = all visible texts of the popup window, in screen order. Returns null if this is not an order popup. */
    fun parse(texts: List<String>): Order?
}

object ReaderUtil {
    private val IGNORE = setOf("pickup", "drop", "pick up", "pick-up")

    fun toKm(value: String, unit: String): Double? {
        val v = value.toDoubleOrNull() ?: return null
        return if (unit.equals("m", true)) v / 1000.0 else v
    }

    /** Joins the texts right after index i until a stop text (next leg, Accept, price, Skip). */
    fun joinAfter(texts: List<String>, i: Int, stop: (String) -> Boolean): String {
        val out = ArrayList<String>()
        var j = i + 1
        while (j < texts.size && out.size < 4) {
            val t = texts[j].trim()
            if (t.isEmpty()) { j++; continue }
            if (stop(t)) break
            if (t.lowercase() !in IGNORE) out.add(t)
            j++
        }
        return out.joinToString(" ")
    }

    fun isStop(t: String): Boolean =
        t.contains("Accept", true) || t.contains("Skip", true) || t.startsWith("₹") || t.startsWith("+")
}

/** Porter: "Title (2.4 km)" then address line. */
object PorterReader : AppReader {
    override val name = "Porter"
    override fun packages(c: Context) = Prefs.porter(c)

    private val legRe = Regex("""^(.+?)\s*\((\d+(?:\.\d+)?)\s*(km|m)\)$""", RegexOption.IGNORE_CASE)

    override fun parse(texts: List<String>): Order? {
        val idx = texts.indices.filter { legRe.matches(texts[it].trim()) }
        if (idx.size < 2) return null
        fun leg(i: Int): Leg {
            val m = legRe.find(texts[i].trim())!!
            val addr = ReaderUtil.joinAfter(texts, i) { legRe.matches(it) || ReaderUtil.isStop(it) }
            return Leg(m.groupValues[1].trim(), addr, ReaderUtil.toKm(m.groupValues[2], m.groupValues[3]))
        }
        return Order(name, leg(idx[0]), leg(idx[1]))
    }
}

/** Rapido: "0.5 km" then "Area - full address". */
object RapidoReader : AppReader {
    override val name = "Rapido"
    override fun packages(c: Context) = Prefs.rapido(c)

    private val kmRe = Regex("""^(\d+(?:\.\d+)?)\s*(km|m)$""", RegexOption.IGNORE_CASE)

    override fun parse(texts: List<String>): Order? {
        val idx = texts.indices.filter { kmRe.matches(texts[it].trim()) }
        if (idx.size < 2) return null
        fun leg(i: Int): Leg {
            val m = kmRe.find(texts[i].trim())!!
            val combined = ReaderUtil.joinAfter(texts, i) { kmRe.matches(it) || ReaderUtil.isStop(it) }
            val parts = combined.split(" - ", " – ", limit = 2)
            val title = if (parts.size == 2) parts[0].trim() else ""
            val addr = if (parts.size == 2) parts[1].trim() else combined
            return Leg(title, addr, ReaderUtil.toKm(m.groupValues[1], m.groupValues[2]))
        }
        return Order(name, leg(idx[0]), leg(idx[1]))
    }
}
