package com.shilapi.xcertplay

import android.content.Context

/** Offline longest-match conversion using the Apache-2.0 OpenCC t2s dictionaries.
 * Works on API 28, where Android's public ICU Transliterator is unavailable.
 */
internal class InstrumentChineseText(private val context: Context) {
    private class Node {
        val children = HashMap<Char, Node>()
        var value: String? = null
    }
    private val root by lazy {
        Node().also { root ->
            for (file in listOf("TSCharacters.txt", "TSPhrases.txt")) {
                context.assets.open("opencc/$file").bufferedReader().useLines { rows ->
                    rows.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { row ->
                        val parts = row.split('\t', limit = 2)
                        if (parts.size == 2) {
                            var node = root
                            parts[0].forEach { node = node.children.getOrPut(it) { Node() } }
                            node.value = parts[1].substringBefore(' ')
                        }
                    }
                }
            }
        }
    }
    private val cache = object : LinkedHashMap<String, String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }

    @Synchronized fun simplified(text: String): String = cache.getOrPut(text) {
        buildString {
            var position = 0
            while (position < text.length) {
                var node = root
                var end = position
                var matchedEnd = position
                var replacement: String? = null
                while (end < text.length) {
                    node = node.children[text[end]] ?: break
                    end++
                    node.value?.let { replacement = it; matchedEnd = end }
                }
                if (replacement != null) { append(replacement); position = matchedEnd }
                else { append(text[position]); position++ }
            }
        }
    }
}
