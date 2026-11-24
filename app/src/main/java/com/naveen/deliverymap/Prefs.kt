package com.naveen.deliverymap

import android.content.Context

object Prefs {
    const val DEFAULT_RAPIDO = "com.rapido.rider"
    const val DEFAULT_PORTER = "com.theporter.android.driverapp"

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    // delivery app package names
    fun rapido(c: Context): Set<String> = split(sp(c).getString("pkg_rapido", DEFAULT_RAPIDO))
    fun porter(c: Context): Set<String> = split(sp(c).getString("pkg_porter", DEFAULT_PORTER))
    fun rapidoRaw(c: Context): String = sp(c).getString("pkg_rapido", DEFAULT_RAPIDO) ?: DEFAULT_RAPIDO
    fun porterRaw(c: Context): String = sp(c).getString("pkg_porter", DEFAULT_PORTER) ?: DEFAULT_PORTER

    fun save(c: Context, rapido: String, porter: String) {
        sp(c).edit().putString("pkg_rapido", rapido.trim()).putString("pkg_porter", porter.trim()).apply()
    }

    // main ON / OFF
    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    // speed mode: "ultra" | "fast" | "normal"
    fun mode(c: Context): String = sp(c).getString("mode", "fast") ?: "fast"
    fun setMode(c: Context, v: String) = sp(c).edit().putString("mode", v).apply()

    // gap between delivery popup and map, in inches
    fun gapInch(c: Context): Float = sp(c).getFloat("gap", 1.0f)
    fun setGapInch(c: Context, v: Float) = sp(c).edit().putFloat("gap", v).apply()

    private fun split(s: String?): Set<String> =
        (s ?: "").split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
