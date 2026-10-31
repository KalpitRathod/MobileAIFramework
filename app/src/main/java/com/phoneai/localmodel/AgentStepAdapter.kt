package com.phoneai.localmodel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mobileai.framework.agent.AgentStep
import java.text.SimpleDateFormat
import java.util.*

class AgentStepAdapter(
    private val steps: MutableList<AgentStep> = mutableListOf()
) : RecyclerView.Adapter<AgentStepAdapter.StepViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class StepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvStepNumber: TextView = itemView.findViewById(R.id.tvStepNumber)
        val tvActionBadge: TextView = itemView.findViewById(R.id.tvActionBadge)
        val tvStepTime: TextView = itemView.findViewById(R.id.tvStepTime)
        val tvStepThought: TextView = itemView.findViewById(R.id.tvStepThought)
        val tvStepDetails: TextView = itemView.findViewById(R.id.tvStepDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_agent_step, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        val step = steps[position]
        holder.tvStepNumber.text = "STEP ${step.stepNumber}"
        holder.tvActionBadge.text = step.action.type.name
        holder.tvStepTime.text = timeFormat.format(Date(step.timestampMs))
        holder.tvStepThought.text = step.thought.ifBlank { "Executing automated action" }

        val details = when {
            step.action.x != null && step.action.y != null -> "Coordinates: (${step.action.x}, ${step.action.y})"
            !step.action.text.isNullOrBlank() -> "Input: \"${step.action.text}\""
            !step.action.keyName.isNullOrBlank() -> "Key: ${step.action.keyName}"
            !step.action.scrollDirection.isNullOrBlank() -> "Scroll: ${step.action.scrollDirection}"
            !step.action.packageName.isNullOrBlank() -> "Package: ${step.action.packageName}"
            !step.action.summary.isNullOrBlank() -> "Summary: ${step.action.summary}"
            else -> "Action: ${step.action.type}"
        }
        holder.tvStepDetails.text = details
    }

    override fun getItemCount(): Int = steps.size

    fun addStep(step: AgentStep) {
        steps.add(step)
        notifyItemInserted(steps.size - 1)
    }

    fun clearSteps() {
        steps.clear()
        notifyDataSetChanged()
    }
}

