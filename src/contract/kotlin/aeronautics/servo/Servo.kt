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

import ru.lazyhat.compukters.api.contracts.DoubleRange
import ru.lazyhat.compukters.api.contracts.GuestCall
import ru.lazyhat.compukters.api.contracts.GuestCommand
import ru.lazyhat.compukters.api.contracts.GuestDevice
import ru.lazyhat.compukters.api.contracts.GuestRead
import ru.lazyhat.compukters.api.contracts.GuestRecord
import ru.lazyhat.compukters.api.contracts.GuestWait
import ru.lazyhat.compukters.api.contracts.IntRange

/** Angles are unwrapped degrees about the outward bearing axis, relative to its physical base. */
@GuestRecord
value class ServoState(
    val gameTick: Long,
    val angle: Double,
    val targetAngle: Double,
    val angularVelocity: Double,
    val speed: Double,
    val controlled: Boolean,
    val settled: Boolean,
)

@GuestDevice("aeronautics:servo", bindings = "ServoBindings")
interface Servo {
    @GuestRead(binding = "state")
    fun state(): ServoState

    @GuestCommand(binding = "moveTo")
    fun moveTo(
        @DoubleRange(min = -360000.0, max = 360000.0) degrees: Double,
        @DoubleRange(min = 0.1, max = 360.0) speed: Double,
    ): Unit

    @GuestCommand(binding = "rotateBy")
    fun rotateBy(
        @DoubleRange(min = -360000.0, max = 360000.0) degrees: Double,
        @DoubleRange(min = 0.1, max = 360.0) speed: Double,
    ): Unit

    /** Cancel travel and hold the current actual angle. Braking takes physical time. */
    @GuestCommand(binding = "stop")
    fun stop(): Unit

    /** True only after actual position and speed settle; false on timeout. The motion continues on timeout. */
    @GuestWait(binding = "awaitStopped")
    fun awaitStopped(
        @IntRange(min = 1, max = 72000) timeoutTicks: Int,
    ): Boolean

    /** Cancel digital travel, return native kinetic/redstone control, and invalidate this handle. */
    @GuestCall(binding = "close")
    fun close(): Unit
}
