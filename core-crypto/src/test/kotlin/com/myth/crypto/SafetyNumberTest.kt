package com.myth.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {

    @Test
    fun testSafetyNumberIsSymmetricAndDeterministic() {
        val aliceEd = Ed25519Key.generate()
        val bobEd = Ed25519Key.generate()

        val aliceNumber = SafetyNumber.computeSafetyNumber(aliceEd.publicKeyBytes, bobEd.publicKeyBytes)
        val bobNumber = SafetyNumber.computeSafetyNumber(bobEd.publicKeyBytes, aliceEd.publicKeyBytes)

        assertEquals("Both parties must compute identical safety numbers regardless of order", aliceNumber, bobNumber)

        // Verify format: 12 groups of 5 digits separated by spaces
        val groups = aliceNumber.split(" ")
        assertEquals(12, groups.size)
        for (group in groups) {
            assertEquals(5, group.length)
            assertTrue(group.all { it.isDigit() })
        }

        // Verify different key produces different safety number (detects key replacement)
        val attackerEd = Ed25519Key.generate()
        val mitmNumber = SafetyNumber.computeSafetyNumber(aliceEd.publicKeyBytes, attackerEd.publicKeyBytes)
        assertNotEquals(aliceNumber, mitmNumber)
    }
}
