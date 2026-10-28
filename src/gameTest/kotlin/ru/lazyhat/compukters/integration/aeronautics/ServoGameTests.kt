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

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType
import dev.ryanhcode.sable.companion.math.BoundingBox3i
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import org.joml.Vector3d
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import kotlin.math.abs
import net.minecraft.util.Unit as MinecraftUnit

@PrefixGameTestTemplate(false)
object ServoGameTests {
    @JvmStatic
    @GameTest(batch = "servo_physics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2000)
    fun finitePhysicalTurns(helper: GameTestHelper) = physicalTurns(helper, false)

    @JvmStatic
    @GameTest(batch = "servo_physics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2000)
    fun turnsOnMovingBase(helper: GameTestHelper) = physicalTurns(helper, true)

    @JvmStatic
    @GameTest(batch = "servo_physics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2000)
    fun turnsFacingDown(helper: GameTestHelper) = physicalTurns(helper, false, Direction.DOWN)

    @JvmStatic
    @GameTest(batch = "servo_physics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2000)
    fun turnsFacingNorth(helper: GameTestHelper) = physicalTurns(helper, false, Direction.NORTH)

    @JvmStatic
    @GameTest(batch = "servo_physics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2000)
    fun turnsFacingSouth(helper: GameTestHelper) = physicalTurns(helper, false, Direction.SOUTH)

    @JvmStatic
    @GameTest(batch = "servo_physics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2000)
    fun turnsFacingWest(helper: GameTestHelper) = physicalTurns(helper, false, Direction.WEST)

    @JvmStatic
    @GameTest(batch = "servo_physics", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 2000)
    fun turnsFacingEast(helper: GameTestHelper) = physicalTurns(helper, false, Direction.EAST)

    private fun physicalTurns(
        helper: GameTestHelper,
        movingBase: Boolean,
        facing: Direction = Direction.UP,
    ) {
        val fixture =
            Fixture(
                helper,
                movingBase,
                if (movingBase) {
                    32
                } else if (facing ==
                    Direction.UP
                ) {
                    0
                } else {
                    128 + facing.ordinal * 32
                },
                facing,
            )
        lateinit var endpoint: ServoEndpoint
        var start = 0.0
        val sequence = helper.startSequence()
        sequence
            .thenExecuteAfter(10) { fixture.assemble() }
            .thenWaitUntil {
                helper.assertTrue(
                    BearingPhysics.sample(fixture.bearing) != null && fixture.bearing.extraKinetics.speed != 0f,
                    "bearing physics or kinetic input not initialized",
                )
            }.thenExecute {
                endpoint = ServoEndpoint(fixture.bearing) { true }
                endpoint.acquire()
                endpoint.activate()
                start = endpoint.active().angle
                endpoint.active().rotateBy(90.0, 90.0)
            }

        fun awaitTarget(expected: () -> Double) {
            sequence.thenWaitUntil {
                val motion = endpoint.active()
                helper.assertTrue(
                    motion.settled,
                    "angle=${motion.angle}, target=${motion.target}, speed=${motion.velocity}, native=${fixture.bearing.targetAngleDegrees}",
                )
                helper.assertTrue(abs(motion.angle - expected()) < 0.5, "wrong physical angle ${motion.angle}; expected ${expected()}")
            }
        }
        awaitTarget { start + 90.0 }
        sequence.thenExecute { endpoint.active().rotateBy(-90.0, 90.0) }
        sequence.thenWaitUntil {
            helper.assertTrue(endpoint.active().settled && abs(endpoint.active().angle - start) < 1.0, "reverse rotation did not settle")
        }
        sequence.thenExecute { endpoint.active().rotateBy(720.0, 180.0) }
        sequence.thenWaitUntil {
            val motion = endpoint.active()
            helper.assertTrue(
                motion.settled && abs(motion.angle - (start + 720.0)) < 1.5,
                "multiple turns did not physically complete: angle=${motion.angle}, target=${motion.target}, speed=${motion.velocity}",
            )
        }
        sequence
            .thenExecute {
                if (movingBase) {
                    helper.assertTrue(
                        fixture.parent!!
                            .logicalPose()
                            .orientation()
                            .angle() > 0.1,
                        "physical base never moved",
                    )
                }
                endpoint.release()
                helper.assertTrue((fixture.bearing as BearingAccess).getServoSession() == null, "release retained digital control")
            }.thenSucceed()
    }

    @JvmStatic
    @GameTest(batch = "servo_guest", template = "bastion/mobs/empty", templateNamespace = "minecraft", timeoutTicks = 100000)
    fun guestMotionTimeoutAndRelease(helper: GameTestHelper) {
        val fixture = Fixture(helper, false, 64)
        val computer = fixture.position.west(2)
        helper.level.setBlockAndUpdate(computer, CompuktersRegistry.COMPUTER.get().defaultBlockState())
        val guest = GuestComputerScenario(helper, BlockPos.ZERO) { computer }
        val sequence = helper.startSequence()
        sequence
            .thenExecuteAfter(10) { fixture.assemble() }
            .thenWaitUntil {
                helper.assertTrue(
                    BearingPhysics.sample(fixture.bearing) != null && fixture.bearing.extraKinetics.speed != 0f,
                    "bearing not ready",
                )
            }.thenExecute {
                PeripheralNetworkGameTestFixtures.bind(helper, computer, fixture.position)
                helper.assertTrue(
                    PeripheralNetworkGameTestFixtures.name(
                        helper,
                        fixture.position,
                        "arm",
                    ) == PeripheralConfiguratorSaveResult.NAMED_DEVICE,
                    "could not name bearing",
                )
            }
        guest.prepare(
            sequence,
            """
            import aeronautics.servo.Servo
            fun main() {
                val servo = Servo.named("arm")
                servo.rotateBy(90.0, 90.0)
                check(servo.awaitStopped(600))
                val observation = servo.state()
                check(observation.gameTick >= 0L)
                check(observation.controlled && observation.settled)
                check(observation.angularVelocity >= -1.0 && observation.angularVelocity <= 1.0)
                println("servo-rotated")
                readln()
                servo.rotateBy(720.0)
                check(!servo.awaitStopped(10))
                servo.stop()
                check(servo.awaitStopped(200))
                servo.close()
                var stale = false
                try { servo.state() } catch (failure: compukter.io.IOException) { stale = true }
                check(stale)
                println("servo-closed")
                readln()
                val next = Servo.named("arm")
                next.rotateBy(720.0)
                println("servo-running")
                readln()
                var disconnected = false
                try { next.state() } catch (failure: compukter.io.IOException) { disconnected = true }
                check(disconnected)
                println("servo-disconnected")
                readln()
                val restored = Servo.named("arm")
                restored.rotateBy(720.0)
                println("servo-reconnected")
                readln()
            }
            """.trimIndent(),
        )
        guest.awaitMarker(sequence, "servo-rotated")
        sequence
            .thenExecute { fixture.setPower(0) }
            .thenWaitUntil { helper.assertTrue(fixture.bearing.extraKinetics.speed == 0f, "kinetic input did not stop") }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "servo-closed")
        sequence
            .thenExecute {
                helper.assertTrue((fixture.bearing as BearingAccess).getServoSession() == null, "closed servo remained controlled")
                fixture.setPower(32)
            }.thenWaitUntil { helper.assertTrue(fixture.bearing.extraKinetics.speed != 0f, "kinetic input did not restart") }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "servo-running")
        sequence
            .thenExecute { PeripheralNetworkGameTestFixtures.unbind(helper, fixture.position) }
            .thenWaitUntil {
                helper.assertTrue(
                    (fixture.bearing as BearingAccess).getServoSession() == null,
                    "network loss retained servo control",
                )
            }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "servo-disconnected")
        sequence.thenExecute {
            PeripheralNetworkGameTestFixtures.bind(helper, computer, fixture.position)
            helper.assertTrue(
                PeripheralNetworkGameTestFixtures.name(helper, fixture.position, "arm") == PeripheralConfiguratorSaveResult.NAMED_DEVICE,
                "could not rebind bearing",
            )
        }
        guest.resume(sequence)
        guest.awaitMarker(sequence, "servo-reconnected")
        guest.terminate(sequence)
        sequence.thenWaitUntil {
            helper.assertTrue((fixture.bearing as BearingAccess).getServoSession() == null, "terminated program retained servo")
        }
        sequence.thenSucceed()
    }

    private class Fixture(
        val helper: GameTestHelper,
        moving: Boolean,
        originX: Int,
        val facing: Direction = Direction.UP,
    ) {
        val container = requireNotNull(SubLevelContainer.getContainer(helper.level))
        var parent: ServerSubLevel? = null
        var position = BlockPos(originX, if (moving) 200 else 100, 0)
        private val worldPosition = position
        private val forcedChunks = mutableListOf<ChunkPos>()
        private var baseConstraint: PhysicsConstraintHandle? = null
        private val inputSide = if (facing.axis == Direction.Axis.Y) Direction.EAST else Direction.UP
        private val cog get() = position.relative(inputSide)
        private val motor get() = cog.relative(facing.opposite)
        var child: ServerSubLevel? = null
        val originalPosition = org.joml.Vector3d(position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
        val bearing get() = helper.level.getBlockEntity(position) as SwivelBearingBlockEntity
        private val paused = container.physicsSystem().paused

        init {
            container.physicsSystem().setPaused(false)
            // Vanilla GameTest deliberately randomizes structure origins up to 15 million blocks.
            // Keep native float physics in a bounded fixture, and explicitly own its chunk tickets.
            for (x in ((position.x - 5) shr 4)..((position.x + 5) shr 4)) {
                for (z in -1..0) {
                    val chunk = ChunkPos(x, z)
                    if (!helper.level.forcedChunks.contains(chunk.toLong())) {
                        helper.level.setChunkForced(x, z, true)
                        forcedChunks.add(chunk)
                    }
                    helper.level.getChunk(x, z)
                }
            }
            helper.onEachTick {
                helper.level.resetEmptyTime()
            }
            val block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("simulated", "swivel_bearing"))
            helper.level.setBlockAndUpdate(position, block.defaultBlockState().setValue(BlockStateProperties.FACING, facing))
            helper.level.setBlockAndUpdate(motor, AllBlocks.CREATIVE_MOTOR.defaultState.setValue(BlockStateProperties.FACING, facing))
            if (moving) {
                val base = position.below()
                helper.level.setBlockAndUpdate(base, Blocks.IRON_BLOCK.defaultBlockState())
                parent =
                    SubLevelAssemblyHelper.assembleBlocks(
                        helper.level,
                        position,
                        listOf(position, base, position.east().below()),
                        BoundingBox3i(position.x, base.y, position.z, position.x + 2, position.y + 1, position.z + 1),
                    )
                forceLoad(requireNotNull(parent))
                position = requireNotNull(parent).plot.centerBlock
                val body = requireNotNull(parent)
                val system = container.physicsSystem()
                baseConstraint =
                    system.pipeline.addConstraint(
                        null,
                        body,
                        RotaryConstraintConfiguration(
                            Vector3d(body.logicalPose().position()),
                            Vector3d(body.logicalPose().rotationPoint()),
                            Vector3d(0.0, 1.0, 0.0),
                            Vector3d(0.0, 1.0, 0.0),
                        ),
                    )
                system.getPhysicsHandle(body).addLinearAndAngularVelocity(Vector3d(), Vector3d(0.0, 0.4, 0.0))
            }
            helper.testInfo.addListener(
                object : GameTestListener {
                    override fun testStructureLoaded(info: GameTestInfo) = Unit

                    override fun testPassed(
                        info: GameTestInfo,
                        runner: GameTestRunner,
                    ) = cleanup()

                    override fun testFailed(
                        info: GameTestInfo,
                        runner: GameTestRunner,
                    ) = cleanup()

                    override fun testAddedForRerun(
                        info: GameTestInfo,
                        rerun: GameTestInfo,
                        runner: GameTestRunner,
                    ) = cleanup()
                },
            )
        }

        fun assemble() {
            helper.level.setBlockAndUpdate(position.relative(facing), Blocks.IRON_BLOCK.defaultBlockState())
            bearing.assemble()
            helper.assertTrue(bearing.isAssembled, "native bearing assembly failed: ${bearing.lastAssemblyException}")
            child = container.getSubLevel(bearing.subLevelID) as ServerSubLevel
            forceLoad(requireNotNull(child))
            val plate = requireNotNull(bearing.platePos)
            val armDirection = if (facing.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            (1..3).forEach {
                helper.level.setBlockAndUpdate(
                    plate.relative(facing).relative(armDirection, it),
                    Blocks.IRON_BLOCK.defaultBlockState(),
                )
            }
            helper.level.setBlockAndUpdate(cog, AllBlocks.COGWHEEL.defaultState.setValue(BlockStateProperties.AXIS, facing.axis))
            setPower(32)
        }

        fun setPower(rpm: Int) {
            (helper.level.getBlockEntity(motor) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)
        }

        private fun forceLoad(body: ServerSubLevel) {
            container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
        }

        private fun cleanup() {
            baseConstraint?.remove()
            listOfNotNull(child, parent).forEach { body ->
                container.removeForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, MinecraftUnit.INSTANCE)
                if (!body.isRemoved) container.removeSubLevel(body, SubLevelRemovalReason.REMOVED)
            }
            listOf(
                worldPosition,
                worldPosition.below(),
                worldPosition.relative(inputSide),
                worldPosition.relative(inputSide).relative(facing.opposite),
                worldPosition.west(2),
            ).forEach { helper.level.removeBlock(it, false) }
            forcedChunks.forEach { helper.level.setChunkForced(it.x, it.z, false) }
            container.physicsSystem().setPaused(paused)
        }
    }
}

@EventBusSubscriber(modid = "compukters_aeronautics", bus = EventBusSubscriber.Bus.MOD)
object ServoGameTestRegistration {
    @JvmStatic @SubscribeEvent
    fun register(event: RegisterGameTestsEvent) {
        event.register(ServoGameTests::class.java)
        event.register(DockingGameTests::class.java)
    }
}
