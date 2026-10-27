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

import kotlin.math.abs

/** Server-confined trajectory state. Native physics remains the authority for motion. */
class FiniteRotation(
    initialAngle: Double,
) {
    var angle: Double = initialAngle
        private set
    var target: Double = initialAngle
        private set
    var velocity: Double = 0.0
        private set
    var speed: Double = 90.0
        private set
    var active: Boolean = false
        private set
    var settled: Boolean = false
        private set
    var tick: Long = Long.MIN_VALUE
        private set
    private var previousWrapped = wrap(initialAngle)
    private var stableTicks = 0

    init {
        require(initialAngle.isFinite())
    }

    fun observe(
        wrappedAngle: Double,
        degreesPerSecond: Double,
        gameTick: Long,
    ) {
        require(wrappedAngle.isFinite() && degreesPerSecond.isFinite())
        if (gameTick == tick) return
        angle += wrap(wrappedAngle - previousWrapped)
        previousWrapped = wrap(wrappedAngle)
        velocity = degreesPerSecond
        stableTicks = if (active && abs(target - angle) <= 0.5 && abs(velocity) <= 1.0) stableTicks + 1 else 0
        settled = stableTicks >= 5
        tick = gameTick
    }

    fun moveTo(
        degrees: Double,
        degreesPerSecond: Double,
    ) {
        require(degrees.isFinite() && abs(degrees) <= 360_000.0) { "Target must be finite and within +/-360000 degrees" }
        require(degreesPerSecond.isFinite() && degreesPerSecond in 0.1..360.0) { "Speed must be between 0.1 and 360 degrees/second" }
        target = degrees
        speed = degreesPerSecond
        active = true
        stableTicks = 0
        settled = false
    }

    fun rotateBy(
        degrees: Double,
        degreesPerSecond: Double,
    ) {
        require(degrees.isFinite()) { "Rotation must be finite" }
        moveTo(angle + degrees, degreesPerSecond)
    }

    /** Return a small native target increment, keeping the waypoint within 15 degrees of actual motion. */
    fun driveStep(
        nativeTarget: Double,
        availableDegreesPerTick: Double,
    ): Double {
        require(nativeTarget.isFinite() && availableDegreesPerTick.isFinite() && availableDegreesPerTick >= 0)
        if (!active) return 0.0
        val waypoint = angle + (target - angle).coerceIn(-15.0, 15.0)
        val maximum = minOf(speed / 20.0, availableDegreesPerTick)
        return wrap(waypoint - nativeTarget).coerceIn(-maximum, maximum)
    }

    fun stop() {
        target = angle
        active = true
        stableTicks = 0
        settled = false
    }

    companion object {
        @JvmStatic
        fun wrap(degrees: Double): Double = ((degrees % 360.0 + 540.0) % 360.0) - 180.0
    }
}
