package com.bolm.mudif.ui.agent

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemAgentMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One chat line. `replyLanguage` is the reply language in force
 * when the message was written ("fusha" / "darija" / "en"); it
 * travels with every message and is stored with it. p57: it always
 * follows the app language now, read live at turn start; the old
 * header menu that let it differ from the UI is gone.
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
 * p98: the text and timestamp colors ARE set here, per role, from
 * the theme attrs (user rows: mudifBubbleUserText / mudifBubbleUserMuted;
 * agent rows: mudifTextPrimary / mudifTextMuted). The layout can only
 * hold one color per view, and the user bubble inverts with the theme
 * (purple in dark/light, white in gradient), so the text color must
 * follow the role. The old trap does NOT apply to the mudif* attrs:
 * they are literal colors in every theme, so TypedValue.data IS the
 * color (textColorPrimary was a ColorStateList in Material3, so .data
 * was not the color, and resolving it rendered the text invisible on
 * the rig). The resolver still type-checks and falls back to the
 * layout's current color, so a bad attr can never render invisible text.
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
        with(holder.binding) {
            // p32: the brain's replies carry raw markdown (`2 + 2`,
            // **4**) and the bubbles show it raw. Strip the bold /
            // code markers for display only (the stored text keeps
            // the original) so the result reads plainly. User
            // messages show exactly what was typed.
            messageText.text = stripDashes(
                if (message.role == AgentMessage.ROLE_AGENT) stripMarkdown(message.text)
                else message.text
            )
            messageTime.text = formatTime(message.time)
            if (message.role == AgentMessage.ROLE_USER) {
                messageRow.gravity = Gravity.END
                messageAvatar.visibility = View.GONE
                messageBubble.setBackgroundResource(R.drawable.agent_bubble_user)
                // p98: white text on the purple user bubble (dark/light),
                // purple text on the white user bubble (gradient).
                messageText.setTextColor(resolveThemeColor(
                    messageText.context, R.attr.mudifBubbleUserText, messageText.currentTextColor))
                messageTime.setTextColor(resolveThemeColor(
                    messageTime.context, R.attr.mudifBubbleUserMuted, messageTime.currentTextColor))
            } else {
                messageRow.gravity = Gravity.START
                messageAvatar.visibility = View.VISIBLE
                messageBubble.setBackgroundResource(R.drawable.agent_bubble_agent)
                messageText.setTextColor(resolveThemeColor(
                    messageText.context, R.attr.mudifTextPrimary, messageText.currentTextColor))
                messageTime.setTextColor(resolveThemeColor(
                    messageTime.context, R.attr.mudifTextMuted, messageTime.currentTextColor))
            }
            // p31: the timestamp is always bottom-right inside the
            // bubble (KissaKoti). message_time is match_parent with
            // gravity=end in the layout, so no per-role gravity is
            // set here any more: a layout_gravity on the time view
            // inside a wrap_content bubble is the prime suspect for
            // the short-agent-message full-width bubble.
        }
    }

    override fun getItemCount() = items.size

    /** Resolve a theme color attr to its color int. The mudif* attrs
     *  are literal colors in every theme, so TypedValue.data IS the
     *  color. The type is still checked: a non-color attr (or an
     *  unresolved one) falls back to [fallback] (the layout's current
     *  text color) instead of rendering invisible text. */
    private fun resolveThemeColor(context: Context, @AttrRes attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attr, tv, true) &&
            tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        ) {
            return tv.data
        }
        return fallback
    }

    /** WhatsApp-style time under each bubble (HH:mm, Latin digits). */
    private fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(time))

    /** p32: strips the bold/code markers the brain emits (`**`, `__`,
     *  backticks) so a reply reads plainly. Display only. p32b: the
     *  brain also emits LaTeX for math ($...$, \boxed{30}, \frac{a}{b},
     *  \times, \%), strip those wrappers too so the result reads
     *  plainly (30, not $$\boxed{30}$$). */
    private fun stripMarkdown(text: String): String {
        var out = text.replace("**", "").replace("__", "").replace("`", "")
        out = out.replace("$$", "").replace("$", "")
        out = Regex("""\\boxed\{([^}]*)\}""").replace(out) { it.groupValues[1] }
        out = Regex("""\\frac\{([^}]*)\}\{([^}]*)\}""").replace(out) {
            "${it.groupValues[1]}/${it.groupValues[2]}"
        }
        out = out.replace("\\times", "×").replace("\\%", "%")
        return out
    }

    /** Display side dash sanitization: replace each em dash (U+2014)
     *  and en dash (U+2013) with a single space, display only (the
     *  stored text keeps the original). Applied to every rendered
     *  message, agent and user alike, so a reply stored before this
     *  fix still renders dash free. */
    private fun stripDashes(text: String): String =
        text.replace('\u2014', ' ').replace('\u2013', ' ')
}
