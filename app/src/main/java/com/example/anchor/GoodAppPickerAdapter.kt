package com.example.anchor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class GoodAppPickerAdapter(
    private val onSelect: (AppInfo) -> Unit
) : ListAdapter<AppInfo, GoodAppPickerAdapter.GoodAppViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GoodAppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_good_app, parent, false)
        return GoodAppViewHolder(view)
    }

    override fun onBindViewHolder(holder: GoodAppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GoodAppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val icon: ImageView = itemView.findViewById(R.id.ivGoodAppIcon)
        private val name: TextView = itemView.findViewById(R.id.tvGoodAppName)

        fun bind(app: AppInfo) {
            icon.setImageDrawable(app.icon)
            name.text = app.name
            card.setOnClickListener { onSelect(app) }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem == newItem
    }
}
