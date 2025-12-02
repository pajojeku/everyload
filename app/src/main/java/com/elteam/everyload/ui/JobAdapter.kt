package com.elteam.everyload.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.elteam.everyload.R
import com.elteam.everyload.model.JobEntry

class JobAdapter(
    private val items: MutableList<JobEntry>,
    private val onClick: (JobEntry) -> Unit,
    private val onChanged: () -> Unit = {}
) : RecyclerView.Adapter<JobAdapter.VH>() {

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
        
        // Show video title if available, otherwise show filename or jobId
        holder.title.text = item.title ?: item.files?.firstOrNull() ?: item.jobId
        holder.url.text = item.localUri ?: item.url
        
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

    fun upsert(job: JobEntry) {
        val idx = items.indexOfFirst { it.jobId == job.jobId }
        if (idx >= 0) {
            items[idx] = job
            notifyItemChanged(idx)
        } else {
            items.add(0, job)
            notifyItemInserted(0)
        }
        onChanged()
    }
}
