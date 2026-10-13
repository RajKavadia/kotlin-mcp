package com.example.counterproof

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val vm: CounterViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val countText = view.findViewById<TextView>(R.id.count_text)
        val refresh = { countText.text = getString(R.string.count_label, vm.count) }
        refresh()

        view.findViewById<Button>(R.id.increment_button).setOnClickListener {
            vm.increment()
            refresh()
        }
        view.findViewById<Button>(R.id.details_button).setOnClickListener {
            findNavController().navigate(
                R.id.action_home_to_details,
                bundleOf("itemId" to "42"),
            )
        }
        view.findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // A tool-side write lands while we are away; repaint on return so the
        // screen, not just memory, shows the proof value.
        view?.findViewById<TextView>(R.id.count_text)?.text =
            getString(R.string.count_label, vm.count)
    }
}
