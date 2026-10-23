package com.MovieBox

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SectionManagerFragment : BottomSheetDialogFragment() {

    companion object {
        const val PREF_ORDER_KEY = "moviebox_section_order"
        const val PREF_HIDDEN_KEY = "moviebox_section_hidden"

        val defaultSections = listOf(
            "live|matches" to "⚽ Live Football & Sports",
            "live|highlights" to "Football Match Highlights",
            "r|0|9167640870324258216" to "Trending Movies",
            "r|0|5692654647815587592" to "In Cinema",
            "r|0|414907768299210008" to "Bollywood",
            "r|0|3859721901924910512" to "South Indian",
            "r|0|8019599703232971616" to "Hollywood",
            "r|0|1488104699998914056" to "New Release",
            "r|0|6027735606879570952" to "New Punjabi",
            "r|0|6144409817256968824" to "New Bengali",
            "r|5|719331337777440448" to "Top Series",
            "r|5|4903182713986896328" to "Indian Drama",
            "r|5|1255898847918934600" to "Reality TV",
            "r|5|1976033493293449744" to "Asian Drama",
            "r|5|3910636007619709856" to "Western TV",
            "r|5|5177200225164885656" to "Turkish Drama",
            "1|1" to "Movies",
            "1|2" to "Series",
            "1|1006" to "Anime",
            "2|2;country=Japan;genre=Animation" to "Anime (Series)",
            "1|1;country=India" to "Indian (Movies)",
            "1|2;country=India" to "Indian (Series)",
            "1|1;classify=Hindi dub;country=United States" to "USA (Movies)",
            "1|2;classify=Hindi dub;country=United States" to "USA (Series)",
            "1|1;country=Japan" to "Japan (Movies)",
            "1|2;country=Japan" to "Japan (Series)",
            "1|1;country=China" to "China (Movies)",
            "1|2;country=China" to "China (Series)",
            "1|1;country=Philippines" to "Philippines (Movies)",
            "1|2;country=Philippines" to "Philippines (Series)",
            "1|1;country=Thailand" to "Thailand (Movies)",
            "1|2;country=Thailand" to "Thailand (Series)",
            "1|1;country=Nigeria" to "Nollywood (Movies)",
            "1|2;country=Nigeria" to "Nollywood (Series)",
            "1|1;country=Korea" to "South Korean (Movies)",
            "1|2;country=Korea" to "South Korean (Series)",
            "1|1;classify=Hindi dub;genre=Action" to "Action (Movies)",
            "1|1;classify=Hindi dub;genre=Crime" to "Crime (Movies)",
            "1|1;classify=Hindi dub;genre=Comedy" to "Comedy (Movies)",
            "1|2;classify=Hindi dub;genre=Crime" to "Crime (Series)",
            "1|2;classify=Hindi dub;genre=Comedy" to "Comedy (Series)"
        )
    }

    private var currentOrder: MutableList<Pair<String, String>> = mutableListOf()
    private val hiddenSections: MutableSet<String> = mutableSetOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        loadPreferences(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))

            val titleBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(ctx).apply {
                    text = "MOVIEBOX"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                    isAllCaps = true
                })
                addView(TextView(ctx).apply {
                    text = "Manage Sections"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
            }
            addView(titleBox)

            val resetBtn = TextView(ctx).apply {
                text = "Reset"
                textSize = 14f
                setTextColor(Color.parseColor("#ff4d4d"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle("Reset to Default")
                        .setMessage("Restore default order and show all sections?")
                        .setPositiveButton("Reset") { _, _ ->
                            resetPreferences(ctx)
                            dismissAllowingStateLoss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            addView(resetBtn)
        }
        root.addView(header)

        val hint = TextView(ctx).apply {
            text = "  Uncheck to hide  ·  ▲▼ to reorder"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(hint)

        // Scroll list
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val itemsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(itemsContainer)
        root.addView(scrollView)

        fun refreshList() {
            itemsContainer.removeAllViews()
            for (idx in currentOrder.indices) {
                val item = currentOrder[idx]
                val key = item.first
                val label = item.second

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(if (idx % 2 == 0) Color.parseColor("#1a1a1a") else Color.parseColor("#222222"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(2), 0, dp(2)) }
                }

                val cb = CheckBox(ctx).apply {
                    isChecked = !hiddenSections.contains(key)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) hiddenSections.remove(key)
                        else hiddenSections.add(key)
                    }
                }
                row.addView(cb)

                val labelTv = TextView(ctx).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(labelTv)

                val upBtn = TextView(ctx).apply {
                    text = "▲"
                    textSize = 16f
                    setTextColor(if (idx > 0) Color.WHITE else Color.parseColor("#444444"))
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    isClickable = idx > 0
                    if (idx > 0) {
                        setOnClickListener {
                            val temp = currentOrder[idx]
                            currentOrder[idx] = currentOrder[idx - 1]
                            currentOrder[idx - 1] = temp
                            refreshList()
                        }
                    }
                }
                row.addView(upBtn)

                val downBtn = TextView(ctx).apply {
                    text = "▼"
                    textSize = 16f
                    setTextColor(if (idx < currentOrder.size - 1) Color.WHITE else Color.parseColor("#444444"))
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    isClickable = idx < currentOrder.size - 1
                    if (idx < currentOrder.size - 1) {
                        setOnClickListener {
                            val temp = currentOrder[idx]
                            currentOrder[idx] = currentOrder[idx + 1]
                            currentOrder[idx + 1] = temp
                            refreshList()
                        }
                    }
                }
                row.addView(downBtn)

                itemsContainer.addView(row)
            }
        }
        refreshList()

        // Bottom Actions
        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)

            val cancelBtn = TextView(ctx).apply {
                text = "Cancel"
                textSize = 15f
                setTextColor(Color.parseColor("#aaaaaa"))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                setOnClickListener { dismissAllowingStateLoss() }
            }
            addView(cancelBtn)

            val saveBtn = TextView(ctx).apply {
                text = "Save"
                textSize = 15f
                setTextColor(Color.parseColor("#4d88ff"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                setOnClickListener {
                    savePreferences(ctx)
                    Toast.makeText(ctx, "Saved! Pull-to-refresh the home page to apply.", Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                }
            }
            addView(saveBtn)
        }
        root.addView(bottomBar)

        return root
    }

    private fun dp(dp: Int): Int = (dp * (context?.resources?.displayMetrics?.density ?: 2f)).toInt()

    private fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val savedOrderStr = prefs.getString(PREF_ORDER_KEY, null)
        val savedHiddenStr = prefs.getString(PREF_HIDDEN_KEY, null)

        val map = defaultSections.toMap()
        currentOrder = if (!savedOrderStr.isNullOrBlank()) {
            val list = savedOrderStr.split(",").filter { it.isNotBlank() }
            val loaded = list.mapNotNull { key -> map[key]?.let { key to it } }.toMutableList()
            for (def in defaultSections) {
                if (loaded.none { it.first == def.first }) loaded.add(def)
            }
            loaded
        } else {
            defaultSections.toMutableList()
        }

        hiddenSections.clear()
        if (!savedHiddenStr.isNullOrBlank()) {
            hiddenSections.addAll(savedHiddenStr.split(",").filter { it.isNotBlank() })
        }
    }

    private fun savePreferences(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_ORDER_KEY, currentOrder.joinToString(",") { it.first })
            .putString(PREF_HIDDEN_KEY, hiddenSections.joinToString(","))
            .apply()
    }

    private fun resetPreferences(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PREF_ORDER_KEY)
            .remove(PREF_HIDDEN_KEY)
            .apply()
    }
}
