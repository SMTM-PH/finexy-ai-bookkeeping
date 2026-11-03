package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test

class TransientChangeRegistryTest {
    @Test fun namespacesAreIsolatedAndChangeBackStillAdvancesRevision() {
        val registry = TransientChangeRegistry()
        var first = 0; var second = 0
        registry.observe("first") { first++ }
        registry.observe("second") { second++ }
        registry.changed("first"); registry.changed("first")
        assertEquals(2L, registry.revision("first")); assertEquals(0L, registry.revision("second"))
        assertEquals(2, first); assertEquals(0, second)
    }
    @Test fun closeIsIdempotentAndListenerFailuresDoNotPreventOtherObservers() {
        val registry = TransientChangeRegistry()
        registry.observe("test") { error("synthetic observer failure") }
        var calls = 0
        val observation = registry.observe("test") { calls++ }
        registry.changed("test"); assertEquals(1, calls)
        observation.close(); observation.close(); registry.changed("test")
        assertEquals(1, calls); assertEquals(2L, registry.revision("test"))
    }
    @Test fun observersCanUnsubscribeAndSubscribeDuringNotification() {
        val registry = TransientChangeRegistry()
        var second = 0
        lateinit var first: AutoCloseable
        first = registry.observe("test") {
            first.close(); registry.observe("test") { second++ }
        }
        registry.changed("test"); assertEquals(0, second)
        registry.changed("test"); assertEquals(1, second)
    }
}
