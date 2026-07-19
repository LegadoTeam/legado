package io.legado.app.ui.association

import io.legado.app.help.config.ReadBookConfig

private const val MIN_READ_CONFIG_COUNT = 5

internal fun applyImportedReadConfig(
    configs: MutableList<ReadBookConfig.Config>,
    imported: ReadBookConfig.Config,
    defaultConfigs: () -> List<ReadBookConfig.Config>,
    save: () -> Unit,
    transactionLock: Any = configs,
): String {
    return synchronized(transactionLock) {
        val previousConfigs = configs.toList()
        try {
            if (configs.size < MIN_READ_CONFIG_COUNT) {
                configs.clear()
                configs.addAll(defaultConfigs())
            }
            val existingIndex = configs.indexOfFirst { it.name == imported.name }
            if (existingIndex >= 0) {
                configs[existingIndex] = imported
            } else {
                configs.add(imported)
            }
            save()
            imported.name
        } catch (error: Throwable) {
            configs.clear()
            configs.addAll(previousConfigs)
            throw error
        }
    }
}
