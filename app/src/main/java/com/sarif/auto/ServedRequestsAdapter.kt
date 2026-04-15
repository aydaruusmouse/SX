package com.sarif.auto

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sarif.auto.databinding.ItemServedRequestBinding

class ServedRequestsAdapter : RecyclerView.Adapter<ServedRequestsAdapter.VH>() {

    private val items = ArrayList<UssdLogEntry>()

    fun setItems(newItems: List<UssdLogEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        val binding = ItemServedRequestBinding.inflate(inf, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemServedRequestBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: UssdLogEntry) {
            val ctx = binding.root.context
            val status = ctx.getString(
                if (entry.ok) R.string.log_status_ok else R.string.log_status_fail
            )
            binding.textStepMeta.text = ctx.getString(
                R.string.log_step_meta,
                entry.stepIndex,
                entry.requestOpener,
                entry.simLabel,
                status
            )
            binding.textStepTime.text = DateFormat.format("HH:mm", entry.timeMs)
            binding.textStepResponse.text = entry.response.ifBlank { "—" }
        }
    }
}
