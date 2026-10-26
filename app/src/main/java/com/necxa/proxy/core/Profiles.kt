package com.necxa.proxy.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** App-filter mode, applied in NeroVpnService.Builder. */
enum class FilterMode { ALL, EXCLUDE, INCLUDE }

/**
 * One proxy profile. Passwords are stored encrypted with the Android Keystore
 * key (SecretBox) in SharedPreferences. Exported files are the exception: an
 * export is a plain JSON file the user explicitly saves, so it contains the
 * password in clear text. Keep an exported file private, like any password file.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val tls: Boolean = false,
    val testUrl: String = "https://example.com/",
) {
    fun toConfig() = ProxyConfig(type, host, port, username, password, tls)
    fun toForm() = FormState(type, host, port.toString(), username ?: "", password ?: "", testUrl, name, tls)
    fun displayName(): String = name.ifBlank { "$host:$port" }
    // Never print credentials.
    override fun toString() = "Profile(${displayName()} $type $host:$port tls=$tls auth=${!username.isNullOrEmpty()})"
}

fun profileFromForm(id: String?, f: FormState, cfg: ProxyConfig): Profile =
    Profile(
        id = id ?: UUID.randomUUID().toString(),
        name = f.name.trim(),
        type = cfg.type, host = cfg.host, port = cfg.port,
        username = cfg.username, password = cfg.password, tls = cfg.tls, testUrl = f.testUrl.trim(),
    )

class ProfileStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nero_profiles_v1", Context.MODE_PRIVATE)

    fun loadAll(): List<Profile> {
        val raw = sp.getString("profiles", null) ?: return emptyList()
        val out = mutableListOf<Profile>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val enc = o.optString("passEnc", "")
                    out += Profile(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", ""),
                        type = runCatching { ProxyType.valueOf(o.optString("type", "SOCKS5")) }.getOrDefault(ProxyType.SOCKS5),
                        host = o.optString("host", ""),
                        port = o.optInt("port", 0),
                        username = o.optString("username", "").ifEmpty { null },
                        password = if (enc.isEmpty()) null else SecretBox.decrypt(enc),
                        tls = o.optBoolean("tls", false),
                        testUrl = o.optString("testUrl", "https://example.com/"),
                    )
                } catch (_: Exception) { /* skip one bad profile, keep the rest */ }
            }
        } catch (_: Exception) { return emptyList() }
        return out.filter { it.host.isNotEmpty() && it.port in 1..65535 }
    }

    fun saveAll(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("name", p.name); o.put("type", p.type.name)
            o.put("host", p.host); o.put("port", p.port)
            o.put("username", p.username ?: "")
            o.put("passEnc", if (p.password.isNullOrEmpty()) "" else SecretBox.encrypt(p.password))
            o.put("tls", p.tls); o.put("testUrl", p.testUrl)
            arr.put(o)
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    fun activeId(): String? = sp.getString("activeId", null)
    fun setActiveId(id: String?) { sp.edit().putString("activeId", id).apply() }
    fun active(): Profile? {
        val all = loadAll()
        return all.firstOrNull { it.id == activeId() } ?: all.firstOrNull()
    }

    fun darkMode(): Boolean = sp.getBoolean("darkMode", false)
    fun setDarkMode(v: Boolean) { sp.edit().putBoolean("darkMode", v).apply() }
    fun filterMode(): FilterMode =
        runCatching { FilterMode.valueOf(sp.getString("filterMode", "ALL")!!) }.getOrDefault(FilterMode.ALL)
    fun setFilterMode(m: FilterMode) { sp.edit().putString("filterMode", m.name).apply() }
    fun filterPackages(): Set<String> = sp.getStringSet("filterPackages", emptySet()) ?: emptySet()
    fun setFilterPackages(s: Set<String>) { sp.edit().putStringSet("filterPackages", s).apply() }

    /** One-time migration from the old single-config storage (v0.2.0). */
    fun migrateLegacy(legacy: ConfigStore) {
        if (loadAll().isNotEmpty()) return
        val f = legacy.load() ?: return
        val (cfg, errs) = ConfigValidator.validate(f)
        if (cfg == null) return
        val p = profileFromForm(null, f, cfg)
        saveAll(listOf(p)); setActiveId(p.id)
    }

    fun exportJson(): String {
        val arr = JSONArray()
        loadAll().forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("name", p.name); o.put("type", p.type.name)
            o.put("host", p.host); o.put("port", p.port)
            o.put("username", p.username ?: ""); o.put("password", p.password ?: "")
            o.put("tls", p.tls); o.put("testUrl", p.testUrl)
            arr.put(o)
        }
        val root = JSONObject(); root.put("app", "NERO Proxy"); root.put("version", 1); root.put("profiles", arr)
        return root.toString(2)
    }

    /** Returns (importedCount, errors). Bad entries are skipped, good ones are kept. */
    fun importJson(text: String): Pair<Int, List<String>> {
        val errors = mutableListOf<String>()
        val parsed = try { JSONObject(text) } catch (e: Exception) { errors += "Not a valid JSON file."; return 0 to errors }
        val arr = try { parsed.getJSONArray("profiles") } catch (e: Exception) { errors += "No 'profiles' list in this file."; return 0 to errors }
        val current = loadAll().toMutableList()
        var count = 0
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val f = FormState(
                    type = runCatching { ProxyType.valueOf(o.optString("type", "SOCKS5")) }.getOrDefault(ProxyType.SOCKS5),
                    host = o.optString("host", ""), port = o.optInt("port", 0).toString(),
                    username = o.optString("username", ""), password = o.optString("password", ""),
                    testUrl = o.optString("testUrl", "https://example.com/"),
                    name = o.optString("name", ""), tls = o.optBoolean("tls", false),
                )
                val (cfg, errs) = ConfigValidator.validate(f)
                if (cfg == null) { errors += "Skipped '${f.name.ifBlank { f.host }}': ${errs.firstOrNull() ?: "invalid"}"; continue }
                val existingId = o.optString("id", "")
                val p = profileFromForm(if (existingId.isNotEmpty() && current.none { it.id == existingId }) existingId else null, f, cfg)
                current.removeAll { it.id == p.id }
                current += p; count++
            } catch (e: Exception) { errors += "Skipped entry ${i + 1}: unreadable." }
        }
        if (count > 0) {
            saveAll(current)
            if (activeId() == null && current.isNotEmpty()) setActiveId(current.first().id)
        }
        return count to errors
    }
}
