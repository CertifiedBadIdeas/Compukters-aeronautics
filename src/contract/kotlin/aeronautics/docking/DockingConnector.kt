/*
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package aeronautics.docking

import ru.lazyhat.compukters.api.contracts.GuestDevice
import ru.lazyhat.compukters.api.contracts.GuestCall

/** Native docking observations. Redstone controls extension and locking; locked ports fix both bodies. */
value class DockingState(
    val gameTick: Long,
    val connected: Boolean,
    val locked: Boolean,
    val extended: Boolean,
    val retracted: Boolean,
)

/** The connector's own finite buffer, including stock retained while disconnected. */
value class DockingFluidState(
    val gameTick: Long,
    val fluid: String,
    val amountMb: Int,
    val capacityMb: Int,
)

@GuestDevice("aeronautics:docking_connector", bindings = "DockingBindings")
interface DockingConnector {
    @GuestCall(binding = "dockState")
    fun state(): DockingState

    @GuestCall(binding = "dockFluidState")
    fun fluidState(): DockingFluidState
}
