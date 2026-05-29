package com.juni.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val providerId: String,
    val modelId: String,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "USER" / "ASSISTANT"
    val contentJson: String, // JSON-encoded List<MessageContent>
    val createdAt: Long,
)

/**
 * Full-text-search row for one markdown note in the vault. Backed by an FTS4
 * virtual table — `path` and `content` are tokenized (so MATCH queries hit
 * both note bodies and path segments like "notes/butterflies/..."), while
 * `lastModified` is stored unindexed and used purely by the sync diff.
 *
 * Tokenizer is unicode61 so non-ASCII notes tokenize correctly; the default
 * `simple` tokenizer only splits on ASCII word boundaries.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["lastModified"])
@Entity(tableName = "vault_docs")
data class VaultDocEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long? = null,
    val path: String,
    val content: String,
    val lastModified: Long,
)

/**
 * Singleton row that records which vault the FTS index currently holds. If the
 * user switches vaults (different tree URI), we detect the mismatch and wipe
 * the index instead of merging two vaults' notes together.
 */
@Entity(tableName = "vault_meta")
data class VaultMetaEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val vaultUri: String,
    val lastFullSyncAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
