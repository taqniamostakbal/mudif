package com.bolm.mudif.ui.agent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R
import com.bolm.mudif.databinding.ItemBrainModelBinding
import com.bolm.mudif.databinding.ItemBrainProviderBinding
import com.bolm.mudif.databinding.ItemBrainSearchBinding
import com.bolm.mudif.engine.AgentServer
import com.bolm.mudif.ui.settings.ViewerPrefs
import com.google.android.material.button.MaterialButton
import com.bolm.mudif.engine.MudifLog

/** One card per free AI platform. A model row selects that model as
 *  the brain (the Activity returns it to the chat). The key row saves
 *  the provider's key to Settings and rewrites keys.env (restarting
 *  the server when it is up, so the key is live at once); the
 *  "Get free key" button opens the provider's key page in the
 *  browser. Providers that need no key show a green note instead. */
class BrainProvidersAdapter(
    private val providers: List<BrainProvider>,
    private val currentModel: String,
    private val onPickModel: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    inner class Holder(val binding: ItemBrainProviderBinding) :
        RecyclerView.ViewHolder(binding.root)

    // Phase 3: "بحث" footer item (Exa + Tavily key rows).
    inner class SearchHolder(val binding: ItemBrainSearchBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TAG = "MudifAgent"
        private const val TYPE_PROVIDER = 0
        private const val TYPE_SEARCH = 1
    }

    override fun getItemViewType(position: Int) =
        if (position < providers.size) TYPE_PROVIDER else TYPE_SEARCH

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SEARCH) {
            SearchHolder(ItemBrainSearchBinding.inflate(inflater, parent, false))
        } else {
            Holder(ItemBrainProviderBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = providers.size + 1

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SearchHolder) {
            bindSearch(holder.binding)
            return
        }
        val providerHolder = holder as Holder
        val provider = providers[position]
        val b = providerHolder.binding
        val ctx = b.root.context
        b.providerName.text = provider.name
        b.providerFreeBadge.text = provider.freeNote
        val isCurrentProvider = provider.models.any { it.id == currentModel }
        b.providerCurrentBadge.visibility =
            if (isCurrentProvider) View.VISIBLE else View.GONE
        b.providerDescription.text = provider.description

        // Models: one row each (cleared first; the holder is recycled).
        b.providerModels.removeAllViews()
        for (model in provider.models) {
            val row = ItemBrainModelBinding.inflate(
                LayoutInflater.from(ctx), b.providerModels, false
            )
            val isCurrent = model.id == currentModel
            // Vision increment 2: a vision-capable model carries the
            // badge; the picked vision model adds a check. Tap still
            // picks the text brain; a long press picks the vision
            // model instead (see below).
            val visionModel = AgentFragment.getVisionModel(ctx)
            val isVisionPicked = model.id == visionModel
            val suffix = buildString {
                if (model.vision) {
                    append(" • ")
                    append(ctx.getString(R.string.brain_providers_vision_badge))
                }
                if (isVisionPicked) append(" ✓")
            }
            row.root.text = (if (isCurrent) "✓ " else "") + model.name + suffix
            if (isCurrent) {
                // p56: the current model row is a filled white pill
                // with purple text (the old filled purple was
                // invisible on the gradient card).
                row.root.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.white)
                row.root.setTextColor(
                    ContextCompat.getColor(ctx, R.color.mudif_purple)
                )
            }
            row.root.setOnClickListener { onPickModel(model.id) }
            if (model.vision) {
                // Long press toggles this model as the vision model
                // (the model the "شوف الشاشة" chip sends the
                // screenshot to). Long-pressing the picked one
                // again clears it back to local vision. The row is
                // refreshed in place; the screen stays open.
                row.root.setOnLongClickListener {
                    val current = AgentFragment.getVisionModel(ctx)
                    if (current == model.id) {
                        AgentFragment.setVisionModel(ctx, "")
                        Toast.makeText(
                            ctx,
                            ctx.getString(
                                R.string.brain_providers_vision_set,
                                ctx.getString(R.string.agent_brain_local_short)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        AgentFragment.setVisionModel(ctx, model.id)
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.brain_providers_vision_set, model.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    val pos = providerHolder.bindingAdapterPosition
                    if (pos >= 0) notifyItemChanged(pos)
                    true
                }
            }
            b.providerModels.addView(row.root)
        }

        // Key row (only for providers that need a key).
        if (provider.envKey == null || provider.prefKey == null) {
            b.providerKeyRow.visibility = View.GONE
            b.providerGetKey.visibility = View.GONE
            b.providerNoKey.visibility = View.VISIBLE
        } else {
            b.providerKeyRow.visibility = View.VISIBLE
            b.providerGetKey.visibility = View.VISIBLE
            b.providerNoKey.visibility = View.GONE
            val prefs = ViewerPrefs.prefs(ctx)
            b.providerKeyInput.hint =
                ctx.getString(R.string.brain_providers_key_hint, provider.name)
            b.providerKeyInput.setText(prefs.getString(provider.prefKey, "") ?: "")
            b.providerKeySave.setOnClickListener {
                val value = b.providerKeyInput.text.toString().trim()
                prefs.edit().putString(provider.prefKey, value).apply()
                AgentServer.writeKeysEnv(ctx)
                Toast.makeText(
                    ctx, R.string.brain_providers_key_saved, Toast.LENGTH_SHORT
                ).show()
                restartServerAfterKeySave(ctx)
            }
            b.providerGetKey.setOnClickListener {
                val url = provider.keyUrl ?: return@setOnClickListener
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                )
            }
        }
    }

    /** Phase 3: the "بحث" footer. One key row each for Exa and
     *  Tavily (the master plan's search providers). The save is
     *  the same as a provider key row: Settings pref + rewrite
     *  keys.env + restart the server if it is up. No "Get free
     *  key" button: no verified key URL exists for either. */
    private fun bindSearch(b: ItemBrainSearchBinding) {
        val ctx = b.root.context
        val prefs = ViewerPrefs.prefs(ctx)
        bindSearchRow(b.searchExaInput, b.searchExaSave, "agent_key_exa", "Exa", prefs, ctx)
        bindSearchRow(b.searchTavilyInput, b.searchTavilySave, "agent_key_tavily", "Tavily", prefs, ctx)
    }

    private fun bindSearchRow(
        input: EditText,
        save: MaterialButton,
        prefKey: String,
        name: String,
        prefs: SharedPreferences,
        ctx: Context
    ) {
        input.hint = ctx.getString(R.string.brain_providers_key_hint, name)
        input.setText(prefs.getString(prefKey, "") ?: "")
        save.setOnClickListener {
            val value = input.text.toString().trim()
            prefs.edit().putString(prefKey, value).apply()
            AgentServer.writeKeysEnv(ctx)
            Toast.makeText(
                ctx, R.string.brain_providers_key_saved, Toast.LENGTH_SHORT
            ).show()
            restartServerAfterKeySave(ctx)
        }
    }

    /**
     * Restarts the brain server after a key save, on a background
     * thread, and reports the honest outcome. The restart is
     * verified, not assumed: the old pid is read before the stop,
     * the stop result is checked, and success needs a new process
     * confirmed up afterwards with a different pid. The Toast
     * says "server restarted" only on that verified outcome,
     * otherwise it says the restart failed and the key loads at
     * the next start. Every step is logged (tag MudifAgent); the
     * key value is never logged. Never call on the UI thread
     * paths except through this thread: the full restart takes
     * longer than 3 s (stop waits up to 15 s, start polls up to
     * 30 s), so a fast pid check right after the save proves
     * nothing either way.
     */
    private fun restartServerAfterKeySave(ctx: Context) {
        val appCtx = ctx.applicationContext
        Thread {
            val portUp = AgentServer.isUp()
            val oldPid = AgentServer.opencodePid(appCtx)
            MudifLog.i(TAG, "key save restart: start (port up=$portUp, old pid=$oldPid)")
            if (!portUp && oldPid == null) {
                MudifLog.i(TAG, "key save restart: server was not running, no restart needed, key loads at next start")
                showRestartToast(appCtx, "تم حفظ المفتاح، وسيُحمَّل عند تشغيل الخادم التالي")
                return@Thread
            }
            val stopped = AgentServer.stop(appCtx)
            MudifLog.i(TAG, "key save restart: stop result=$stopped (old pid=$oldPid)")
            if (!stopped) {
                Log.e(TAG, "key save restart: stop failed, old server still running, new key not loaded")
                showRestartToast(appCtx, "فشلت إعادة التشغيل، وسيُحمَّل المفتاح عند التشغيل التالي")
                return@Thread
            }
            val ensured = AgentServer.ensureStarted(appCtx)
            val newPid = AgentServer.opencodePid(appCtx)
            val pidChanged = oldPid == null || (newPid != null && newPid != oldPid)
            MudifLog.i(TAG, "key save restart: ensureStarted up=${ensured.up} reason=${ensured.reason} new pid=$newPid pidChanged=$pidChanged")
            if (ensured.up && newPid != null && pidChanged) {
                showRestartToast(appCtx, "تمت إعادة تشغيل الخادم")
            } else {
                Log.e(TAG, "key save restart: restart not verified (up=${ensured.up}, old pid=$oldPid, new pid=$newPid)")
                showRestartToast(appCtx, "فشلت إعادة التشغيل، وسيُحمَّل المفتاح عند التشغيل التالي")
            }
        }.apply { isDaemon = true }.start()
    }

    /** Toast from a background thread: posts to the main looper,
     *  a worker thread has none. */
    private fun showRestartToast(ctx: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
    }
}
