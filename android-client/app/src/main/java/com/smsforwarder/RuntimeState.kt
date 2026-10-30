package com.smsforwarder

import android.content.Context

/** État pilotable à distance : l'écoute peut être coupée depuis le serveur. */
object RuntimeState {

    private const val PREFS = "runtime"
    private const val KEY_LISTENING = "listening"

    @Volatile
    var listening: Boolean = true
    private set

    fun load(context: Context) {
        listening = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_LISTENING, true)
    }

    fun set(context: Context, enabled: Boolean) {
        listening = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_LISTENING, enabled).apply()
    }
}
