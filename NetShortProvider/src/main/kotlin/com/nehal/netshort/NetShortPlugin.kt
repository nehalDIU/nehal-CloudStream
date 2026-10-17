package com.nehal.netshort

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetShortPlugin : Plugin() {
    override fun load(context: Context) {
        NetShortStorage.init(context.applicationContext)
        registerMainAPI(NetShortProvider())
        registerExtractorAPI(VolcEngineExtractor())

        openSettings = { ctx ->
            showSettings(ctx)
        }
    }

    private fun showSettings(context: Context) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("NetShort Provider Settings")

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        scrollView.addView(layout)

        val infoText = TextView(context).apply {
            text = "NetShort streams short drama series.\n\n" +
                    "• Guest access is automated (zero-config).\n" +
                    "• If you have a VIP or account Bearer token from the NetShort app, you can paste it below to unlock account benefits."
            textSize = 13f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(infoText)

        val tokenLabel = TextView(context).apply {
            text = "Custom Bearer Token (Optional):"
            textSize = 14f
        }
        layout.addView(tokenLabel)

        val tokenInput = EditText(context).apply {
            hint = "Paste Bearer token here..."
            setText(NetShortStorage.getCustomToken() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        layout.addView(tokenInput)

        val saveButton = Button(context).apply {
            text = "Save Token"
            setOnClickListener {
                val input = tokenInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    NetShortStorage.saveCustomToken(input)
                    Toast.makeText(context, "NetShort custom token saved!", Toast.LENGTH_SHORT).show()
                } else {
                    NetShortStorage.clearCustomToken()
                    Toast.makeText(context, "Custom token cleared (using guest mode)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        layout.addView(saveButton)

        val clearButton = Button(context).apply {
            text = "Clear & Reset to Guest"
            setOnClickListener {
                NetShortStorage.clearCustomToken()
                tokenInput.setText("")
                Toast.makeText(context, "Reset to automated guest mode", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(clearButton)

        builder.setView(scrollView)
        builder.setPositiveButton("Close") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }
}
