package co.twinotify.core.service

import org.json.JSONObject

/** Scans the wire spelling before JSONObject can reorder or rewrite an envelope. */
internal object RawRelayJson {
    fun objectMembers(raw: String): Map<String, IntRange> {
        val scanner = Scanner(raw)
        scanner.space()
        val fields = scanner.obj(0)
        scanner.space()
        require(scanner.index == raw.length) { "trailing relay JSON" }
        return fields
    }

    private class Scanner(private val raw: String) {
        var index = 0
        fun space() { while (raw.getOrNull(index) in listOf(' ', '\t', '\r', '\n')) index++ }
        private fun take(char: Char) {
            space()
            require(raw.getOrNull(index) == char) { "invalid relay JSON" }
            index++
        }
        fun obj(depth: Int): Map<String, IntRange> {
            require(depth <= 64) { "relay JSON nesting limit" }
            take('{')
            val fields = linkedMapOf<String, IntRange>()
            space()
            if (raw.getOrNull(index) == '}') { index++; return fields }
            while (true) {
                space()
                val start = index
                string()
                val name = JSONObject("{\"k\":${raw.substring(start, index)}}").getString("k")
                require(name !in fields) { "duplicate relay JSON key" }
                take(':'); space()
                val valueStart = index
                value(depth + 1)
                fields[name] = valueStart until index
                space()
                if (raw.getOrNull(index) == '}') { index++; return fields }
                take(',')
            }
        }
        private fun value(depth: Int) {
            require(depth <= 64) { "relay JSON nesting limit" }
            space()
            when (raw.getOrNull(index)) {
                '{' -> obj(depth)
                '[' -> {
                    index++; space()
                    if (raw.getOrNull(index) == ']') { index++; return }
                    while (true) {
                        value(depth + 1); space()
                        if (raw.getOrNull(index) == ']') { index++; break }
                        take(',')
                    }
                }
                '"' -> string()
                else -> {
                    val start = index
                    while (index < raw.length && raw[index] !in ",]} \t\r\n") index++
                    val token = raw.substring(start, index)
                    require(token in setOf("true", "false", "null") || NUMBER.matches(token)) { "invalid relay JSON value" }
                }
            }
        }
        private fun string() {
            take('"')
            while (index < raw.length) {
                when (val char = raw[index++]) {
                    '"' -> return
                    '\\' -> {
                        val escaped = raw.getOrNull(index++) ?: throw IllegalArgumentException("truncated relay JSON string")
                        if (escaped == 'u') {
                            repeat(4) { require(raw.getOrNull(index++)?.digitToIntOrNull(16) != null) { "invalid relay JSON escape" } }
                        } else require(escaped in "\"\\/bfnrt") { "invalid relay JSON escape" }
                    }
                    else -> require(char.code >= 0x20) { "invalid relay JSON string" }
                }
            }
            throw IllegalArgumentException("truncated relay JSON string")
        }
    }
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
