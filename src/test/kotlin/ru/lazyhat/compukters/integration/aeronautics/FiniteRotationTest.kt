/*
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.lazyhat.compukters.integration.aeronautics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiniteRotationTest {
    @Test fun `full turns require actual travel and settling`() {
        val motion = FiniteRotation(0.0)
        motion.rotateBy(720.0, 90.0)
        repeat(40) { motion.observe(0.0, 0.0, it.toLong()) }
        assertFalse(motion.settled)
        assertTrue(motion.driveStep(0.0, 10.0) > 0.0)
        repeat(72) { i -> motion.observe(FiniteRotation.wrap((i + 1) * 10.0), 200.0, (40 + i).toLong()) }
        assertEquals(720.0, motion.angle, 1e-8)
        assertFalse(motion.settled)
        repeat(5) { motion.observe(0.0, 0.0, (112 + it).toLong()) }
        assertTrue(motion.settled)
    }

    @Test fun `reverse wrap and relative retarget start from actual position`() {
        val motion = FiniteRotation(-170.0)
        motion.observe(170.0, -400.0, 1)
        assertEquals(-190.0, motion.angle, 1e-8)
        motion.rotateBy(-720.0, 30.0)
        assertEquals(-910.0, motion.target, 1e-8)
        motion.rotateBy(90.0, 30.0)
        assertEquals(-100.0, motion.target, 1e-8)
    }

    @Test fun `kinetic input and requested speed bound waypoint and prevent running ahead`() {
        val motion = FiniteRotation(0.0)
        motion.rotateBy(720.0, 90.0)
        assertEquals(0.0, motion.driveStep(0.0, 0.0))
        assertEquals(1.0, motion.driveStep(0.0, 1.0))
        assertEquals(4.5, motion.driveStep(0.0, 100.0))
        assertEquals(0.0, motion.driveStep(15.0, 100.0))
        motion.stop()
        assertEquals(0.0, motion.target)
        assertTrue(motion.driveStep(15.0, 100.0) < 0.0)
    }

    @Test fun `repeated polls cannot manufacture completion ticks`() {
        val motion = FiniteRotation(0.0)
        motion.rotateBy(0.0, 30.0)
        repeat(20) { motion.observe(0.0, 0.0, 1) }
        assertFalse(motion.settled)
        (2L..5L).forEach { motion.observe(0.0, 0.0, it) }
        assertTrue(motion.settled)
    }

    @Test fun `invalid commands leave the existing motion intact`() {
        val motion = FiniteRotation(0.0)
        motion.rotateBy(90.0, 30.0)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 360001.0)) {
            assertFailsWith<IllegalArgumentException> { motion.moveTo(value, 30.0) }
        }
        assertFailsWith<IllegalArgumentException> { motion.rotateBy(1.0, 0.0) }
        assertEquals(90.0, motion.target)
    }
}
