package com.example

import com.example.data.secure.KeyStorage

/** The secrets store a JVM test can hold: one map, and a note of every name written to it. */
class InMemoryKeyStorage : KeyStorage {
    override val backingFile = "memory"
    override val isEncrypted = false
    val written = mutableListOf<String>()
    private val values = LinkedHashMap<String, String>()

    override fun read(name: String): String = values[name]?.trim().orEmpty()

    override fun write(name: String, value: String) {
        written += name
        if (value.isBlank()) values.remove(name) else values[name] = value
    }
}
