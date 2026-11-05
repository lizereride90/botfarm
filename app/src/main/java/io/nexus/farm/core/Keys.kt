package io.nexus.farm.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class ApiKey(
	val id: String,
	val provider: String,
	val value: String,
	val limitedUntil: Long = 0,
	val hits: Int = 0,
) {
	val cooling: Boolean get() = limitedUntil > System.currentTimeMillis()

	val state: String
		get() = when {
			cooling -> "resting ${((limitedUntil - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)}m"
			hits > 0 -> "used $hits"
			else -> "ready"
		}
}

/**
 * a pool of api keys per provider.
 *
 * bots run for days. the free tiers do not. when a key reports a rate limit it
 * goes to sleep for a while and the next key in the pool takes over, so the
 * bot keeps running without anyone watching it.
 *
 * the current key is handed to every child process as the usual environment
 * variable for that provider, which is what basically every bot and cli reads.
 */
object Keys {

	/** provider to the env var its tools read */
	val providers = linkedMapOf(
		"groq" to "GROQ_API_KEY",
		"openai" to "OPENAI_API_KEY",
		"gemini" to "GEMINI_API_KEY",
		"anthropic" to "ANTHROPIC_API_KEY",
		"openrouter" to "OPENROUTER_API_KEY",
		"deepseek" to "DEEPSEEK_API_KEY",
		"mistral" to "MISTRAL_API_KEY",
	)

	private const val FILE = "keys.json"

	/** how long a key rests after it reports a limit */
	private const val REST = 6 * 60 * 60 * 1000L

	private val pool = mutableListOf<ApiKey>()
	private val dirty = AtomicBoolean(false)
	private val lock = Any()

	fun load() {
		synchronized(lock) {
			if (pool.isNotEmpty()) return
			val file = File(Env.root, FILE)
			if (!file.exists()) return

			runCatching {
				val array = JSONArray(file.readText())
				for (i in 0 until array.length()) {
					val o = array.getJSONObject(i)
					pool += ApiKey(
						id = o.getString("id"),
						provider = o.getString("provider"),
						value = o.getString("value"),
						limitedUntil = o.optLong("limitedUntil", 0),
						hits = o.optInt("hits", 0),
					)
				}
			}
		}
	}

	private fun save() {
		if (!dirty.compareAndSet(false, true)) return

		val array = JSONArray()
		synchronized(lock) {
			for (key in pool) {
				array.put(
					JSONObject()
						.put("id", key.id)
						.put("provider", key.provider)
						.put("value", key.value)
						.put("limitedUntil", key.limitedUntil)
						.put("hits", key.hits),
				)
			}
		}

		runCatching {
			val file = File(Env.root, FILE)
			file.writeText(array.toString())
			file.setReadable(false, false)
			file.setReadable(true, true)
		}
		dirty.set(false)
	}

	fun all(): List<ApiKey> = synchronized(lock) { pool.toList() }

	fun forProvider(provider: String): List<ApiKey> =
		synchronized(lock) { pool.filter { it.provider == provider } }

	fun add(provider: String, value: String): ApiKey? {
		val clean = value.trim()
		if (clean.isBlank()) return null

		val key = ApiKey(
			id = System.currentTimeMillis().toString(36) + clean.hashCode().toString(36).take(4),
			provider = provider,
			value = clean,
		)

		synchronized(lock) {
			if (pool.any { it.provider == provider && it.value == clean }) return null
			pool += key
		}

		save()
		return key
	}

	fun remove(id: String) {
		synchronized(lock) { pool.removeAll { it.id == id } }
		save()
	}

	/** the first key that is not resting, providers are tried in order */
	fun current(provider: String): ApiKey? = synchronized(lock) {
		val keys = pool.filter { it.provider == provider }
		keys.firstOrNull { !it.cooling } ?: keys.minByOrNull { it.limitedUntil }?.takeIf { keys.isNotEmpty() }
	}

	/** nothing left to rotate to, the caller gets the soonest one back */
	fun exhausted(provider: String): Boolean = synchronized(lock) {
		val keys = pool.filter { it.provider == provider }
		keys.isNotEmpty() && keys.all { it.cooling }
	}

	fun hitLimit(id: String, rest: Long = REST) {
		synchronized(lock) {
			val index = pool.indexOfFirst { it.id == id }
			if (index < 0) return
			val key = pool[index]
			pool[index] = key.copy(limitedUntil = System.currentTimeMillis() + rest, hits = key.hits + 1)
		}
		save()
	}

	fun noteUse(id: String) {
		synchronized(lock) {
			val index = pool.indexOfFirst { it.id == id }
			if (index < 0) return
			val key = pool[index]
			pool[index] = key.copy(hits = key.hits + 1)
		}
		save()
	}

	/** what every hosted process sees */
	fun environment(): Map<String, String> {
		val env = mutableMapOf<String, String>()

		for ((provider, variable) in providers) {
			val key = current(provider) ?: continue
			env[variable] = key.value
		}

		// one var with the pool laid out, for tools that want to rotate by hand
		env["BOTFARM_KEY_FILE"] = File(Env.root, FILE).absolutePath
		return env
	}

	private val limitSignals = listOf(
		"rate limit",
		"rate_limit",
		"429",
		"quota",
		"usage limit",
		"insufficient_quota",
		"too many requests",
		"exceeded your current quota",
	)

	/**
	 * a line of bot output that looks like a limit, so the manager can move the
	 * running server onto the next key instead of letting it spin.
	 */
	fun looksLimited(line: String): Boolean {
		val lower = line.lowercase()
		return limitSignals.any { lower.contains(it) }
	}

	/**
	 * when the line names a provider and reads like a limit, park that key.
	 * returns the provider that changed so the caller can restart the process
	 * with the next key in the pool.
	 */
	fun rotate(line: String): String? {
		if (!looksLimited(line)) return null

		val lower = line.lowercase()
		var changed: String? = null

		for (provider in providers.keys) {
			if (!lower.contains(provider)) continue
			if (exhausted(provider)) continue

			val key = current(provider) ?: continue
			hitLimit(key.id)
			changed = provider
		}

		return changed
	}
}
