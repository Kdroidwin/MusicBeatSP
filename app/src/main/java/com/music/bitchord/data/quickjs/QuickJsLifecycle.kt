package com.music.bitchord.data.quickjs

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Serializes native QuickJS context creation and destruction across the app.
 * The Android binding shares JNI global state between contexts, so independent
 * executors must not initialize or dispose contexts at the same time.
 */
internal object QuickJsLifecycle {
    private val lock = Any()

    fun create(dispatcher: CoroutineDispatcher): QuickJs = synchronized(lock) {
        QuickJs.create(dispatcher)
    }

    fun close(engine: QuickJs) {
        synchronized(lock) { engine.close() }
    }
}
