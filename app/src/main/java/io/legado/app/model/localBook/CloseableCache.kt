package io.legado.app.model.localBook

internal class CloseableCache<T : AutoCloseable> {
    private var value: T? = null

    @Synchronized
    fun getOrCreate(matches: (T) -> Boolean, create: () -> T): T {
        val current = value
        if (current != null && matches(current)) {
            return current
        }
        value = null
        current?.close()
        return create().also { value = it }
    }

    @Synchronized
    fun clear() {
        val current = value
        value = null
        current?.close()
    }

    @Synchronized
    fun clearIf(matches: (T) -> Boolean) {
        val current = value
        if (current != null && matches(current)) {
            value = null
            current.close()
        }
    }
}
