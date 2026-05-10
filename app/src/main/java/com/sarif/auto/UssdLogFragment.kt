package com.sarif.auto

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.sarif.auto.databinding.FragmentUssdLogBinding

class UssdLogFragment : Fragment() {

    private var _binding: FragmentUssdLogBinding? = null
    private val binding get() = _binding!!

    private val adapter = ServedRequestsAdapter()
    private val logStore by lazy { UssdLogStore(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUssdLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerServed.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerServed.adapter = adapter
        refreshList()
    }

    fun refreshList() {
        if (_binding == null) return
        adapter.setItems(logStore.loadRecent())
        val n = adapter.itemCount
        if (n > 0) {
            binding.recyclerServed.scrollToPosition(n - 1)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
