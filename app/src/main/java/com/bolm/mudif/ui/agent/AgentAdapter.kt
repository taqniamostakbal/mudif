package com.bolm.mudif.ui.agent

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemAgentMessageBinding

/**
 * One chat line. `replyLanguage` is the reply-language choice the user
 * picked in the header ("fusha" / "darija" / "en"); it travels with
 * every user message so the brain's system prompt can answer in that
 * language (the prompt itself is a later step).
 */
data class AgentMessage(
    val role: String,
    val text: String,
    val replyLanguage: String = REPLY_FUSHA
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_AGENT = "agent"
        const val REPLY_FUSHA = "fusha"
        const val REPLY_DARIJA = "darija"
        const val REPLY_EN = "en"
    }
}

/**
 * Phase 4: one message row for the مساعد مضيف chat. The row is a single
 * bubble; the adapter flips it by role (user = end-aligned, purple
 * bubble, white text, no avatar; agent = start-aligned, surface bubble,
 * system text, clay-penguin avatar). RTL is handled by the layout:
 * the row root uses layoutDirection from the locale, so "start" and
 * "end" are correct in Arabic and in English.
 */
class AgentAdapter : RecyclerView.Adapter<AgentAdapter.Holder>() {

    private var items: List<AgentMessage> = emptyList()

    inner class Holder(val binding: ItemAgentMessageBinding) :
        RecyclerView.ViewHolder(binding.root)

    fun submitList(messages: List<AgentMessage>) {
        items = messages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAgentMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = items[position]
        val ctx = holder.binding.root.context
        with(holder.binding) {
            messageText.text = message.text
            if (message.role == AgentMessage.ROLE_USER) {
                messageRow.gravity = Gravity.END
                messageAvatar.visibility = View.GONE
                messageText.setBackgroundResource(R.drawable.agent_bubble_user)
                messageText.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
            } else {
                messageRow.gravity = Gravity.START
                messageAvatar.visibility = View.VISIBLE
                messageText.setBackgroundResource(R.drawable.agent_bubble_agent)
                messageText.setTextColor(resolveOnSurfaceColor(ctx))
            }
        }
    }

    override fun getItemCount() = items.size

    /** Agent-bubble text: the theme's primary text color, so the bubble
     *  stays readable in DayNight light and dark modes. */
    private fun resolveOnSurfaceColor(ctx: Context): Int {
        val typedValue = TypedValue()
        return if (ctx.theme.resolveAttribute(
                android.R.attr.textColorPrimary, typedValue, true
            )
        ) typedValue.data else Color.BLACK
    }
}
