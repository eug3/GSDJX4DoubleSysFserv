package com.guaishoudejia.x4doublesysfserv

import java.util.concurrent.atomic.AtomicBoolean

object FrameSyncState {
    private val dirty = AtomicBoolean(true)

    fun markDirty() {
        dirty.set(true)
    }

    fun consumeDirty(): Boolean {
        return dirty.getAndSet(false)
    }
}
