package com.elteam.everyload.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.elteam.everyload.R
import com.elteam.everyload.model.JobEntry

/**
 * Adapter for displaying download jobs.
 * Now uses stable IDs and DiffUtil for efficient updates.
 */
class JobAdapter(
    private val onClick: (JobEntry) -> Unit
) : RecyclerView.Adapter<JobAdapter.VH>() {

    private val items = mutableListOf<JobEntry>()
    
    init {
        setHasStableIds(true)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.jobTitle)
        val url: TextView = view.findViewById(R.id.jobUrl)
        val status: TextView = view.findViewById(R.id.jobStatus)
        val info: TextView = view.findViewById(R.id.jobInfo)
        val spinner: ProgressBar = view.findViewById(R.id.jobProgressSpinner)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_job, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        
        // Use the new display methods
        holder.title.text = item.getDisplayTitle()
        holder.url.text = item.getDisplayUrl()
        
        val context = holder.itemView.context
        
        // Style status based on state
        when (item.status) {
            "downloaded" -> {
                holder.status.text = context.getString(R.string.status_saved)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_saved))
                holder.status.setTypeface(null, Typeface.BOLD)
                holder.spinner.visibility = View.GONE
            }
            "downloading_local" -> {
                holder.status.text = context.getString(R.string.status_downloading)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_downloading))
                holder.status.setTypeface(null, Typeface.NORMAL)
                holder.spinner.visibility = View.VISIBLE
            }
            "finished" -> {
                holder.status.text = context.getString(R.string.status_ready)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_ready))
                holder.status.setTypeface(null, Typeface.NORMAL)
                holder.spinner.visibility = View.GONE
            }
            "error", "download_error" -> {
                holder.status.text = context.getString(R.string.status_error)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_error))
                holder.status.setTypeface(null, Typeface.BOLD)
                holder.spinner.visibility = View.GONE
            }
            "queued" -> {
                holder.status.text = context.getString(R.string.status_queued)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_queued))
                holder.status.setTypeface(null, Typeface.NORMAL)
                holder.spinner.visibility = View.GONE
            }
            "downloading", "running" -> {
                holder.status.text = context.getString(R.string.status_processing)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_downloading))
                holder.status.setTypeface(null, Typeface.NORMAL)
                holder.spinner.visibility = View.VISIBLE
            }
            "extracting" -> {
                holder.status.text = context.getString(R.string.status_extracting)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_downloading))
                holder.status.setTypeface(null, Typeface.NORMAL)
                holder.spinner.visibility = View.VISIBLE
            }
            "stopped" -> {
                holder.status.text = context.getString(R.string.status_stopped)
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_error))
                holder.status.setTypeface(null, Typeface.NORMAL)
                holder.spinner.visibility = View.GONE
            }
            else -> {
                holder.status.text = item.status
                holder.status.setTextColor(ContextCompat.getColor(context, R.color.status_queued))
                holder.status.setTypeface(null, Typeface.NORMAL)
                holder.spinner.visibility = View.GONE
            }
        }
        
        // Show info field for progress or additional information
        if (!item.info.isNullOrEmpty()) {
            holder.info.visibility = View.VISIBLE
            holder.info.text = item.info
        } else {
            holder.info.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
    
    override fun getItemId(position: Int): Long {
        // Use jobId hash as stable ID
        return items[position].jobId.hashCode().toLong()
    }

    /**
     * Update the entire list using DiffUtil for efficient animations
     */
    fun submitList(newList: List<JobEntry>) {
        val diffCallback = JobDiffCallback(items, newList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        
        items.clear()
        items.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }
    
    /**
     * Update a single item by position
     */
    fun updateItem(position: Int, job: JobEntry) {
        if (position in items.indices) {
            items[position] = job
            notifyItemChanged(position)
        }
    }
    
    /**
     * Insert a single item at position
     */
    fun insertItem(position: Int, job: JobEntry) {
        items.add(position, job)
        notifyItemInserted(position)
    }
    
    /**
     * Remove item at position
     */
    fun removeItem(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }
    
    /**
     * Clear all items
     */
    fun clearAll() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }
    
    /**
     * Get item at position
     */
    fun getItem(position: Int): JobEntry? {
        return items.getOrNull(position)
    }
}

/**
 * DiffUtil callback for efficient list updates
 */
private class JobDiffCallback(
    private val oldList: List<JobEntry>,
    private val newList: List<JobEntry>
) : DiffUtil.Callback() {
    
    override fun getOldListSize(): Int = oldList.size
    
    override fun getNewListSize(): Int = newList.size
    
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].jobId == newList[newItemPosition].jobId
    }
    
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]
        
        return oldItem.status == newItem.status &&
               oldItem.info == newItem.info &&
               oldItem.title == newItem.title &&
               oldItem.localUri == newItem.localUri &&
               oldItem.files == newItem.files
    }
}

