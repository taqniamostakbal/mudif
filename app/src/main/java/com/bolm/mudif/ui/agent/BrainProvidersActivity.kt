package com.bolm.mudif.ui.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.bolm.mudif.ui.MudifActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bolm.mudif.R

/** p31: provider-selection screen. One Mudif-style card per free AI
 *  platform (9 platforms, 46 models from opencode-providers.json;
 *  p95: Groq hidden, 5 models, by Anas's order):
 *  name + description + its free models + a "Get free key" button
 *  that opens the provider's key page. Tapping a model returns it
 *  to the chat (EXTRA_PICKED_MODEL); the chat applies it through the
 *  existing setModel backend and resets the session. */
class BrainProvidersActivity : MudifActivity() {

    companion object {
        const val EXTRA_CURRENT_MODEL = "current_model"
        const val EXTRA_PICKED_MODEL = "picked_model"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_brain_providers)
        findViewById<TextView>(R.id.providers_back).setOnClickListener { finish() }
        val current =
            intent.getStringExtra(EXTRA_CURRENT_MODEL) ?: "ollama/qwen3:1.7b"
        val providers = BrainProviders.load(this)
        val recycler = findViewById<RecyclerView>(R.id.providers_recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = BrainProvidersAdapter(providers, current) { picked ->
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_PICKED_MODEL, picked)
            )
            finish()
        }
    }
}
