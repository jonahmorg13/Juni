package com.juni.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC")
    suspend fun forConversation(id: String): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)
}

/** Lightweight DTO for the sync diff — avoids loading every note's content into memory. */
data class IndexedPath(val path: String, val lastModified: Long)

/** Result row from a full-text search. `snippet` is built by SQLite's snippet() function. */
data class SearchHit(val path: String, val snippet: String)

@Dao
interface VaultIndexDao {

    /**
     * Run a sanitized FTS MATCH query and return up to [limit] hits.
     * The snippet is built across whichever column actually matched (colno = -1)
     * with a short window (12 tokens) — enough for context, small enough that
     * the agent's tool-result message stays compact.
     */
    @Query(
        "SELECT path, snippet(vault_docs, '[', ']', '...', -1, 12) AS snippet " +
            "FROM vault_docs WHERE vault_docs MATCH :query LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int): List<SearchHit>

    @Query("SELECT path, lastModified FROM vault_docs")
    suspend fun allPathsWithLastModified(): List<IndexedPath>

    @Insert
    suspend fun insert(doc: VaultDocEntity)

    @Query("DELETE FROM vault_docs WHERE path = :path")
    suspend fun deleteByPath(path: String)

    /**
     * Path-only rename for move_note — content stays the same, so we just
     * update the path column instead of re-tokenizing the body.
     */
    @Query("UPDATE vault_docs SET path = :to WHERE path = :from")
    suspend fun renamePath(from: String, to: String)

    @Query("DELETE FROM vault_docs")
    suspend fun clearDocs()

    @Transaction
    suspend fun upsert(doc: VaultDocEntity) {
        deleteByPath(doc.path)
        insert(doc)
    }

    @Query("SELECT * FROM vault_meta WHERE id = 0")
    suspend fun getMeta(): VaultMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(meta: VaultMetaEntity)

    @Query("DELETE FROM vault_meta")
    suspend fun clearMeta()
}
