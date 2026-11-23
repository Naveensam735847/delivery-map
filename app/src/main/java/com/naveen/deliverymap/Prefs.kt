package com.naveen.deliverymap

import android.content.Context

/** Package names of the delivery apps. Editable in the app in case they differ on your phone. */
object Prefs {
    const val DEFAULT_RAPIDO = "com.rapido.rider"
    const val DEFAULT_PORTER = "com.theporter.android.driverapp"

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun rapido(c: Context): Set<String> = split(sp(c).getString("pkg_rapido", DEFAULT_RAPIDO))
    fun porter(c: Context): Set<String> = split(sp(c).getString("pkg_porter", DEFAULT_PORTER))
    fun rapidoRaw(c: Context): String = sp(c).getString("pkg_rapido", DEFAULT_RAPIDO) ?: DEFAULT_RAPIDO
    fun porterRaw(c: Context): String = sp(c).getString("pkg_porter", DEFAULT_PORTER) ?: DEFAULT_PORTER

    fun save(c: Context, rapido: String, porter: String) {
        sp(c).edit().putString("pkg_rapido", rapido.trim()).putString("pkg_porter", porter.trim()).apply()
    }

    private fun split(s: String?): Set<String> =
        (s ?: "").split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
