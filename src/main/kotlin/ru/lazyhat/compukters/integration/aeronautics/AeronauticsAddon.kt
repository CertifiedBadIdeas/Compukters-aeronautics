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

import aeronautics.AeronauticsAddonContract
import aeronautics.AeronauticsCapabilityHandler
import aeronautics.DockingState
import aeronautics.ServoState
import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity
import net.neoforged.fml.common.Mod
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonFailed
import ru.lazyhat.compukters.api.addon.addonPending
import ru.lazyhat.compukters.api.addon.addonPollCompleted
import ru.lazyhat.compukters.api.addon.addonPollFailed
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonHostFactory
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersAddonRegistry
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersComputerContext
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersLeasedPeripheralEndpoint
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralAccessException
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralContract
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralDevice
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralEndpoint
import ru.lazyhat.compukters.api.addon.minecraft.CompuktersPeripheralProvider
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind

@Mod("compukters_aeronautics")
class AeronauticsAddon {
    init {
        CompuktersAddonRegistry.register(
            AeronauticsAddonContract.guestApi(AeronauticsAddon::class.java),
            CompuktersAddonHostFactory { AeronauticsAddonContract.host(ServoHost(it)) },
            CompuktersPeripheralProvider { contact ->
                if (!contact.level.hasChunkAt(contact.position)) {
                    null
                } else {
                    when (val entity = contact.level.getBlockEntity(contact.position)) {
                        is SwivelBearingBlockEntity -> {
                            entity
                                .takeUnless { it.isRemoved }
                                ?.let { CompuktersPeripheralDevice(it.blockPos, "servo") }
                        }

                        is DockingConnectorBlockEntity -> {
                            entity
                                .takeUnless { it.isRemoved }
                                ?.let { CompuktersPeripheralDevice(it.blockPos, "docking_connector") }
                        }

                        else -> {
                            null
                        }
                    }
                }
            },
            listOf(ServoHost.contract, DockingEndpoint.contract),
        )
    }
}

internal class ServoEndpoint(
    val entity: SwivelBearingBlockEntity,
    private val reachable: () -> Boolean,
) : CompuktersLeasedPeripheralEndpoint {
    override val identity: Any get() = entity
    private val child = entity.subLevelID
    private var stale = false
    lateinit var session: BearingSession
        private set

    override fun valid(): Boolean {
        if (!stale &&
            (
                entity.isRemoved || !entity.isAssembled || entity.subLevelID != child || !reachable() ||
                    (::session.isInitialized && (entity as BearingAccess).getServoSession() !== session)
            )
        ) {
            stale = true
        }
        return !stale
    }

    override fun acquire() {
        check(valid()) { "Servo must be assembled and reachable" }
        val sample = checkNotNull(BearingPhysics.sample(entity)) { "Servo physics is not initialized" }
        val motion =
            FiniteRotation(sample.angle).apply {
                observe(sample.angle, sample.velocity, checkNotNull(entity.level).gameTime)
            }
        session = BearingSession(checkNotNull(child), motion, ::valid)
        (entity as BearingAccess).setServoSession(session)
    }

    override fun release() {
        if (::session.isInitialized) BearingPhysics.release(entity, session)
    }

    fun active(): FiniteRotation {
        check(valid() && (entity as BearingAccess).getServoSession() === session) { "Servo is unavailable" }
        return session.motion
    }

    fun activate() {
        val motion = active()
        check((entity as BearingAccess).getServoSequenceLimit() < 0.0) { "A native sequenced rotation is active" }
        if (!motion.active) (entity as BearingAccess).setServoNativeAngle(FiniteRotation.wrap(motion.angle))
    }
}

