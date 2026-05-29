package com.juni.app.data.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Path-relative wrapper around a Storage Access Framework tree URI.
 * The rest of the app speaks in vault-relative paths like "notes/butterflies.md";
 * this class is the only place that touches DocumentFile / Uri.
 *
 * Construct freshly when needed — it is stateless apart from the root reference.
 */
class VaultRepository(
    private val context: Context,
    treeUri: Uri,
) {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("Cannot resolve vault tree URI: $treeUri")

    suspend fun list(relativePath: String = ""): List<VaultEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(relativePath) ?: return@withContext emptyList()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            .mapNotNull { df ->
                val name = df.name ?: return@mapNotNull null
                // Skip dotfiles/dotfolders (.obsidian/, .trash/, .git/, …). The agent doesn't
                // need to see them and listing them is mostly noise.
                if (name.startsWith(".")) return@mapNotNull null
                VaultEntry(
                    relativePath = joinPath(relativePath, name),
                    name = name,
                    isDirectory = df.isDirectory,
                    lastModified = df.lastModified(),
                    sizeBytes = df.length(),
                )
            }
            .sortedWith(compareByDescending<VaultEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun read(relativePath: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(relativePath) ?: return@withContext null
        if (!file.isFile) return@withContext null
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            input.bufferedReader().readText()
        }
    }

    suspend fun write(relativePath: String, content: String) = withContext(Dispatchers.IO) {
        val (parentPath, name) = splitParent(relativePath)
        val parent = ensureDirectory(parentPath)
            ?: throw IllegalStateException("Could not create parent for $relativePath")
        val existing = parent.findFile(name)
        val target = existing ?: parent.createFile(mimeTypeFor(name), name)
            ?: throw IllegalStateException("Could not create file $relativePath")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Could not open output stream for $relativePath")
    }

    suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolve(relativePath) ?: return@withContext false
        file.delete()
    }

    /**
     * Move/rename by copy-then-delete. Cross-cutting renames are simpler and more
     * portable across DocumentsProvider implementations than DocumentsContract.moveDocument.
     */
    suspend fun move(fromPath: String, toPath: String): Boolean = withContext(Dispatchers.IO) {
        val src = resolve(fromPath) ?: return@withContext false
        if (!src.isFile) return@withContext false
        val content = read(fromPath) ?: return@withContext false
        write(toPath, content)
        src.delete()
    }

    /**
     * Save bytes (e.g. a JPEG) to the `attachments/` subfolder.
     * Returns the relative path that was written so callers can build `![[…]]` embeds.
     */
    suspend fun writeAttachment(filename: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val parent = ensureDirectory("attachments")
            ?: throw IllegalStateException("Could not create attachments/ directory")
        val safeName = filename.replace('/', '_').replace('\\', '_')
        val target = parent.findFile(safeName) ?: parent.createFile(mimeTypeFor(safeName), safeName)
            ?: throw IllegalStateException("Could not create attachment $safeName")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(bytes)
        } ?: throw IllegalStateException("Could not open output stream for $safeName")
        joinPath("attachments", safeName)
    }

    /**
     * Naive substring search across `*.md` files. v1 — fine for vaults of a few thousand
     * notes; if it gets slow we'll add an index.
     */
    suspend fun search(query: String, maxHits: Int = 50): List<VaultHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val needle = query.lowercase()
        val hits = mutableListOf<VaultHit>()
        walkMarkdown(root, "") { df, path ->
            if (hits.size >= maxHits) return@walkMarkdown false
            val text = context.contentResolver.openInputStream(df.uri)?.use { it.bufferedReader().readText() }
                ?: return@walkMarkdown true
            val idx = text.lowercase().indexOf(needle)
            if (idx >= 0) {
                val start = (idx - 30).coerceAtLeast(0)
                val end = (idx + needle.length + 30).coerceAtMost(text.length)
                hits += VaultHit(
                    relativePath = path,
                    snippet = text.substring(start, end).replace('\n', ' ').trim(),
                )
            }
            true
        }
        hits
    }

    fun exists(relativePath: String): Boolean = resolve(relativePath) != null

    /**
     * Walks the whole vault once and returns (path, lastModified) for every `.md`
     * file (dotfolders skipped). Cheap relative to reading content because each
     * file only triggers a `name`/`isFile`/`lastModified` Binder call, not an
     * `openInputStream`. The FTS sync diff uses this to decide which files
     * actually need to be re-read.
     */
    suspend fun listAllMarkdown(): List<VaultFileInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<VaultFileInfo>()
        walkMarkdown(root, "") { df, path ->
            out += VaultFileInfo(relativePath = path, lastModified = df.lastModified())
            true
        }
        out
    }

    // ---------- internals ----------

    private fun resolve(relativePath: String): DocumentFile? {
        if (relativePath.isEmpty() || relativePath == "/") return root
        var cursor: DocumentFile? = root
        for (segment in relativePath.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            cursor = cursor?.findFile(segment) ?: return null
        }
        return cursor
    }

    private fun ensureDirectory(relativePath: String): DocumentFile? {
        if (relativePath.isEmpty()) return root
        var cursor: DocumentFile = root
        for (segment in relativePath.trim('/').split('/')) {
            if (segment.isEmpty()) continue
            val existing = cursor.findFile(segment)
            cursor = when {
                existing == null -> cursor.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return cursor
    }

    private fun walkMarkdown(
        dir: DocumentFile,
        path: String,
        visit: (DocumentFile, String) -> Boolean,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            // Skip hidden directories like .trash/ and .obsidian/ — search shouldn't
            // surface deleted notes or plugin config.
            if (name.startsWith(".")) continue
            val childPath = joinPath(path, name)
            when {
                child.isDirectory -> walkMarkdown(child, childPath, visit)
                child.isFile && name.endsWith(".md", ignoreCase = true) -> {
                    if (!visit(child, childPath)) return
                }
            }
        }
    }

    private fun joinPath(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"

    private fun splitParent(path: String): Pair<String, String> {
        val trimmed = path.trim('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx < 0) "" to trimmed else trimmed.substring(0, idx) to trimmed.substring(idx + 1)
    }

    private fun mimeTypeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
