package com.freescript

import java.io.File

object SessionManager {

    const val MAX_INSERTED_IMAGES = 5

    data class SessionMeta(
        val id: String, val name: String, val lastAccessed: Long,
        val wordCount: Int = 0, val imageCount: Int = 0,
        val canvasMode: CanvasMode = CanvasMode.VERTICAL,
        /** Folder path relative to poems/ (empty string = root). */
        val folder: String = ""
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

    // ── Folder helpers ────────────────────────────────────────────────────

    /** Recursively yields every {id}.json file under poems/. */
    private fun walkSessionFiles(root: File): Sequence<File> = sequence {
        val files = root.listFiles() ?: return@sequence
        for (f in files) {
            if (f.isDirectory) yieldAll(walkSessionFiles(f))
            else if (f.extension == "json") yield(f)
        }
    }

    /** Returns the folder path of [file] relative to [poemsDir] (empty = root). */
    private fun folderOf(file: File, poemsDir: File): String {
        val parent = file.parentFile ?: return ""
        val poemsPath = poemsDir.absolutePath
        val parentPath = parent.absolutePath
        if (parentPath == poemsPath) return ""
        return parentPath.removePrefix("$poemsPath/").removePrefix("$poemsPath\\")
            .replace('\\', '/')
    }

    /** Find the JSON file for a session id, anywhere under poems/. Null if missing. */
    fun findSessionFile(filesDir: File, id: String): File? {
        val poems = sessionsDir(filesDir)
        if (!poems.exists()) return null
        return walkSessionFiles(poems).firstOrNull { it.nameWithoutExtension == id }
    }

    /** All folder paths (relative to poems/), depth-first. Empty list = no folders. */
    fun listFolders(filesDir: File): List<String> {
        val poems = sessionsDir(filesDir)
        val out = mutableListOf<String>()
        fun walk(dir: File, prefix: String) {
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (!f.isDirectory) continue
                val name = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                out.add(name)
                walk(f, name)
            }
        }
        walk(poems, "")
        return out
    }

    /** Create an empty folder under poems/. Idempotent. */
    fun createFolder(filesDir: File, folderPath: String): Boolean {
        if (folderPath.isBlank()) return false
        return File(sessionsDir(filesDir), folderPath).mkdirs()
    }

