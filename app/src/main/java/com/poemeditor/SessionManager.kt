package com.poemeditor

import java.io.File

object SessionManager {

    const val MAX_INSERTED_IMAGES = 5

    data class SessionMeta(
        val id: String, val name: String, val lastAccessed: Long,
        val wordCount: Int = 0, val imageCount: Int = 0
    ) {
        fun formattedDate(): String {
            val cal = java.util.Calendar.getInstance().also { it.timeInMillis = lastAccessed }
            val now = java.util.Calendar.getInstance()
            val time = "%02d:%02d".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE))
            val date = if (cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR))
                "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
            else
                "${cal.get(java.util.Calendar.YEAR)}/${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
            return "$date $time"
        }
    }

    fun sessionsDir(filesDir: File): File =
        File(filesDir, "poems").also { it.mkdirs() }

    private fun countWordsInJson(j: org.json.JSONObject): Int {
        val cols = j.optJSONArray("columnData") ?: return 0
        var count = 0
        for (c in 0 until cols.length()) {
            val rows = cols.optJSONArray(c) ?: continue
            for (r in 0 until rows.length()) {
                val ch = rows.optString(r)
                if (ch.isNotBlank() && ch != "​" && ch != "↵") count++
            }
        }
        return count
    }

    private fun countImagesInJson(j: org.json.JSONObject): Int =
        j.optJSONArray("insertedImages")?.length() ?: 0

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

    // ── Inserted-image JSON helpers ────────────────────────────────────

    fun parseInsertedImages(json: org.json.JSONArray?): MutableList<InsertedImageState> {
        if (json == null) return mutableListOf()
        val result = mutableListOf<InsertedImageState>()
        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            val uri = obj.optString("uri", "")
            if (uri.isEmpty()) continue
            val matArr = obj.optJSONArray("matrix")
            val matrix = if (matArr != null && matArr.length() == 9)
                FloatArray(9) { idx -> matArr.optDouble(idx).toFloat() }
            else null
            result.add(InsertedImageState(uri, matrix))
            if (result.size >= MAX_INSERTED_IMAGES) break
        }
        return result
    }

    fun insertedImagesToJsonString(images: List<InsertedImageState>): String {
        val arr = org.json.JSONArray()
        images.take(MAX_INSERTED_IMAGES).forEach { image ->
            val obj = org.json.JSONObject().put("uri", image.uri)
            image.matrix?.let { values ->
                val matArr = org.json.JSONArray()
                values.forEach { matArr.put(it.toDouble()) }
                obj.put("matrix", matArr)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    // ── File I/O ───────────────────────────────────────────────────────

    fun listSessions(filesDir: File): List<SessionMeta> =
        (sessionsDir(filesDir).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { file ->
                try {
                    val j = org.json.JSONObject(file.readText())
                    SessionMeta(
                        id = j.getString("id"),
                        name = j.getString("name"),
                        lastAccessed = j.getLong("lastAccessed"),
                        wordCount = countWordsInJson(j),
                        imageCount = countImagesInJson(j)
                    )
                } catch (_: Exception) { null }
            }
            .sortedByDescending { it.lastAccessed }

    fun saveSession(
        filesDir: File, id: String, name: String,
        columnData: List<List<String>>, columnBreaks: Set<Int>,
        fontIndex: Int, fontSizeSp: Float, wordGapDp: Float,
        gridTextColor: Int, bgColor: Int, bgImageUri: String?,
        bgImageMatrixValues: FloatArray?,
        inputMode: String,
        insertedImages: List<InsertedImageState> = emptyList(),
        activeImageIndex: Int = -1,
        gridPadTop: Int = 0, gridPadBottom: Int = 0,
        gridPadLeft: Int = 0, gridPadRight: Int = 0
    ) {
        val normalizedImages = insertedImages.take(5)
        val normalizedActiveIndex = activeImageIndex.coerceIn(-1, normalizedImages.lastIndex)
        val activeImage = normalizedImages.getOrNull(normalizedActiveIndex)
        val legacyUri = activeImage?.uri ?: bgImageUri
        val legacyMatrix = activeImage?.matrix ?: bgImageMatrixValues

        val j = org.json.JSONObject().apply {
            put("id", id); put("name", name)
            put("lastAccessed", System.currentTimeMillis())
            put("columnData", columnDataToJson(columnData))
            put("columnBreaks", columnBreaksToJson(columnBreaks))
            put("fontIndex", fontIndex); put("fontSizeSp", fontSizeSp.toDouble())
            put("wordGapDp", wordGapDp.toDouble()); put("gridTextColor", gridTextColor)
            put("bgColor", bgColor); put("bgImageUri", legacyUri ?: "")
            if (legacyMatrix != null) {
                val arr = org.json.JSONArray()
                legacyMatrix.forEach { arr.put(it.toDouble()) }
                put("bgImageMatrix", arr)
            }
            val imagesArr = org.json.JSONArray()
            normalizedImages.forEach { img ->
                val imgObj = org.json.JSONObject()
                    .put("uri", img.uri)
                img.matrix?.let { m ->
                    val mArr = org.json.JSONArray()
                    m.forEach { mArr.put(it.toDouble()) }
                    imgObj.put("matrix", mArr)
                }
                imagesArr.put(imgObj)
            }
            put("insertedImages", imagesArr)
            put("activeImageIndex", normalizedActiveIndex)
            put("inputMode", inputMode)
            put("gridPadTop",    gridPadTop)
            put("gridPadBottom", gridPadBottom)
            put("gridPadLeft",   gridPadLeft)
            put("gridPadRight",  gridPadRight)
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

    fun nextNewSessionName(filesDir: File, baseName: String): String {
        val names = listSessions(filesDir).map { it.name }.toSet()
        if (!names.contains(baseName)) return baseName
        var i = 2
        while (names.contains("$baseName $i")) i++
        return "$baseName $i"
    }

    fun renameSession(filesDir: File, id: String, newName: String) {
        val file = File(sessionsDir(filesDir), "$id.json")
        if (!file.exists()) return
        try {
            val j = org.json.JSONObject(file.readText())
            j.put("name", newName)
            j.put("lastAccessed", System.currentTimeMillis())
            file.writeText(j.toString())
        } catch (_: Exception) {}
    }

    fun ensureDefaultSession(
        filesDir: File, id: String, name: String,
        columnData: List<List<String>>, columnBreaks: Set<Int>,
        fontIndex: Int, fontSizeSp: Float, wordGapDp: Float,
        gridTextColor: Int, bgColor: Int, bgImageUri: String?,
        bgImageMatrixValues: FloatArray?,
        inputMode: String,
        insertedImages: List<InsertedImageState> = emptyList(),
        activeImageIndex: Int = -1,
        gridPadTop: Int = 0, gridPadBottom: Int = 0,
        gridPadLeft: Int = 0, gridPadRight: Int = 0
    ) {
        if (listSessions(filesDir).isEmpty()) {
            saveSession(filesDir, id, name, columnData, columnBreaks,
                fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri,
                bgImageMatrixValues, inputMode, insertedImages, activeImageIndex,
                gridPadTop, gridPadBottom, gridPadLeft, gridPadRight)
        }
    }
}
