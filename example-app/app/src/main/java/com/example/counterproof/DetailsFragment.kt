package com.example.counterproof

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

class DetailsFragment : Fragment(R.layout.fragment_details) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val itemId = arguments?.getString("itemId") ?: "0"
        view.findViewById<TextView>(R.id.details_text).text =
            getString(R.string.details_label, itemId)
    }
}
