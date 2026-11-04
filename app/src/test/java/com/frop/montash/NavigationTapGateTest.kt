package com.frop.montash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTapGateTest {
    @Test fun ignoresSecondTapUntilTransitionWindowEnds() {
        var time = 100L
        val gate = NavigationTapGate(now = { time })

        assertTrue(gate.tryEnter())
        time = 500L
        assertFalse(gate.tryEnter())
        time = 1_100L
        assertTrue(gate.tryEnter())
    }
}
