package dev.sebastiano.camerasync

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] fake for plain-JVM tests (Fakes over Mocks per CLAUDE.md).
 *
 * Covers the API subset used by [dev.sebastiano.camerasync.usb.PhotoSyncManager] and
 * [dev.sebastiano.camerasync.usb.UsbSyncPreferences]: get/put for all primitive types and string
 * sets, contains, getAll, edit (apply/commit), clear, remove, and listener registration.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val store = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = store.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        store[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (store[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        store[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = key != null && store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners.add(it) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        listener?.let { listeners.remove(it) }
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            pending[checkNotNull(key)] = value
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { pending[checkNotNull(key)] = values }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            pending[checkNotNull(key)] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            pending[checkNotNull(key)] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            pending[checkNotNull(key)] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            pending[checkNotNull(key)] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            pending[checkNotNull(key)] = null
        }

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() {
            applyChanges()
        }

        private fun applyChanges() {
            if (clearRequested) {
                store.clear()
                clearRequested = false
            }
            pending.forEach { (key, value) ->
                if (value == null) store.remove(key) else store[key] = value
            }
            pending.clear()
        }
    }
}
