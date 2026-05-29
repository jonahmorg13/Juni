package com.juni.app.data.vault

data class VaultEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val sizeBytes: Long,
)

data class VaultHit(
    val relativePath: String,
    val snippet: String,
)

/** (path, lastModified) for every markdown file under the vault; used by the FTS sync diff. */
data class VaultFileInfo(
    val relativePath: String,
    val lastModified: Long,
)
