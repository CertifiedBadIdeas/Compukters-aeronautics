/*
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package aeronautics.docking

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider

/** Native docking observations. Redstone controls extension and locking; locked ports fix both bodies. */
public value class DockingState(
    public val gameTick: Long,
    public val connected: Boolean,
    public val locked: Boolean,
    public val extended: Boolean,
    public val retracted: Boolean,
)

/** The connector's own finite buffer, including stock retained while disconnected. */
public value class DockingFluidState(
    public val gameTick: Long,
    public val fluid: String,
    public val amountMb: Int,
    public val capacityMb: Int,
)

public value class DockingConnector internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<DockingConnector>("aeronautics:docking_connector") {
        override fun wrap(handle: Int): DockingConnector = DockingConnector(handle)
    }

    public fun state(): DockingState = DockingBindings.dockState(handle)
    public fun fluidState(): DockingFluidState = DockingBindings.dockFluidState(handle)
}

private object DockingBindings {
    external fun dockState(handle: Int): DockingState
    external fun dockFluidState(handle: Int): DockingFluidState
}
