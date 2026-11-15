package com.bolm.mudif.ui.agent

import android.content.Context
import org.json.JSONObject

/** One free AI platform (a card on the provider screen). */
data class BrainProvider(
    val key: String,
    val name: String,
    val description: String,
    val freeNote: String,
    val keyUrl: String?,
    val envKey: String?,
    val prefKey: String?,
    val models: List<BrainModel>
)

data class BrainModel(val id: String, val name: String, val vision: Boolean = false)

/** The 10 free platforms from opencode-providers.json, in display
 *  order: local first, then the no-key free models, then the keyed
 *  platforms. The same asset the chat labels (AgentFragment) and the
 *  guest config (AgentServer) read, so the screen, the picker labels
 *  and the server can never disagree about which models exist. */
object BrainProviders {
    val ORDER = listOf(
        "ollama", "opencode", "openrouter", "groq", "llm7",
        "huggingface", "google", "cohere", "mistral", "tokenlab"
    )

    /** Settings pref key for a provider's API key env var. */
    fun prefKeyForEnv(env: String?): String? = when (env) {
        "GROQ_API_KEY" -> "agent_key_groq"
        "HF_TOKEN" -> "agent_key_hf"
        "LLM7_API_KEY" -> "agent_key_llm7"
        "OPENROUTER_API_KEY" -> "agent_key_openrouter"
        "GEMINI_API_KEY" -> "agent_key_gemini"
        "CO_API_KEY" -> "agent_key_cohere"
        "MISTRAL_API_KEY" -> "agent_key_mistral"
        "TOKENLAB_API_KEY" -> "agent_key_tokenlab"
        else -> null
    }

    fun load(context: Context): List<BrainProvider> {
        val out = mutableListOf<BrainProvider>()
        try {
            val root = JSONObject(
                context.assets.open("opencode-providers.json")
                    .bufferedReader().use { it.readText() }
            ).optJSONObject("providers") ?: return out
            for (key in ORDER) {
                val p = root.optJSONObject(key) ?: continue
                val modelsObj = p.optJSONObject("models") ?: continue
                val modelKeys = mutableListOf<String>()
                val keys = modelsObj.keys()
                while (keys.hasNext()) modelKeys.add(keys.next())
                val models = modelKeys
                    .sortedBy { modelsObj.optJSONObject(it)?.optString("name", it) ?: it }
                    .map { mk ->
                        BrainModel(
                            "$key/$mk",
                            modelsObj.optJSONObject(mk)?.optString("name", mk) ?: mk,
                            // Vision increment 2: the "vision" flag in
                            // opencode-providers.json marks models
                            // that can see a screenshot.
                            modelsObj.optJSONObject(mk)?.optBoolean("vision", false) ?: false
                        )
                    }
                if (models.isEmpty()) continue
                val env = p.optJSONArray("env")?.optString(0)
                out.add(
                    BrainProvider(
                        key = key,
                        name = p.optString("name", key),
                        description = p.optString("description", ""),
                        freeNote = p.optString("freeNote", ""),
                        keyUrl = p.optString("keyUrl", "").takeIf { it.isNotBlank() },
                        envKey = env,
                        prefKey = prefKeyForEnv(env),
                        models = models
                    )
                )
            }
        } catch (e: Exception) {
            // Empty list; the chat still works on Local.
        }
        return out
    }
}
