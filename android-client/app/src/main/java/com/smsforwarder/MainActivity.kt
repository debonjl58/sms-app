package com.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.smsforwarder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SMS_PERMISSION_CODE = 100

        /**
         * On ne demande QUE des permissions dangereuses. WRITE_SMS, INTERNET,
         * ACCESS_NETWORK_STATE sont de protection "normale" : accordees a
         * l'installation des qu'elles sont declarees dans le manifeste.
         */
        private val REQUIRED_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        private const val CORE_PERMISSION = Manifest.permission.RECEIVE_SMS
    }

    private lateinit var webSocketManager: WebSocketManager
        private lateinit var binding: ActivityMainBinding
            private var serviceStarted = false

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                try {
                    binding = ActivityMainBinding.inflate(layoutInflater)
                    setContentView(binding.root)

                    webSocketManager = WebSocketManager.getInstance(applicationContext)

                    binding.btnConnect.isEnabled = false
                    binding.btnCreateAccount.isEnabled = false
                    updateStatus(false)

                    webSocketManager.onConnectionChange = { connected ->
                        runOnUiThread { updateStatus(connected) }
                    }

                    checkPermissions()
                } catch (t: Throwable) {
                    // Filet : on n'echoue plus en silence, le stacktrace s'affiche a l'ecran.
                    Log.e(TAG, "Crash au demarrage", t)
                    CrashLog.write(this, t)
                    showCrashScreen(t)
                }
            }

            private fun showCrashScreen(t: Throwable) {
                try {
                    Toast.makeText(
                        this,
                        "CRASH: ${t.javaClass.simpleName}: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()

                    val textView = TextView(this).apply {
                        text = Log.getStackTraceString(t)
                        textSize = 9f
                        setPadding(24, 24, 24, 24)
                        setTextIsSelectable(true)
                    }
                    val scroll = ScrollView(this)
                    scroll.addView(textView)
                    setContentView(scroll)
                } catch (ignored: Throwable) {
                    Log.e(TAG, "Affichage du crash impossible", ignored)
                }
            }

            override fun onResume() {
                super.onResume()
                if (hasCorePermission()) initializeApp()
            }

            private fun updateStatus(connected: Boolean) {
                if (!::binding.isInitialized) return
                    binding.statusText.text =
                    if (connected) "Connecté — en attente de SMS"
                        else "Attendez que l'application se mette à jour"
            }

            private fun hasCorePermission(): Boolean =
                ContextCompat.checkSelfPermission(this, CORE_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED

                private fun checkPermissions() {
                    val missing = REQUIRED_PERMISSIONS.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }

                    if (missing.isNotEmpty()) {
                        ActivityCompat.requestPermissions(this, missing.toTypedArray(), SMS_PERMISSION_CODE)
                    } else {
                        initializeApp()
                    }
                }

                override fun onRequestPermissionsResult(
                    requestCode: Int,
                    permissions: Array<out String>,
                    grantResults: IntArray
                ) {
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
                    if (requestCode != SMS_PERMISSION_CODE) return

                        if (hasCorePermission()) {
                            initializeApp()
                        } else {
                            updateStatus(false)
                            Toast.makeText(
                                this,
                                "Permission SMS nécessaire pour transférer les messages",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }

                private fun initializeApp() {
                    // CORRECTION : on ne regarde plus serviceStarted, qui n'a pas de sens ici.
                    if (SmsForwarderService.isRunning()) return

                        try {
                            val serviceIntent = Intent(this, SmsForwarderService::class.java)
                            ContextCompat.startForegroundService(this, serviceIntent)
                            serviceStarted = true
                            Log.d(TAG, "Service de transfert lancé")
                        } catch (e: Throwable) {
                            serviceStarted = false
                            Log.e(TAG, "Impossible de lancer le service", e)
                            CrashLog.write(this, e)
                        }
                }

                override fun onDestroy() {
                    // CORRECTION : guard lateinit, sinon on masque le vrai crash par un
                    // UninitializedPropertyAccessException.
                    if (::webSocketManager.isInitialized) {
                        webSocketManager.onConnectionChange = null
                    }
                    super.onDestroy()
                }
}
