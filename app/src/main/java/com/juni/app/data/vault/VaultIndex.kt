package com.juni.app.data.vault

import com.juni.app.data.db.VaultDocEntity
import com.juni.app.data.db.VaultIndexDao
import com.juni.app.data.db.VaultMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Full-text search index over the vault's markdown files, backed by Room/FTS4.
 *
 * Why this exists: searching by walking the SAF tree on every query was doing
 * thousands of Binder round-trips through DocumentsProvider, which dominates
 * latency on Android. The index pays that cost once (per file change) and then
 * searches are a single SQLite MATCH query.
 *
 * Sync model:
 *   - On vault load, [ensureSynced] runs an incremental diff (compare on-disk
 *     `lastModified` against what's indexed; re-read changed files only).
 *   - If the vault URI itself changed, the whole index is wiped first.
 *   - Tools that mutate the vault call [upsertDoc] / [renamePath] / [deleteDoc]
 *     directly, so the index stays correct without waiting for the next sync.
 *   - External edits made outside the app (e.g. in Obsidian itself) are caught
 *     on the next app start, not in real time. Good enough for v1.
 */
class VaultIndex(private val dao: VaultIndexDao) {

    private val syncLock = Mutex()

    suspend fun ensureSynced(vault: VaultRepository, vaultUri: String) = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val meta = dao.getMeta()
            if (meta == null || meta.vaultUri != vaultUri) {
                dao.clearDocs()
                dao.clearMeta()
                fullRebuild(vault, vaultUri)
            } else {
                incrementalSync(vault, vaultUri)
            }
        }
    }

    private suspend fun fullRebuild(vault: VaultRepository, vaultUri: String) {
        for (file in vault.listAllMarkdown()) {
            val content = vault.read(file.relativePath) ?: continue
            dao.upsert(
                VaultDocEntity(
                    path = file.relativePath,
                    content = content,
                    lastModified = file.lastModified,
                ),
            )
        }
        dao.setMeta(
            VaultMetaEntity(vaultUri = vaultUri, lastFullSyncAt = System.currentTimeMillis()),
        )
    }

    private suspend fun incrementalSync(vault: VaultRepository, vaultUri: String) {
        val onDisk = vault.listAllMarkdown().associateBy { it.relativePath }
        val indexed = dao.allPathsWithLastModified().associateBy { it.path }

        // Removed on disk → drop from index.
        for (path in indexed.keys - onDisk.keys) {
            dao.deleteByPath(path)
        }
        // New or modified on disk → re-read and upsert.
        for ((path, info) in onDisk) {
            val prior = indexed[path]
            if (prior == null || info.lastModified > prior.lastModified) {
                val content = vault.read(path) ?: continue
                dao.upsert(
                    VaultDocEntity(
                        path = path,
                        content = content,
                        lastModified = info.lastModified,
                    ),
                )
            }
        }
        dao.setMeta(
            VaultMetaEntity(vaultUri = vaultUri, lastFullSyncAt = System.currentTimeMillis()),
        )
    }

    /**
     * Run a user/agent search query. The raw text is sanitized into a safe FTS4
     * MATCH expression: punctuation that FTS treats as operators is stripped,
     * then each surviving token gets a trailing `*` so partial words match (e.g.
     * "monarch" finds "monarchs"). Tokens are AND'd together (FTS4's default
     * conjunction).
     */
    suspend fun search(rawQuery: String, maxHits: Int = 50): List<VaultHit> {
        val fts = toFtsMatch(rawQuery)
        if (fts.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            dao.search(fts, maxHits).map { VaultHit(relativePath = it.path, snippet = it.snippet) }
        }
    }

    suspend fun upsertDoc(path: String, content: String) = withContext(Dispatchers.IO) {
        dao.upsert(
            VaultDocEntity(
                path = path,
                content = content,
                lastModified = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun renamePath(from: String, to: String) = withContext(Dispatchers.IO) {
        dao.renamePath(from, to)
    }

    suspend fun deleteDoc(path: String) = withContext(Dispatchers.IO) {
        dao.deleteByPath(path)
    }

    companion object {
        // FTS4 operator characters; if any of these leak into the MATCH expression
        // SQLite will either parse them as syntax or throw, so we strip them and
        // rebuild a safe prefix-AND query ourselves.
        private val FTS_OPERATORS = Regex("[\"*():^+~\\-]")
        private val WHITESPACE = Regex("\\s+")

        internal fun toFtsMatch(raw: String): String {
            val cleaned = FTS_OPERATORS.replace(raw, " ").trim()
            if (cleaned.isEmpty()) return ""
            return cleaned.split(WHITESPACE)
                .filter { it.length >= 2 }
                .joinToString(" ") { "$it*" }
        }
    }
}
