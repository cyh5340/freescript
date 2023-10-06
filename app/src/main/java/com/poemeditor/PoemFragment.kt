package com.poemeditor

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.poemeditor.databinding.FragmentPoemBinding

class PoemFragment : Fragment() {
    private var _binding: FragmentPoemBinding? = null
    private val binding
        get() = checkNotNull(_binding) {
            "Cannot access binding because it is null. Is the view visible?"
        }

    private lateinit var sharedPreferences: SharedPreferences

    private val TEXT_KEY = "savedText"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentPoemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Initialize SharedPreferences
        sharedPreferences =
            requireContext().getSharedPreferences(
                "PoemEditorPrefs", Context.MODE_PRIVATE
            )

        // Restore text from SharedPreferences when the fragment is created
        val savedText = sharedPreferences.getString(TEXT_KEY, "")
        binding.outlinedEditText.setText(savedText)

        // Set up a listener to automatically save text when it changes
        binding.outlinedEditText.addTextChangedListener {
            saveTextToStorage(it.toString())
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()

        // Save the text when the fragment is destroyed (e.g., when the user leaves the app)
        saveTextToStorage(binding.outlinedEditText.text.toString())
    }

    private fun saveTextToStorage(text: String) {
        val editor = sharedPreferences.edit()
        editor.putString(TEXT_KEY, text)
        editor.apply()
    }
}
