package com.nehal.mojaloss

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class MojaLossPlugin : Plugin() {
    override fun load(context: Context) {
        MojaLossStorage.init(context.applicationContext)
        registerMainAPI(MojaLossProvider())

        openSettings = { ctx ->
            showSettings(ctx)
        }
    }

    private fun showSettings(context: Context) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("MojaLoss Settings")

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        scrollView.addView(layout)

        val infoText = TextView(context).apply {
            text = "MojaLoss requires an active session cookie to stream.\n\nHow to get your cookie:\n1. Log in to mojaloss.stream in your browser.\n2. Copy your 'wordpress_logged_in_*' cookie (or full Cookie header) using DevTools or Cookie-Editor extension.\n3. Paste it below and tap 'Verify & Save'."
            textSize = 12f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(infoText)

        val cookieLabel = TextView(context).apply {
            text = "Session Cookie (wordpress_logged_in_*):"
            textSize = 13f
        }
        layout.addView(cookieLabel)

        val cookieInput = EditText(context).apply {
            hint = "Paste wordpress_logged_in_... cookie here"
            setText(MojaLossStorage.getCookie() ?: "")
            textSize = 12f
            maxLines = 4
        }
        layout.addView(cookieInput)

        val verifyBtn = Button(context).apply {
            text = "Verify & Save Cookie"
            setOnClickListener {
                val c = cookieInput.text.toString().trim()
                if (c.isEmpty()) {
                    Toast.makeText(context, "Please paste your session cookie first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                text = "Verifying..."
                CoroutineScope(Dispatchers.IO).launch {
                    val isValid = MojaLossStorage.verifySession(c)
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        text = "Verify & Save Cookie"
                        MojaLossStorage.saveCookie(c)
                        if (isValid) {
                            Toast.makeText(context, "Success! Session verified and saved.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Saved, but verification failed (cookie may be expired or incomplete).", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        layout.addView(verifyBtn)

        val divider = TextView(context).apply {
            text = "\n--- Direct Account Login (Fallback) ---"
            textSize = 13f
        }
        layout.addView(divider)

        val (savedUser, savedPass) = MojaLossStorage.getCredentials()

        val userInput = EditText(context).apply {
            hint = "Username or Email"
            setText(savedUser ?: "")
            textSize = 12f
        }
        layout.addView(userInput)

        val passInput = EditText(context).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(savedPass ?: "")
            textSize = 12f
        }
        layout.addView(passInput)

        val loginBtn = Button(context).apply {
            text = "Login & Save Session"
            setOnClickListener {
                val u = userInput.text.toString().trim()
                val p = passInput.text.toString()
                if (u.isEmpty() || p.isEmpty()) {
                    Toast.makeText(context, "Please enter username and password", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                text = "Logging in..."
                CoroutineScope(Dispatchers.IO).launch {
                    val success = MojaLossStorage.login(u, p)
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        text = "Login & Save Session"
                        if (success) {
                            cookieInput.setText(MojaLossStorage.getCookie() ?: "")
                            Toast.makeText(context, "Login successful! Session saved.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Direct login blocked by site protection. Please copy and paste your session cookie above.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        layout.addView(loginBtn)

        builder.setView(scrollView)

        builder.setPositiveButton("Save") { dialog, _ ->
            val cookie = cookieInput.text.toString().trim()
            if (cookie.isNotEmpty()) {
                MojaLossStorage.saveCookie(cookie)
            }
            val u = userInput.text.toString().trim()
            val p = passInput.text.toString()
            if (u.isNotEmpty() && p.isNotEmpty()) {
                MojaLossStorage.saveCredentials(u, p)
            }
            Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        builder.setNeutralButton("Clear / Logout") { dialog, _ ->
            MojaLossStorage.clearCookie()
            MojaLossStorage.clearCredentials()
            cookieInput.setText("")
            userInput.setText("")
            passInput.setText("")
            Toast.makeText(context, "Session cleared", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }
}
