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
        /** Parses model output; tolerates text around the JSON object. Throws if no valid object. */
        fun parse(raw: String): Digest {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) throw JSONException("no JSON object in output")
            val o = JSONObject(raw.substring(start, end + 1))
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
         * GBNF grammar (llama.cpp) forcing exactly this JSON shape, so even a small model always returns
         * parseable output. Strings exclude raw control characters; whitespace is bounded.
         */
        val GRAMMAR = """
            root ::= "{" ws "\"summary\":" ws str "," ws "\"events\":" ws arr "," ws "\"agreements\":" ws arr "," ws "\"tasks\":" ws arr "," ws "\"ideas\":" ws arr "," ws "\"mood\":" ws str ws "}"
            arr ::= "[" ws ( str ( "," ws str ){0,15} )? ws "]"
            str ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] ){0,600} "\""
            ws ::= | " " | "\n" [ \t]{0,8}
        """.trimIndent()
    }
}
