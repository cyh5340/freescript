package com.poemeditor

import java.io.File

class SessionRepository(private val filesDir: File) {

    fun saveSession(doc: SessionDocument) = SessionManager.saveSession(filesDir, doc)

    fun loadSession(id: String): org.json.JSONObject? = SessionManager.loadSession(filesDir, id)

    fun ensureDefaultSession(doc: SessionDocument) = SessionManager.ensureDefaultSession(filesDir, doc)
}
