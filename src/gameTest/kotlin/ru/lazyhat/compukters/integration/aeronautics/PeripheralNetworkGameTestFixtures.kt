/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.aeronautics

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.extensions.IBlockEntityExtension
import net.neoforged.neoforge.common.util.FakePlayerFactory
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBindingResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorMode
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions
import java.util.UUID

/** Real configurator actions at absolute world/plot positions, with persisted exact-device identity. */
internal object PeripheralNetworkGameTestFixtures {
    fun bind(
        helper: GameTestHelper,
        vararg positions: BlockPos,
    ) {
        val player = player(helper, positions.first())
        val stack = player.mainHandItem
        val item = stack.item as PeripheralConfiguratorItem
        item.selectMode(player.mainHandItem, 1)
        for (position in positions) {
            player.setPos(requireNotNull(PeripheralWorldPositions.project(helper.level, Vec3.atCenterOf(position))))
            val result = PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, position, Direction.WEST)
            helper.assertTrue(
                result in listOf(PeripheralBindingResult.CREATED, PeripheralBindingResult.SELECTED, PeripheralBindingResult.BOUND),
                "Could not bind $position: $result",
            )
        }
        helper.assertTrue(item.selectedNetwork(stack) != null, "Configurator did not select a network")
    }

    fun unbind(
        helper: GameTestHelper,
        position: BlockPos,
    ) {
        val player = player(helper, position)
        val item = player.mainHandItem.item as PeripheralConfiguratorItem
        item.selectMode(player.mainHandItem, 1)
        player.setShiftKeyDown(true)
        try {
            val result = PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, position, Direction.WEST)
            helper.assertTrue(result == PeripheralBindingResult.REMOVED, "Could not unbind $position: $result")
        } finally {
            player.setShiftKeyDown(false)
        }
    }

    fun name(
        helper: GameTestHelper,
        position: BlockPos,
        name: String,
    ): PeripheralConfiguratorSaveResult {
        val player = player(helper, position)
        val anchor = position
        // Binding creates this persisted stamp. Read it instead of manufacturing an identity for the request.
        val data = (helper.level.getBlockEntity(anchor) as? IBlockEntityExtension)?.persistentData
        val instance = runCatching { UUID.fromString(data?.getString("compukters_peripheral_instance")) }.getOrNull()
        val snapshot =
            PeripheralConfiguratorServer.inspect(
                helper.level,
                PeripheralConfiguratorContext(position, Direction.WEST, instance),
                PeripheralConfiguratorMode.EDIT_DEVICE,
            )
        return PeripheralConfiguratorServer.save(player, InteractionHand.MAIN_HAND, snapshot.context, name)
    }

    private fun player(
        helper: GameTestHelper,
        position: BlockPos,
    ) = FakePlayerFactory.getMinecraft(helper.level).also {
        it.setShiftKeyDown(false)
        it.setPos(requireNotNull(PeripheralWorldPositions.project(helper.level, Vec3.atCenterOf(position))))
        it.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get()))
    }
}
