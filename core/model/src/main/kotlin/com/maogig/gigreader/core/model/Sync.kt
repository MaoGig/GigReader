package com.maogig.gigreader.core.model

/**
 * Metadata carried by every persisted entity so that incremental backup/sync can be added
 * later without a data migration (see docs/ARCHITECTURE.md, "Sincronização").
 *
 * - [id] is a random UUID generated on the device that created the entity; it never changes.
 * - [createdAt]/[modifiedAt] are epoch milliseconds (wall clock, informative only).
 * - [version] is a per-entity counter incremented on every local modification.
 * - [deletedAt] marks a tombstone: the row is kept (without user content) so the deletion can
 *   be propagated, and is purged once every provider has acknowledged it.
 */
interface Syncable {
    val id: String
    val createdAt: Long
    val modifiedAt: Long
    val version: Long
    val deletedAt: Long?

    val isDeleted: Boolean get() = deletedAt != null
}

/** Change classification a future sync engine assigns to one entity. */
enum class ChangeKind { UNCHANGED, LOCAL_NEW, REMOTE_NEW, LOCAL_MODIFIED, REMOTE_MODIFIED, PURGED, CONFLICT }

/**
 * Classifies one entity given its local version, remote version and the version recorded at the
 * last successful sync (`null` = absent / never synced).
 *
 * Versions are only compared against the last synced version: two devices that each edit an entity
 * once both reach `base + 1`, so equal versions on both sides after independent edits are still a
 * [ChangeKind.CONFLICT].
 */
fun classifyChange(local: Long?, remote: Long?, lastSynced: Long?): ChangeKind {
    if (lastSynced == null) {
        return when {
            local == null && remote == null -> ChangeKind.UNCHANGED
            remote == null -> ChangeKind.LOCAL_NEW
            local == null -> ChangeKind.REMOTE_NEW
            else -> ChangeKind.CONFLICT
        }
    }
    if (local == null || remote == null) return ChangeKind.PURGED
    val localChanged = local != lastSynced
    val remoteChanged = remote != lastSynced
    return when {
        localChanged && remoteChanged -> ChangeKind.CONFLICT
        localChanged -> ChangeKind.LOCAL_MODIFIED
        remoteChanged -> ChangeKind.REMOTE_MODIFIED
        else -> ChangeKind.UNCHANGED
    }
}
