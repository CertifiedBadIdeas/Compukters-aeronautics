/*
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package aeronautics.docking

import ru.lazyhat.compukters.api.contracts.GuestDevice
import ru.lazyhat.compukters.api.contracts.GuestRead
import ru.lazyhat.compukters.api.contracts.GuestRecord

/** Native docking observations. Redstone controls extension and locking; locked ports fix both bodies. */
@GuestRecord
value class DockingState(
    val gameTick: Long,
    val connected: Boolean,
    val locked: Boolean,
    val extended: Boolean,
    val retracted: Boolean,
)

/** The connector's own finite buffer, including stock retained while disconnected. */
@GuestRecord
value class DockingFluidState(
    val gameTick: Long,
    val fluid: String,
    val amountMb: Int,
    val capacityMb: Int,
)

@GuestDevice("aeronautics:docking_connector", bindings = "DockingBindings")
interface DockingConnector {
    @GuestRead(binding = "dockState")
    fun state(): DockingState

    @GuestRead(binding = "dockFluidState")
    fun fluidState(): DockingFluidState
}
