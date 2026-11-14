package com.bolm.mudif.ui.agent

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemAgentMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One chat line. `replyLanguage` is the reply-language choice the user
 * picked in the header ("fusha" / "darija" / "en"); it travels with
 * every user message so the brain's system prompt can answer in that
 * language (the prompt itself is a later step).
 */
data class AgentMessage(
    val role: String,
    val text: String,
    val replyLanguage: String = REPLY_FUSHA,
    val time: Long = System.currentTimeMillis()
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
 * bubble; the adapter flips it by role (user = end-aligned, light
 * purple bubble, dark text, no avatar; agent = start-aligned, white
 * bubble, dark text, clay-penguin avatar). RTL is handled by the
 * layout: the row root uses layoutDirection from the locale, so "start"
 * and "end" are correct in Arabic and in English.
 * p30 KissaKoti redesign: the timestamp sits INSIDE the bubble, on the
 * side the bubble is aligned to (agent = START, user = END); the
 * background is set on message_bubble, not on the text view.
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
            messageTime.text = formatTime(message.time)
            val timeParams = messageTime.layoutParams as LinearLayout.LayoutParams
            if (message.role == AgentMessage.ROLE_USER) {
                messageRow.gravity = Gravity.END
                messageAvatar.visibility = View.GONE
                messageBubble.setBackgroundResource(R.drawable.agent_bubble_user)
                messageText.setTextColor(resolveOnSurfaceColor(ctx))
                timeParams.gravity = Gravity.END
            } else {
                messageRow.gravity = Gravity.START
                messageAvatar.visibility = View.VISIBLE
                messageBubble.setBackgroundResource(R.drawable.agent_bubble_agent)
                messageText.setTextColor(resolveOnSurfaceColor(ctx))
                timeParams.gravity = Gravity.START
            }
            messageTime.layoutParams = timeParams
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

    /** WhatsApp-style time under each bubble (HH:mm, device locale). */
    private fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
}
