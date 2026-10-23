package com.MovieBox

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsFragment(private val plugin: MovieBoxProviderPlugin) : BottomSheetDialogFragment() {

    private val hostList = listOf(
        "https://api3.aoneroom.com",
        "https://apig.inmoviebox.com",
        "https://api7.aoneroom.com",
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val ctx = requireContext()
        val res = plugin.resources ?: return null

        val layoutId = res.getIdentifier("fragment_moviebox_settings", "layout", "com.MovieBox")
        if (layoutId == 0) return null

        val view = runCatching {
            res.getLayout(layoutId)?.let { parser ->
                LayoutInflater.from(ctx).inflate(parser, container, false)
            }
        }.getOrNull() ?: inflater.inflate(layoutId, container, false)

        val prefs: SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        fun <T : View> find(name: String): T? {
            val id = res.getIdentifier(name, "id", "com.MovieBox")
            return if (id != 0) view?.findViewById(id) else null
        }

        val currentHost = prefs.getString("moviebox_host", hostList.first()) ?: hostList.first()

        val hostSubtitle: TextView? = find("hostSubtitle")
        hostSubtitle?.text = "Current: $currentHost"

        val hostRow: LinearLayout? = find("hostRow")
        hostRow?.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Select API Host")
                .setItems(hostList.toTypedArray()) { _, which ->
                    val selected = hostList[which]
                    prefs.edit().putString("moviebox_host", selected).apply()
                    hostSubtitle?.text = "Current: $selected"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val sectionsRow: LinearLayout? = find("sectionsRow")
        sectionsRow?.setOnClickListener {
            SectionManagerFragment().show(parentFragmentManager, "section_manager")
        }

        val saveContainer: LinearLayout? = find("saveContainer")
        saveContainer?.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismissAllowingStateLoss()
                }
                .setNegativeButton("No") { _, _ ->
                    dismissAllowingStateLoss()
                }
                .show()
        }

        return view
    }
}
