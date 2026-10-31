package com.phoneai.localmodel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class KnowledgeAdapter(
    private var entries: MutableList<KnowledgeEntry>,
    private val onQueryRagClick: (KnowledgeEntry) -> Unit,
    private val onDeleteClick: (KnowledgeEntry) -> Unit
) : RecyclerView.Adapter<KnowledgeAdapter.KnowledgeViewHolder>() {

    private val similarityScores = mutableMapOf<Long, Float>()

    class KnowledgeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvKnowledgeTitle)
        val tvCategory: TextView = itemView.findViewById(R.id.tvKnowledgeCategory)
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvKnowledgeTimestamp)
        val tvScore: TextView = itemView.findViewById(R.id.tvKnowledgeScore)
        val tvSummary: TextView = itemView.findViewById(R.id.tvKnowledgeSummary)
        val tvContent: TextView = itemView.findViewById(R.id.tvKnowledgeContent)
        val btnAskAI: LinearLayout = itemView.findViewById(R.id.btnAskKnowledgeAI)
        val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteKnowledge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KnowledgeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_knowledge_entry, parent, false)
        return KnowledgeViewHolder(view)
    }

    override fun onBindViewHolder(holder: KnowledgeViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvTitle.text = entry.title
        holder.tvCategory.text = entry.category
        holder.tvTimestamp.text = entry.timestamp
        holder.tvSummary.text = entry.summary
        holder.tvContent.text = entry.content

        val score = similarityScores[entry.id]
        if (score != null) {
            holder.tvScore.visibility = View.VISIBLE
            holder.tvScore.text = String.format(Locale.US, "Cosine: %.3f", score)
        } else {
            holder.tvScore.visibility = View.GONE
        }

        holder.btnAskAI.setOnClickListener {
            onQueryRagClick(entry)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(entry)
        }
    }

    override fun getItemCount(): Int = entries.size

    fun updateList(newEntries: List<KnowledgeEntry>, scores: Map<Long, Float>? = null) {
        entries.clear()
        entries.addAll(newEntries)
        similarityScores.clear()
        if (scores != null) {
            similarityScores.putAll(scores)
        }
        notifyDataSetChanged()
    }
}

