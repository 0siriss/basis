package app.basis.pipeline.summary

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Structured summary of a period (hour or day), exactly what the LLM is asked to produce. */
data class Digest(
    val summary: String = "",
    val events: List<String> = emptyList(),
    val agreements: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val ideas: List<String> = emptyList(),
    val mood: String = "",
) {
    val isEmpty: Boolean
        get() = summary.isBlank() && events.isEmpty() && agreements.isEmpty() && tasks.isEmpty() && ideas.isEmpty()

    fun toJson(): String = JSONObject()
        .put("summary", summary)
        .put("events", JSONArray(events))
        .put("agreements", JSONArray(agreements))
        .put("tasks", JSONArray(tasks))
        .put("ideas", JSONArray(ideas))
        .put("mood", mood)
        .toString()

    companion object {
        /**
         * Parses model output; tolerates text around the JSON object and an answer cut off by the token
         * limit (closes the open string/list/object, drops a half-written key). Throws if nothing usable.
         */
        fun parse(raw: String): Digest {
            val start = raw.indexOf('{')
            if (start < 0) throw JSONException("no JSON object in output")
            val end = raw.lastIndexOf('}')
            val o = (if (end > start) runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() else null)
                ?: JSONObject(repairTruncated(raw.substring(start)) ?: throw JSONException("no JSON object in output"))
            fun list(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf(String::isNotEmpty) }.distinct()
            }
            return Digest(
                summary = o.optString("summary").trim(),
                events = list("events"),
                agreements = list("agreements"),
                tasks = list("tasks"),
                ideas = list("ideas"),
                mood = o.optString("mood").trim(),
            )
        }

        /**
         * Completes a JSON object truncated mid-way: remembers the last point where a value had just ended
         * (plus the brackets open there), cuts there and closes the brackets. An unfinished string value is
         * kept (closed with "…"); an unfinished key is dropped. Returns null if no value was ever completed.
         */
        internal fun repairTruncated(s: String): String? {
            val stack = StringBuilder() // '{' or '['
            var inString = false
            var escape = false
            var stringIsKey = false
            var expectKey = false // inside an object, before a key
            var safeEnd = -1
            var safeStack = ""
            var valueStringStart = -1
            for (i in s.indices) {
                val c = s[i]
                if (inString) {
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        c == '"' -> {
                            inString = false
                            if (!stringIsKey) { safeEnd = i + 1; safeStack = stack.toString() }
                        }
                    }
                    continue
                }
                when (c) {
                    '"' -> {
                        inString = true
                        stringIsKey = stack.lastOrNull() == '{' && expectKey
                        if (!stringIsKey) valueStringStart = i
                    }
                    ':' -> expectKey = false
                    ',' -> expectKey = stack.lastOrNull() == '{'
                    '{' -> { stack.append('{'); expectKey = true }
                    '[' -> stack.append('[')
                    '}', ']' -> {
                        if (stack.isNotEmpty()) stack.setLength(stack.length - 1)
                        expectKey = false
                        safeEnd = i + 1; safeStack = stack.toString()
                        if (stack.isEmpty()) return s.substring(0, i + 1)
                    }
                }
            }
            val sb = StringBuilder()
            var open: String
            if (inString && !stringIsKey && valueStringStart >= 0) {
                // Keep the cut string value; drop a dangling backslash.
                var body = s.substring(0, s.length).trimEnd()
                if (escape) body = body.dropLast(1)
                sb.append(body).append("…\"")
                open = stack.toString()
            } else {
                if (safeEnd < 0) return null
                sb.append(s, 0, safeEnd)
                open = safeStack
            }
            for (b in open.reversed()) sb.append(if (b == '{') '}' else ']')
            return sb.toString()
        }

        /**
         * GBNF grammar (llama.cpp) forcing exactly this JSON shape, so even a small model always returns
         * parseable output. Strings exclude raw control characters; whitespace is bounded.
         */
        val GRAMMAR = """
            root ::= "{" ws "\"summary\":" ws str "," ws "\"events\":" ws arr "," ws "\"agreements\":" ws arr "," ws "\"tasks\":" ws arr "," ws "\"ideas\":" ws arr "," ws "\"mood\":" ws str ws "}"
            arr ::= "[" ws ( str ( "," ws str ){0,9} )? ws "]"
            str ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] ){0,600} "\""
            ws ::= | " " | "\n" [ \t]{0,8}
        """.trimIndent()
    }
}
