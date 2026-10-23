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
        registerMainAPI(ToonBoxProvider())
        registerExtractorAPI(VolcEngineExtractor())

        openSettings = { ctx ->
            showSettings(ctx)
        }
    }

    private fun showSettings(context: Context) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Reel Providers Settings")

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        scrollView.addView(layout)

        // --- NetShort Section ---
        val netShortHeader = TextView(context).apply {
            text = "=== NetShort Settings ==="
            textSize = 16f
            setPadding(0, 10, 0, 10)
        }
        layout.addView(netShortHeader)

        val infoText = TextView(context).apply {
            text = "• Guest access is automated (zero-config).\n" +
                    "• Paste a custom VIP / Bearer token below if needed."
            textSize = 12f
            setPadding(0, 0, 0, 10)
        }
        layout.addView(infoText)

        val netShortInput = EditText(context).apply {
            hint = "NetShort Bearer token..."
            setText(NetShortStorage.getCustomToken() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        layout.addView(netShortInput)

        val netShortSaveBtn = Button(context).apply {
            text = "Save NetShort Token"
            setOnClickListener {
                val input = netShortInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    NetShortStorage.saveCustomToken(input)
                    Toast.makeText(context, "NetShort token saved!", Toast.LENGTH_SHORT).show()
                } else {
                    NetShortStorage.clearCustomToken()
                    Toast.makeText(context, "NetShort token cleared (guest mode)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        layout.addView(netShortSaveBtn)

        val netShortClearBtn = Button(context).apply {
            text = "Reset NetShort to Guest"
            setOnClickListener {
                NetShortStorage.clearCustomToken()
                netShortInput.setText("")
                Toast.makeText(context, "NetShort reset to guest mode", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(netShortClearBtn)

        // --- ToonBox Section ---
        val toonBoxHeader = TextView(context).apply {
            text = "\n=== ToonBox Settings ==="
            textSize = 16f
            setPadding(0, 10, 0, 10)
        }
        layout.addView(toonBoxHeader)

        val toonBoxInfo = TextView(context).apply {
            text = "ToonBox streams vertical dramas & anime reels.\n" +
                    "• The API requires client signatures or authorized tokens.\n" +
                    "• Paste your ToonBox Bearer token or authorization string below.\n" +
                    "• Optional custom headers can also be added (Header: Value per line)."
            textSize = 12f
            setPadding(0, 0, 0, 10)
        }
        layout.addView(toonBoxInfo)

        val toonBoxTokenLabel = TextView(context).apply {
            text = "ToonBox Bearer / Auth Token:"
            textSize = 13f
        }
        layout.addView(toonBoxTokenLabel)

        val toonBoxTokenInput = EditText(context).apply {
            hint = "Paste ToonBox token here..."
            setText(NetShortStorage.getToonBoxToken() ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        layout.addView(toonBoxTokenInput)

        val toonBoxHeadersLabel = TextView(context).apply {
            text = "Custom Request Headers (one per line, e.g. X-Sign: ...):"
            textSize = 13f
            setPadding(0, 10, 0, 0)
        }
        layout.addView(toonBoxHeadersLabel)

        val existingHeaders = NetShortStorage.getToonBoxCustomHeaders().entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val toonBoxHeadersInput = EditText(context).apply {
            hint = "Key: Value (one per line)"
            setText(existingHeaders)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        layout.addView(toonBoxHeadersInput)

        val toonBoxSaveBtn = Button(context).apply {
            text = "Save ToonBox Config"
            setOnClickListener {
                val token = toonBoxTokenInput.text.toString().trim()
                if (token.isNotEmpty()) {
                    NetShortStorage.saveToonBoxToken(token)
                } else {
                    NetShortStorage.clearToonBoxToken()
                }

                val headers = toonBoxHeadersInput.text.toString().trim()
                NetShortStorage.saveToonBoxCustomHeaders(headers)
                Toast.makeText(context, "ToonBox settings saved!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(toonBoxSaveBtn)

        val toonBoxClearBtn = Button(context).apply {
            text = "Clear ToonBox Config"
            setOnClickListener {
                NetShortStorage.clearToonBoxToken()
                NetShortStorage.saveToonBoxCustomHeaders("")
                toonBoxTokenInput.setText("")
                toonBoxHeadersInput.setText("")
                Toast.makeText(context, "ToonBox settings cleared", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(toonBoxClearBtn)

        builder.setView(scrollView)
        builder.setPositiveButton("Close") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }
}
