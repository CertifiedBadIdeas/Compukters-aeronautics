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
package aeronautics.servo

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** Angles are unwrapped degrees about the outward bearing axis, relative to its physical base. */
public value class ServoState(
    public val gameTick: Long,
    public val angle: Double,
    public val targetAngle: Double,
    public val angularVelocity: Double,
    public val speed: Double,
    public val controlled: Boolean,
    public val settled: Boolean,
)

public value class Servo internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<Servo>("aeronautics:servo") {
        override fun wrap(handle: Int): Servo = Servo(handle)
    }

    public fun state(): ServoState = ServoBindings.state(handle)

    /** Replace the current motion with an absolute unwrapped target. Native kinetic input limits available speed. */
    public fun moveTo(degrees: Double) { moveTo(degrees, 90.0) }

    public fun moveTo(degrees: Double, speed: Double) {
        validate(degrees, speed)
        ServoBindings.moveTo(handle, degrees, speed)
    }

    /** Replace the current motion with a signed displacement from the actual position at command acceptance. */
    public fun rotateBy(degrees: Double) { rotateBy(degrees, 90.0) }

    public fun rotateBy(degrees: Double, speed: Double) {
        validate(degrees, speed)
        ServoBindings.rotateBy(handle, degrees, speed)
    }

    /** Cancel travel and hold the current actual angle. Braking takes physical time. */
    public fun stop() { ServoBindings.stop(handle) }

    /** True only after actual position and speed settle; false on timeout. The motion continues on timeout. */
    public fun awaitStopped(timeoutTicks: Int = 1200): Boolean {
        if (timeoutTicks <= 0 || timeoutTicks > 72000) throw IllegalArgumentException("Timeout must be between 1 and 72000 ticks")
        return ServoBindings.awaitStopped(handle, timeoutTicks)
    }

    /** Cancel digital travel, return native kinetic/redstone control, and invalidate this handle. */
    public fun close() { ServoBindings.close(handle) }
}

private fun validate(degrees: Double, speed: Double) {
    if (!(degrees >= -360000.0 && degrees <= 360000.0)) throw IllegalArgumentException("Angle must be finite and within +/-360000 degrees")
    if (!(speed >= 0.1 && speed <= 360.0)) throw IllegalArgumentException("Speed must be between 0.1 and 360 degrees/second")
}

private object ServoBindings {
    external fun state(handle: Int): ServoState
    external fun moveTo(handle: Int, degrees: Double, speed: Double)
    external fun rotateBy(handle: Int, degrees: Double, speed: Double)
    external fun stop(handle: Int)
    external fun awaitStopped(handle: Int, timeoutTicks: Int): Boolean
    external fun close(handle: Int)
}
