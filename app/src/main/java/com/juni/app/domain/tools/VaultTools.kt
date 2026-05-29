package com.juni.app.domain.tools

import com.juni.app.data.vault.VaultIndex
import com.juni.app.data.vault.VaultRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Source of truth for all vault-touching tools the agent can call. Each tool
 * accepts a [VaultRepository] and an optional in-flight image staging map so
 * `save_attachment` can persist a photo the user just added to the composer.
 *
 * Tools are intentionally small and side-effect-focused: the AgentLoop's
 * approval gate decides *whether* a tool runs; this file decides *what* it does.
 */
object VaultTools {

    fun all(
        vault: VaultRepository,
        index: VaultIndex,
        attachmentStaging: AttachmentStaging,
        onClarifyingQuestion: (question: String) -> Unit = {},
        onRenameChat: (newTitle: String) -> Unit = {},
    ): List<Tool> = listOf(
        listFiles(vault),
        readNote(vault),
        createNote(vault, index),
        editNote(vault, index),
        moveNote(vault, index),
        searchNotes(index),
        saveAttachment(vault, attachmentStaging),
        askClarifyingQuestion(onClarifyingQuestion),
        renameChat(onRenameChat),
    )

    private fun listFiles(vault: VaultRepository): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "list_files",
            description = "List entries (files and folders) in a directory of the user's Obsidian vault. " +
                "Use this to discover where notes live before deciding where to create new ones. " +
                "Returns one entry per line: `DIR  path` or `FILE  path  <bytes>B`.",
            inputSchema = objectSchema(
                "path" to stringField(
                    "Vault-relative directory path. Empty string '' means the vault root.",
                ),
                required = listOf("path"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val path = input.string("path") ?: ""
            val entries = vault.list(path)
            val text = if (entries.isEmpty()) {
                "(empty)"
            } else {
                entries.joinToString("\n") { e ->
                    if (e.isDirectory) "DIR   ${e.relativePath}"
                    else "FILE  ${e.relativePath}  ${e.sizeBytes}B"
                }
            }
            return ToolResult(text)
        }
    }

    private fun readNote(vault: VaultRepository): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "read_note",
            description = "Read the full text content of a markdown file in the vault.",
            inputSchema = objectSchema(
                "path" to stringField("Vault-relative path, e.g. `notes/butterflies.md`."),
                required = listOf("path"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val path = input.string("path") ?: return ToolResult("Missing 'path'.", isError = true)
            val content = vault.read(path) ?: return ToolResult("File not found: $path", isError = true)
            return ToolResult(content)
        }
    }

    private fun createNote(vault: VaultRepository, index: VaultIndex): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "create_note",
            description = "Create a new markdown file in the vault, or overwrite an existing one. " +
                "Pick a path that fits the user's existing structure — if you're not sure, list_files or " +
                "ask a clarifying question first.",
            inputSchema = objectSchema(
                "path" to stringField("Vault-relative path including .md, e.g. `notes/butterflies/monarchs.md`."),
                "content" to stringField("Full markdown content of the note."),
                required = listOf("path", "content"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val path = input.string("path") ?: return ToolResult("Missing 'path'.", isError = true)
            val content = input.string("content") ?: return ToolResult("Missing 'content'.", isError = true)
            vault.write(path, content)
            index.upsertDoc(path, content)
            return ToolResult("Wrote $path (${content.length} chars).")
        }
    }

    private fun editNote(vault: VaultRepository, index: VaultIndex): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "edit_note",
            description = "Replace an exact substring in an existing note. The `find` text must match exactly " +
                "(including whitespace). Use this for surgical edits; for whole-note rewrites use create_note.",
            inputSchema = objectSchema(
                "path" to stringField("Vault-relative path of the file to edit."),
                "find" to stringField("Exact substring to replace. Must occur exactly once."),
                "replace" to stringField("New text to substitute in."),
                required = listOf("path", "find", "replace"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val path = input.string("path") ?: return ToolResult("Missing 'path'.", isError = true)
            val find = input.string("find") ?: return ToolResult("Missing 'find'.", isError = true)
            val replace = input.string("replace") ?: return ToolResult("Missing 'replace'.", isError = true)
            val original = vault.read(path) ?: return ToolResult("File not found: $path", isError = true)
            val occurrences = original.split(find).size - 1
            return when (occurrences) {
                0 -> ToolResult("`find` text not found in $path.", isError = true)
                1 -> {
                    val updated = original.replace(find, replace)
                    vault.write(path, updated)
                    index.upsertDoc(path, updated)
                    ToolResult("Edited $path.")
                }
                else -> ToolResult(
                    "`find` text occurs $occurrences times in $path; widen it to be unique.",
                    isError = true,
                )
            }
        }
    }

