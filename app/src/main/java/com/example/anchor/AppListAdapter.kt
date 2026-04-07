package com.example.anchor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class AppListAdapter(
    private val blockedApps: MutableSet<String>,
    private val onToggle: (String, Boolean) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val name: TextView = itemView.findViewById(R.id.tvAppName)
        private val switch: MaterialSwitch = itemView.findViewById(R.id.switchBlock)

        fun bind(app: AppInfo) {
            icon.setImageDrawable(app.icon)
            name.text = app.name

            switch.setOnCheckedChangeListener(null)
            switch.isChecked = blockedApps.contains(app.packageName)
            switch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(app.packageName, isChecked)
            }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.packageName == newItem.packageName
    }
}
