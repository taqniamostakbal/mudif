package com.bolm.mudif.ui.agent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
            row.root.text = if (isCurrent) "✓ ${model.name}" else model.name
            if (isCurrent) {
                row.root.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, R.color.mudif_purple)
                row.root.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
            }
            row.root.setOnClickListener { onPickModel(model.id) }
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
                if (AgentServer.isUp()) {
                    Thread {
                        AgentServer.stop(ctx)
                        AgentServer.ensureStarted(ctx)
                    }.start()
                }
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
            if (AgentServer.isUp()) {
                Thread {
                    AgentServer.stop(ctx)
                    AgentServer.ensureStarted(ctx)
                }.start()
            }
        }
    }
}