    private fun moveNote(vault: VaultRepository, index: VaultIndex): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "move_note",
            description = "Rename or move a note to a new path within the vault.",
            inputSchema = objectSchema(
                "from" to stringField("Current vault-relative path."),
                "to" to stringField("New vault-relative path."),
                required = listOf("from", "to"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val from = input.string("from") ?: return ToolResult("Missing 'from'.", isError = true)
            val to = input.string("to") ?: return ToolResult("Missing 'to'.", isError = true)
            val ok = vault.move(from, to)
            if (ok) index.renamePath(from, to)
            return if (ok) ToolResult("Moved $from → $to.")
            else ToolResult("Move failed (source missing or destination unwritable).", isError = true)
        }
    }

    private fun searchNotes(index: VaultIndex): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "search_notes",
            description = "Full-text search across all .md files in the vault. Space-separated words are " +
                "treated as AND with prefix matching (e.g. `monarch but` matches notes containing words " +
                "starting with `monarch` AND words starting with `but`). Searches both note bodies and " +
                "path segments. Returns up to 50 hits; each line is `path :: snippet` with `[ ]` around " +
                "matched terms.",
            inputSchema = objectSchema(
                "query" to stringField("Words to search for. Don't use FTS operators — just plain words."),
                required = listOf("query"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val query = input.string("query") ?: return ToolResult("Missing 'query'.", isError = true)
            val hits = index.search(query)
            val text = if (hits.isEmpty()) "(no hits)"
            else hits.joinToString("\n") { h -> "${h.relativePath} :: ${h.snippet}" }
            return ToolResult(text)
        }
    }

    private fun saveAttachment(vault: VaultRepository, staging: AttachmentStaging): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "save_attachment",
            description = "Persist a pending image (one the user just attached in the composer) into the " +
                "vault's `attachments/` folder. Returns the embed string `![[attachments/<filename>]]` to " +
                "drop into a note.",
            inputSchema = objectSchema(
                "filename" to stringField("Suggested filename including extension, e.g. `whiteboard-2026-05-07.jpg`."),
                required = listOf("filename"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val filename = input.string("filename") ?: return ToolResult("Missing 'filename'.", isError = true)
            val bytes = staging.takeNext() ?: return ToolResult(
                "No pending attachment in the composer to save.",
                isError = true,
            )
            val savedPath = vault.writeAttachment(filename, bytes)
            return ToolResult("Saved to $savedPath. Embed with `![[$savedPath]]`.")
        }
    }

    private fun askClarifyingQuestion(onClarifyingQuestion: (String) -> Unit): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "ask_clarifying_question",
            description = "Ask the user a single clarifying question instead of guessing. The agent's turn " +
                "will end after this so the user can respond. Use when location, scope, or content is ambiguous.",
            inputSchema = objectSchema(
                "question" to stringField("The single question to ask the user."),
                required = listOf("question"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val question = input.string("question") ?: return ToolResult("Missing 'question'.", isError = true)
            onClarifyingQuestion(question)
            return ToolResult("Asked: $question")
        }
    }

    private fun renameChat(onRenameChat: (String) -> Unit): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "rename_chat",
            description = "Rename the current chat to a short descriptive title (3 to 6 words) summarising " +
                "what it is about. Call this once after your first substantive response so the conversation " +
                "list shows something meaningful instead of the default themed name.",
            inputSchema = objectSchema(
                "title" to stringField("Short descriptive title for this chat."),
                required = listOf("title"),
            ),
        )

        override suspend fun execute(input: JsonObject): ToolResult {
            val title = input.string("title")?.trim()
                ?: return ToolResult("Missing 'title'.", isError = true)
            if (title.isEmpty()) return ToolResult("Title is empty.", isError = true)
            onRenameChat(title)
            return ToolResult("Renamed chat to \"$title\".")
        }
    }

    // ---------- schema helpers ----------

    private fun objectSchema(
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
        )
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(it) } })
        }
    }

    private fun stringField(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull
}

/**
 * Bag of pending image bytes the user attached in the composer. The Chat
 * ViewModel hands one to the AgentLoop per turn; `save_attachment` pulls bytes
 * out as the agent calls it.
 */
class AttachmentStaging {
    private val pending = ArrayDeque<ByteArray>()

    fun add(bytes: ByteArray) { pending.addLast(bytes) }
    fun takeNext(): ByteArray? = pending.removeFirstOrNull()
    fun isEmpty(): Boolean = pending.isEmpty()
    fun clear() { pending.clear() }
}
