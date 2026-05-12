package com.poemeditor

import java.io.File

object SessionManager {

    data class SessionMeta(val id: String, val name: String, val lastAccessed: Long) {
        fun formattedDate(): String {
            val cal = java.util.Calendar.getInstance().also { it.timeInMillis = lastAccessed }
            return "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
        }
    }

    fun sessionsDir(filesDir: File): File =
        File(filesDir, "poems").also { it.mkdirs() }

    // ── JSON helpers ───────────────────────────────────────────────────

    fun columnDataToJson(columnData: List<List<String>>): org.json.JSONArray {
        val cols = org.json.JSONArray()
        for (col in columnData) {
            val rows = org.json.JSONArray()
            for (ch in col) rows.put(ch)
            cols.put(rows)
        }
        return cols
    }

    fun columnBreaksToJson(columnBreaks: Set<Int>): org.json.JSONArray =
        org.json.JSONArray().also { arr -> for (b in columnBreaks) arr.put(b) }

    fun loadColumnDataFromJson(cols: org.json.JSONArray): MutableList<MutableList<String>> {
        val result = mutableListOf<MutableList<String>>()
        for (i in 0 until cols.length()) {
            val rows = cols.getJSONArray(i)
            result.add((0 until rows.length()).mapTo(mutableListOf()) { rows.getString(it) })
        }
        return result
    }

    fun loadColumnBreaksFromJson(breaks: org.json.JSONArray): MutableSet<Int> =
        mutableSetOf<Int>().also { s -> for (i in 0 until breaks.length()) s.add(breaks.getInt(i)) }

    // ── File I/O ───────────────────────────────────────────────────────

    fun listSessions(filesDir: File): List<SessionMeta> =
        (sessionsDir(filesDir).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { file ->
                try {
                    val j = org.json.JSONObject(file.readText())
                    SessionMeta(j.getString("id"), j.getString("name"), j.getLong("lastAccessed"))
                } catch (_: Exception) { null }
            }
            .sortedByDescending { it.lastAccessed }

    fun saveSession(
        filesDir: File, id: String, name: String,
        columnData: List<List<String>>, columnBreaks: Set<Int>,
        fontIndex: Int, fontSizeSp: Float, wordGapDp: Float,
        gridTextColor: Int, bgColor: Int, bgImageUri: String?, inputMode: String
    ) {
        val j = org.json.JSONObject().apply {
            put("id", id); put("name", name)
            put("lastAccessed", System.currentTimeMillis())
            put("columnData", columnDataToJson(columnData))
            put("columnBreaks", columnBreaksToJson(columnBreaks))
            put("fontIndex", fontIndex); put("fontSizeSp", fontSizeSp.toDouble())
            put("wordGapDp", wordGapDp.toDouble()); put("gridTextColor", gridTextColor)
            put("bgColor", bgColor); put("bgImageUri", bgImageUri ?: "")
            put("inputMode", inputMode)
        }
        File(sessionsDir(filesDir), "$id.json").writeText(j.toString())
    }

    // Reads session JSON and bumps lastAccessed timestamp on disk. Returns null if missing/corrupt.
    fun loadSession(filesDir: File, id: String): org.json.JSONObject? {
        val file = File(sessionsDir(filesDir), "$id.json")
        if (!file.exists()) return null
        return try {
            val j = org.json.JSONObject(file.readText())
            j.put("lastAccessed", System.currentTimeMillis())
            file.writeText(j.toString())
            j
        } catch (_: Exception) { null }
    }

    fun deleteSession(filesDir: File, id: String) {
        File(sessionsDir(filesDir), "$id.json").delete()
    }

    fun nextNewSessionName(filesDir: File): String {
        val names = listSessions(filesDir).map { it.name }.toSet()
        if (!names.contains("文檔")) return "文檔"
        var i = 2
        while (names.contains("文檔 $i")) i++
        return "文檔 $i"
    }

    fun ensureDefaultSession(
        filesDir: File, id: String, name: String,
        columnData: List<List<String>>, columnBreaks: Set<Int>,
        fontIndex: Int, fontSizeSp: Float, wordGapDp: Float,
        gridTextColor: Int, bgColor: Int, bgImageUri: String?, inputMode: String
    ) {
        if (listSessions(filesDir).isEmpty()) {
            saveSession(filesDir, id, name, columnData, columnBreaks,
                fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri, inputMode)
        }
    }
}
