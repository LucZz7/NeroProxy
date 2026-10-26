package com.necxa.proxy.ui

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.necxa.proxy.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val packageName: String, val label: String)

class ProxyViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val legacy = ConfigStore(app)

    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val activeId = MutableStateFlow<String?>(null)
    val darkMode = MutableStateFlow(false)
    val filterMode = MutableStateFlow(FilterMode.ALL)
    val filterPackages = MutableStateFlow<Set<String>>(emptySet())
    val form = MutableStateFlow(FormState())
    val errors = MutableStateFlow<List<String>>(emptyList())
    val steps = MutableStateFlow<List<StepResult>>(emptyList())
    val testing = MutableStateFlow(false)
    val importResult = MutableStateFlow<String?>(null)
    val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val appsLoading = MutableStateFlow(false)

    init {
        store.migrateLegacy(legacy)
        refresh()
    }

    fun refresh() {
        profiles.value = store.loadAll()
        activeId.value = store.active()?.id
        darkMode.value = store.darkMode()
        filterMode.value = store.filterMode()
        filterPackages.value = store.filterPackages()
    }

    fun activeProfile(): Profile? = profiles.value.firstOrNull { it.id == activeId.value } ?: profiles.value.firstOrNull()

    fun update(f: FormState) { form.value = f }
    fun newForm() { form.value = FormState(); errors.value = emptyList(); steps.value = emptyList() }
    fun editForm(p: Profile) { form.value = p.toForm(); errors.value = emptyList(); steps.value = emptyList() }

    /** Saves the current form as a new profile, or updates [existingId]. Returns true on success. */
    fun saveProfile(existingId: String?): Boolean {
        val (cfg, e) = ConfigValidator.validate(form.value)
        errors.value = e
        if (cfg == null) return false
        val p = profileFromForm(existingId, form.value, cfg)
        val list = profiles.value.toMutableList()
        list.removeAll { it.id == p.id }
        list += p
        store.saveAll(list)
        if (activeId.value == null || existingId == null && list.size == 1) store.setActiveId(p.id)
        if (existingId != null && activeId.value == existingId) store.setActiveId(p.id)
        refresh()
        return true
    }

    fun deleteProfile(id: String) {
        val list = profiles.value.filter { it.id != id }
        store.saveAll(list)
        if (activeId.value == id) store.setActiveId(list.firstOrNull()?.id)
        refresh()
    }

    fun setActive(id: String) { store.setActiveId(id); refresh() }

    fun testForm() {
        val (cfg, e) = ConfigValidator.validate(form.value)
        errors.value = e
        if (cfg == null || testing.value) return
        viewModelScope.launch {
            testing.value = true; steps.value = emptyList()
            steps.value = ConnectionTester.run(cfg, form.value.testUrl)
            testing.value = false
        }
    }

    fun testActive() {
        val p = activeProfile() ?: return
        if (testing.value) return
        viewModelScope.launch {
            testing.value = true; steps.value = emptyList()
            steps.value = ConnectionTester.run(p.toConfig(), p.testUrl)
            testing.value = false
        }
    }

    fun exportJson(): String = store.exportJson()

    fun importJsonText(text: String) {
        val (count, errs) = store.importJson(text)
        importResult.value = if (count > 0) "Imported $count profile(s)." + if (errs.isEmpty()) "" else " Skipped: " + errs.joinToString("; ")
            else "Nothing imported. " + errs.joinToString("; ")
        errors.value = errs
        refresh()
    }

    fun toggleDark(v: Boolean) { store.setDarkMode(v); darkMode.value = v }
    fun setFilter(m: FilterMode) { store.setFilterMode(m); filterMode.value = m }
    fun toggleFilterApp(pkg: String) {
        val s = filterPackages.value.toMutableSet()
        if (!s.add(pkg)) s.remove(pkg)
        store.setFilterPackages(s); filterPackages.value = s
    }

    fun loadApps() {
        if (appsLoading.value || apps.value.isNotEmpty()) return
        viewModelScope.launch {
            appsLoading.value = true
            val list = withContext(Dispatchers.IO) {
                try {
                    val pm = getApplication<Application>().packageManager
                    val own = getApplication<Application>().packageName
                    @Suppress("DEPRECATION")
                    val infos: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= 33)
                        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                    else pm.getInstalledApplications(0)
                    infos.filter { it.packageName != own }
                        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                        .sortedBy { it.label.lowercase() }
                } catch (_: Exception) { emptyList() }
            }
            apps.value = list; appsLoading.value = false
        }
    }
}
