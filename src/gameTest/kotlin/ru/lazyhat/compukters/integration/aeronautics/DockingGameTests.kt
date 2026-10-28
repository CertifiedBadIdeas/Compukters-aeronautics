/*
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.integration.aeronautics

import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

@PrefixGameTestTemplate(false)
object DockingGameTests {
    @JvmStatic
    @GameTest(batch = "docking", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 200)
    fun nativeObservationAndReplacement(helper: GameTestHelper) {
        val pos = BlockPos(3, 3, 3)
        val state =
            BuiltInRegistries.BLOCK
                .get(ResourceLocation.parse("simulated:docking_connector"))
                .defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP)
        helper.setBlock(pos, state)
        var endpoint: DockingEndpoint? = null
        helper
            .startSequence()
            .thenExecuteAfter(5) {
                val entity = helper.getBlockEntity(pos) as DockingConnectorBlockEntity
                endpoint = DockingEndpoint(entity) { true }
                helper.assertTrue(endpoint!!.valid(), "Loaded exact connector is unavailable")
                val sample = endpoint!!.state()
                helper.assertTrue(sample.gameTick == helper.level.gameTime, "Wrong native observation tick")
                helper.assertTrue(!sample.connected && !sample.locked && sample.retracted, "Read-only acquisition changed native dock")
                val empty = endpoint!!.fluidState()
                helper.assertTrue(
                    empty.fluid == "" && empty.amountMb == 0 && empty.capacityMb == 1000,
                    "Wrong native connector buffer capacity",
                )
                val water =
                    dev.simulated_team.simulated.multiloader.tanks.CFluidType(
                        net.minecraft.world.level.material.Fluids.WATER,
                        net.minecraft.core.component.DataComponentPatch.EMPTY,
                    )
                dev.simulated_team.simulated.multiloader.tanks.SingleTank.applyInsert(
                    entity.tank,
                    water,
                    dev.simulated_team.simulated.service.SimFluidService.INSTANCE
                        .mbToLoaderUnits(650),
                )
                val retained = endpoint!!.fluidState()
                helper.assertTrue(
                    retained.gameTick == helper.level.gameTime && retained.fluid == "minecraft:water" && retained.amountMb == 650,
                    "Disconnected native stock was omitted or mutated",
                )
                helper.setBlock(pos.below(), Blocks.REDSTONE_BLOCK)
            }.thenWaitUntil {
                helper.assertTrue(endpoint!!.state().extended, "Native redstone did not extend connector")
            }.thenExecute {
                helper.assertTrue(!endpoint!!.state().locked, "Unpaired extended connector must not claim locked")
                helper.setBlock(pos.below(), Blocks.AIR)
            }.thenWaitUntil {
                helper.assertTrue(endpoint!!.state().retracted, "Native connector did not retract")
            }.thenExecute {
                helper.setBlock(pos, Blocks.AIR)
                helper.setBlock(pos, state)
                helper.assertTrue(!endpoint!!.valid(), "Old endpoint silently acquired a replacement")
            }.thenSucceed()
    }
}
