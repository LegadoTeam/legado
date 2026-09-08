package io.legado.app.help.storage

import kotlinx.coroutines.sync.Mutex

// Backup and restore share staging files and must not snapshot partially restored data.
internal val backupRestoreMutex = Mutex()
