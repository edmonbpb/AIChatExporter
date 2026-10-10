package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.CodeSnippet
import org.json.JSONArray
import org.json.JSONObject

class Converters {

    @TypeConverter
    fun fromStringList(list: List<String>?): String {
        if (list == null) return "[]"
        val array = JSONArray()
        list.forEach { array.put(it) }
        return array.toString()
    }

    @TypeConverter
    fun toStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val list = mutableListOf<String>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
        } catch (_: Exception) {}
        return list
    }

    @TypeConverter
    fun fromCodeSnippetList(list: List<CodeSnippet>?): String {
        if (list == null) return "[]"
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("language", item.language)
            obj.put("code", item.code)
            array.put(obj)
        }
        return array.toString()
    }

    @TypeConverter
    fun toCodeSnippetList(json: String?): List<CodeSnippet> {
        if (json.isNullOrBlank()) return emptyList()
        val list = mutableListOf<CodeSnippet>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CodeSnippet(
                        id = obj.optString("id"),
                        language = obj.optString("language", "plaintext"),
                        code = obj.optString("code", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}