    /** Delete a folder and every session inside it. */
    fun deleteFolder(filesDir: File, folderPath: String): Boolean {
        val dir = File(sessionsDir(filesDir), folderPath)
        if (!dir.exists() || !dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    /** Rename a folder. Fails if the target already exists. Sessions inside follow the
     *  rename via their directory move, so SessionMeta.folder reflects the new name on
     *  the next listSessions(). */
    fun renameFolder(filesDir: File, oldPath: String, newPath: String): Boolean {
        if (oldPath.isBlank() || newPath.isBlank() || oldPath == newPath) return false
        val poems = sessionsDir(filesDir)
        val src = File(poems, oldPath)
        if (!src.exists() || !src.isDirectory) return false
        val dst = File(poems, newPath)
        if (dst.exists()) return false
        return src.renameTo(dst)
    }

    /** Move a session to a new folder (empty = root). Returns true on success. */
    fun moveSession(filesDir: File, id: String, targetFolder: String): Boolean {
        val src = findSessionFile(filesDir, id) ?: return false
        val poems = sessionsDir(filesDir)
        val targetDir = if (targetFolder.isEmpty()) poems
                        else File(poems, targetFolder).also { it.mkdirs() }
        val dst = File(targetDir, src.name)
        if (src.absolutePath == dst.absolutePath) return true
        return src.renameTo(dst)
    }

    /** Returns a folder name that doesn't already exist under poems/, based on [baseName]. */
    fun nextNewFolderName(filesDir: File, baseName: String): String {
        val existing = listFolders(filesDir).toSet()
        if (!existing.contains(baseName)) return baseName
        var i = 2
        while (existing.contains("$baseName $i")) i++
        return "$baseName $i"
    }

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

    // ── TextBox JSON helpers ───────────────────────────────────────────────

    fun textBoxesToJson(textBoxes: List<TextBoxInstance>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        textBoxes.forEach { box ->
            val obj = org.json.JSONObject().apply {
                put("id", box.id)
                put("leftPx", box.leftPx.toDouble())
                put("topPx", box.topPx.toDouble())
                put("colCount", box.colCount)
                put("rowCount", box.rowCount)
                put("columnData", columnDataToJson(box.columnData))
                put("columnBreaks", columnBreaksToJson(box.columnBreaks))
                put("fontIndex", box.fontIndex)
                put("fontSizeSp", box.fontSizeSp.toDouble())
                put("wordGapDp", box.wordGapDp.toDouble())
                put("gridTextColor", box.gridTextColor)
                put("inputMode", box.inputMode.name)
                put("isHorizontal", box.isHorizontal)
                put("boxBgColor", box.boxBgColor)
                put("borderVisible", box.borderVisible)
                put("borderColor", box.borderColor)
                put("borderThicknessIdx", box.borderThicknessIdx)
            }
            arr.put(obj)
        }
        return arr
    }

    fun parseTextBoxes(json: org.json.JSONArray?): MutableList<TextBoxInstance> {
        if (json == null) return mutableListOf()
        val result = mutableListOf<TextBoxInstance>()
        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            val id = obj.optString("id", java.util.UUID.randomUUID().toString())
            val colData = try { loadColumnDataFromJson(obj.getJSONArray("columnData")) }
                          catch (_: Exception) { mutableListOf() }
            val colBreaks = try { loadColumnBreaksFromJson(obj.getJSONArray("columnBreaks")) }
                            catch (_: Exception) { mutableSetOf() }
            result.add(TextBoxInstance(
                id           = id,
                leftPx       = obj.optDouble("leftPx", 0.0).toFloat(),
                topPx        = obj.optDouble("topPx", 0.0).toFloat(),
                colCount     = obj.optInt("colCount", 2),
                rowCount     = obj.optInt("rowCount", 2),
                columnData   = colData,
                columnBreaks = colBreaks,
                fontIndex    = obj.optInt("fontIndex", 0),
                fontSizeSp   = obj.optDouble("fontSizeSp", 24.0).toFloat(),
                wordGapDp    = obj.optDouble("wordGapDp", 0.0).toFloat(),
                gridTextColor = obj.optInt("gridTextColor", android.graphics.Color.BLACK),
                inputMode    = try { InputMode.valueOf(obj.optString("inputMode", "SEQUENTIAL")) }
                               catch (_: Exception) { InputMode.SEQUENTIAL },
                isHorizontal = obj.optBoolean("isHorizontal", false),
                boxBgColor   = obj.optInt("boxBgColor", android.graphics.Color.TRANSPARENT),
                borderVisible = obj.optBoolean("borderVisible", true),
                borderColor   = obj.optInt("borderColor", android.graphics.Color.parseColor("#CCCCCC")),
                borderThicknessIdx = obj.optInt("borderThicknessIdx", 1)
            ))
        }
        return result
    }

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

    private fun matrixToJson(values: FloatArray): org.json.JSONArray =
        org.json.JSONArray().also { arr -> values.forEach { arr.put(it.toDouble()) } }

    private fun insertedImagesToJson(images: List<InsertedImageState>): org.json.JSONArray =
        org.json.JSONArray().also { arr ->
            images.take(MAX_INSERTED_IMAGES).forEach { image ->
                val obj = org.json.JSONObject().put("uri", image.uri)
                image.matrix?.let { obj.put("matrix", matrixToJson(it)) }
                arr.put(obj)
            }
        }

    fun insertedImagesToJsonString(images: List<InsertedImageState>): String =
        insertedImagesToJson(images).toString()

    // ── Session JSON → SessionDocument ────────────────────────────────

    fun parseSessionJson(j: org.json.JSONObject): SessionDocument {
        val colData   = try { loadColumnDataFromJson(j.getJSONArray("columnData")) }
                        catch (_: Exception) { mutableListOf() }
        val colBreaks = try { loadColumnBreaksFromJson(j.getJSONArray("columnBreaks")) }
                        catch (_: Exception) { mutableSetOf() }
        val canvasModeStr = j.optString("canvasMode", "VERTICAL")

        // Legacy horizontalText migration: populate columnData from plain-text field
        val legacyHText = j.optString("horizontalText", "")
        if (canvasModeStr == "HORIZONTAL" && colData.isEmpty() && legacyHText.isNotEmpty()) {
            legacyHText.split('\n').forEach { line ->
                colData.add(line.map { it.toString() }.toMutableList())
            }
        }

        val matArr    = j.optJSONArray("bgImageMatrix")
        val matValues = if (matArr != null && matArr.length() == 9)
            FloatArray(9) { i -> matArr.getDouble(i).toFloat() } else null
        val loadedImages = parseInsertedImages(j.optJSONArray("insertedImages"))
        val legacyUri = j.optString("bgImageUri", "").ifEmpty { null }
        if (loadedImages.isEmpty() && !legacyUri.isNullOrEmpty()) {
            loadedImages.add(InsertedImageState(legacyUri, matValues))
        }
        val activeIdx = j.optInt("activeImageIndex", if (loadedImages.isNotEmpty()) 0 else -1)

        return SessionDocument(
            id            = j.optString("id", java.util.UUID.randomUUID().toString()),
            name          = j.optString("name", ""),
            canvasMode    = canvasModeStr,
            columnData    = colData,
            columnBreaks  = colBreaks,
            fontIndex     = j.optInt("fontIndex", 0),
            fontSizeSp    = j.optDouble("fontSizeSp", 24.0).toFloat(),
            wordGapDp     = j.optDouble("wordGapDp",  0.0).toFloat(),
            gridTextColor = j.optInt("gridTextColor", android.graphics.Color.BLACK),
            bgColor       = j.optInt("bgColor",       android.graphics.Color.WHITE),
            bgImageUri    = legacyUri,
            bgImageMatrix = matValues,
            inputMode     = j.optString("inputMode", "SEQUENTIAL"),
            insertedImages     = loadedImages,
            activeImageIndex   = activeIdx,
            gridPadTop    = j.optInt("gridPadTop",    0),
            gridPadBottom = j.optInt("gridPadBottom", 0),
            gridPadLeft   = j.optInt("gridPadLeft",   0),
            gridPadRight  = j.optInt("gridPadRight",  0),
            textBoxes     = parseTextBoxes(j.optJSONArray("textBoxes")),
            horizontalText = legacyHText,
            screenshotCropLeft   = if (j.has("cropL")) j.getInt("cropL") else null,
            screenshotCropTop    = if (j.has("cropT")) j.getInt("cropT") else null,
            screenshotCropRight  = if (j.has("cropR")) j.getInt("cropR") else null,
            screenshotCropBottom = if (j.has("cropB")) j.getInt("cropB") else null
        )
    }

    // ── File I/O ───────────────────────────────────────────────────────

    fun listSessions(filesDir: File): List<SessionMeta> {
        val poems = sessionsDir(filesDir)
        return walkSessionFiles(poems).mapNotNull { file ->
            try {
                val j = org.json.JSONObject(file.readText())
                val cachedWordCount  = j.optInt("wordCount",  -1)
                val cachedImageCount = j.optInt("imageCount", -1)
                SessionMeta(
                    id = j.getString("id"),
                    name = j.getString("name"),
                    lastAccessed = j.getLong("lastAccessed"),
                    wordCount  = if (cachedWordCount  >= 0) cachedWordCount  else countWordsInJson(j),
                    imageCount = if (cachedImageCount >= 0) cachedImageCount else countImagesInJson(j),
                    canvasMode = try { CanvasMode.valueOf(j.optString("canvasMode", "VERTICAL")) }
                                 catch (_: Exception) { CanvasMode.VERTICAL },
                    folder = folderOf(file, poems)
                )
            } catch (_: Exception) { null }
        }.toList().sortedByDescending { it.lastAccessed }
    }

    /**
     * Save a session. When [folder] is null, an existing session is rewritten in place
     * (keeping its current subdirectory). For new sessions [folder] selects the target
     * directory ("" = root). Passing a non-null [folder] for an existing session moves
     * the file to that folder.
     */
    fun saveSession(filesDir: File, doc: SessionDocument, folder: String? = null) {
        val normalizedImages = doc.insertedImages.take(MAX_INSERTED_IMAGES)
        val normalizedActiveIndex = doc.activeImageIndex.coerceIn(-1, normalizedImages.lastIndex)
        val activeImage = normalizedImages.getOrNull(normalizedActiveIndex)
        val legacyUri = activeImage?.uri ?: doc.bgImageUri
        val legacyMatrix = activeImage?.matrix ?: doc.bgImageMatrix

        val poems = sessionsDir(filesDir)
        val existing = findSessionFile(filesDir, doc.id)
        val resolvedFolder = folder ?: existing?.let { folderOf(it, poems) } ?: ""
        val targetDir = if (resolvedFolder.isEmpty()) poems
                        else File(poems, resolvedFolder).also { it.mkdirs() }
        val targetFile = File(targetDir, "${doc.id}.json")
        // If the session was previously stored in a different folder, drop the old file
        // before writing the new one so we don't end up with two copies.
        if (existing != null && existing.absolutePath != targetFile.absolutePath) {
            existing.delete()
        }

        val j = org.json.JSONObject().apply {
            put("id", doc.id); put("name", doc.name)
            put("lastAccessed", System.currentTimeMillis())
            put("columnData", columnDataToJson(doc.columnData))
            put("columnBreaks", columnBreaksToJson(doc.columnBreaks))
            put("fontIndex", doc.fontIndex); put("fontSizeSp", doc.fontSizeSp.toDouble())
            put("wordGapDp", doc.wordGapDp.toDouble()); put("gridTextColor", doc.gridTextColor)
            put("bgColor", doc.bgColor); put("bgImageUri", legacyUri ?: "")
            if (legacyMatrix != null) put("bgImageMatrix", matrixToJson(legacyMatrix))
            put("insertedImages", insertedImagesToJson(normalizedImages))
            put("activeImageIndex", normalizedActiveIndex)
            put("inputMode", doc.inputMode)
            put("gridPadTop",    doc.gridPadTop)
            put("gridPadBottom", doc.gridPadBottom)
            put("gridPadLeft",   doc.gridPadLeft)
            put("gridPadRight",  doc.gridPadRight)
            put("canvasMode", doc.canvasMode)
            put("horizontalText", doc.horizontalText)
            put("textBoxes", textBoxesToJson(doc.textBoxes))
            doc.screenshotCropLeft?.let   { put("cropL", it) }
            doc.screenshotCropTop?.let    { put("cropT", it) }
            doc.screenshotCropRight?.let  { put("cropR", it) }
            doc.screenshotCropBottom?.let { put("cropB", it) }
            // Cache derived counts so listSessions() can read them without re-parsing all content
            put("wordCount",  countWordsInJson(this))
            put("imageCount", normalizedImages.size)
        }
        targetFile.writeText(j.toString())
    }

    // Reads session JSON and bumps lastAccessed timestamp on disk. Returns null if missing/corrupt.
    fun loadSession(filesDir: File, id: String): org.json.JSONObject? {
        val file = findSessionFile(filesDir, id) ?: return null
        return try {
            val j = org.json.JSONObject(file.readText())
            j.put("lastAccessed", System.currentTimeMillis())
            file.writeText(j.toString())
            j
        } catch (_: Exception) { null }
    }

    fun deleteSession(filesDir: File, id: String) {
        findSessionFile(filesDir, id)?.delete()
    }

    fun nextNewSessionName(filesDir: File, baseName: String): String {
        val names = listSessions(filesDir).map { it.name }.toSet()
        if (!names.contains(baseName)) return baseName
        var i = 2
        while (names.contains("$baseName $i")) i++
        return "$baseName $i"
    }

    fun renameSession(filesDir: File, id: String, newName: String) {
        val file = findSessionFile(filesDir, id) ?: return
        try {
            val j = org.json.JSONObject(file.readText())
            j.put("name", newName)
            j.put("lastAccessed", System.currentTimeMillis())
            file.writeText(j.toString())
        } catch (_: Exception) {}
    }

    fun ensureDefaultSession(filesDir: File, doc: SessionDocument) {
        if (listSessions(filesDir).isEmpty()) {
            saveSession(filesDir, doc)
        }
    }
}