internal class ServoHost(
    private val computer: CompuktersComputerContext,
) : AeronauticsCapabilityHandler {
    private val endpoints = mutableSetOf<ServoEndpoint>()

    override fun dockState(handle: Int): AddonCallResult<DockingState> {
        check(computer.level.server.isSameThread)
        return try {
            addonCompleted(computer.peripheral(DockingEndpoint.contract, handle).state())
        } catch (failure: CompuktersPeripheralAccessException) {
            addonFailed(failure.kind, failure.message)
        }
    }

    override fun state(handle: Int): AddonCallResult<ServoState> =
        withServo(handle) { endpoint ->
            val motion = endpoint.active()
            addonCompleted(
                ServoState(
                    motion.tick,
                    motion.angle,
                    motion.target,
                    motion.velocity,
                    motion.speed,
                    motion.active,
                    motion.settled,
                ),
            )
        }

    override fun moveTo(
        handle: Int,
        degrees: Double,
        speed: Double,
    ): AddonCallResult<Unit> =
        withServo(handle) { endpoint ->
            endpoint.activate()
            endpoint.active().moveTo(degrees, speed)
            addonCompleted(Unit)
        }

    override fun rotateBy(
        handle: Int,
        degrees: Double,
        speed: Double,
    ): AddonCallResult<Unit> =
        withServo(handle) { endpoint ->
            endpoint.activate()
            endpoint.active().rotateBy(degrees, speed)
            addonCompleted(Unit)
        }

    override fun stop(handle: Int): AddonCallResult<Unit> =
        withServo(handle) { endpoint ->
            endpoint.activate()
            endpoint.active().stop()
            (endpoint.entity as BearingAccess).setServoNativeAngle(FiniteRotation.wrap(endpoint.active().angle))
            addonCompleted(Unit)
        }

    override fun awaitStopped(
        handle: Int,
        timeoutTicks: Int,
    ): AddonCallResult<Boolean> =
        withServo(handle) { endpoint ->
            require(timeoutTicks in 1..72000) { "Invalid servo timeout" }
            check(endpoint.active().active) { "Servo has no commanded motion" }
            val deadline = computer.level.gameTime + timeoutTicks
            addonPending {
                try {
                    val current = computer.peripheral(contract, handle)
                    when {
                        current !== endpoint -> addonPollFailed(HostFailureKind.UNAVAILABLE, "Servo was replaced")
                        endpoint.active().settled -> addonPollCompleted(true)
                        computer.level.gameTime >= deadline -> addonPollCompleted(false)
                        else -> null
                    }
                } catch (failure: CompuktersPeripheralAccessException) {
                    addonPollFailed(failure.kind, failure.message)
                } catch (failure: IllegalStateException) {
                    addonPollFailed(HostFailureKind.UNAVAILABLE, failure.message ?: "Servo is unavailable")
                }
            }
        }

    override fun close(handle: Int): AddonCallResult<Unit> =
        withServo(handle) {
            computer.closePeripheral(contract, handle)
            endpoints.remove(it)
            addonCompleted(Unit)
        }

    override fun reset() {
        endpoints.forEach { it.release() }
        endpoints.clear()
    }

    private fun <T> withServo(
        handle: Int,
        action: (ServoEndpoint) -> AddonCallResult<T>,
    ): AddonCallResult<T> {
        check(computer.level.server.isSameThread)
        return try {
            val endpoint = computer.peripheral(contract, handle)
            endpoints.add(endpoint)
            action(endpoint)
        } catch (failure: CompuktersPeripheralAccessException) {
            addonFailed(failure.kind, failure.message)
        } catch (failure: IllegalArgumentException) {
            addonFailed(HostFailureKind.OTHER, failure.message ?: "Invalid servo command")
        } catch (failure: IllegalStateException) {
            addonFailed(HostFailureKind.UNAVAILABLE, failure.message ?: "Servo is unavailable")
        }
    }

    companion object {
        val contract =
            CompuktersPeripheralContract("aeronautics:servo", "servo", "aeronautics.servo.Servo") { location ->
                if (!location.level.hasChunkAt(location.position)) {
                    null
                } else {
                    (location.level.getBlockEntity(location.position) as? SwivelBearingBlockEntity)
                        ?.takeIf { it.isAssembled && !it.isRemoved }
                        ?.let { entity ->
                            ServoEndpoint(entity) {
                                location.level.hasChunkAt(
                                    location.position,
                                ) && location.level.getBlockEntity(location.position) === entity &&
                                    location.isReachable()
                            }
                        }
                }
            }
    }
}

/** Observation only: acquiring a handle never powers or locks a connector. */
internal class DockingEndpoint(
    private val entity: DockingConnectorBlockEntity,
    private val reachable: () -> Boolean,
) : CompuktersPeripheralEndpoint {
    override val identity: Any get() = entity

    override fun valid(): Boolean {
        val level = entity.level ?: return false
        check(level.server?.isSameThread == true)
        return !entity.isRemoved && level.hasChunkAt(entity.blockPos) &&
            level.getBlockEntity(entity.blockPos) === entity && reachable()
    }

    fun state(): DockingState =
        DockingState(
            checkNotNull(entity.level).gameTime,
            entity.hasOtherConnector(),
            entity.isLocked,
            entity.isExtended,
            entity.isRetracted,
        )

    companion object {
        val contract =
            CompuktersPeripheralContract(
                "aeronautics:docking_connector",
                "docking_connector",
                "aeronautics.docking.DockingConnector",
            ) { location ->
                if (!location.level.hasChunkAt(location.position)) {
                    null
                } else {
                    (location.level.getBlockEntity(location.position) as? DockingConnectorBlockEntity)
                        ?.takeUnless { it.isRemoved }
                        ?.let { DockingEndpoint(it) { location.isReachable() } }
                }
            }
    }
}
