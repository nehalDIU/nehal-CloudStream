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

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val cookieLabel = TextView(context).apply {
            text = "WordPress Session Cookie (wordpress_logged_in_*):"
            textSize = 14f
        }
        layout.addView(cookieLabel)

        val cookieInput = EditText(context).apply {
            hint = "Paste cookie here"
            setText(MojaLossStorage.getCookie() ?: "")
            textSize = 13f
        }
        layout.addView(cookieInput)

        val divider = TextView(context).apply {
            text = "\n--- OR Login With Credentials ---"
            textSize = 13f
        }
        layout.addView(divider)

        val (savedUser, savedPass) = MojaLossStorage.getCredentials()

        val userInput = EditText(context).apply {
            hint = "Username or Email"
            setText(savedUser ?: "")
            textSize = 13f
        }
        layout.addView(userInput)

        val passInput = EditText(context).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(savedPass ?: "")
            textSize = 13f
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
                            Toast.makeText(context, "Login failed. Check credentials.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        layout.addView(loginBtn)

        builder.setView(layout)

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
