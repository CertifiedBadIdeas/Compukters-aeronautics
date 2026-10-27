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

import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.atan2

interface BearingAccess {
    fun getServoSession(): BearingSession?

    fun setServoSession(session: BearingSession?)

    fun setServoNativeAngle(degrees: Double)

    fun getServoSequenceLimit(): Double
}

class BearingSession(
    val child: UUID,
    val motion: FiniteRotation,
    val valid: () -> Boolean,
)

object BearingPhysics {
    data class Observation(
        val angle: Double,
        val velocity: Double,
    )

    /** Joint frames use each block's own facing; the common world rotation cancels. */
    @JvmStatic
    fun sample(entity: SwivelBearingBlockEntity): Observation? {
        val level = entity.level ?: return null
        val plate = entity.platePos ?: return null
        if (!entity.isAssembled || !level.hasChunkAt(plate)) return null
        val child =
            SubLevelContainer.getContainer(level as? ServerLevel ?: return null)?.getSubLevel(entity.subLevelID) as? ServerSubLevel
                ?: return null
        if (child.isRemoved) return null
        val parent = Sable.HELPER.getContaining(entity) as? ServerSubLevel
        val facing = entity.blockState.getValue(BlockStateProperties.FACING)
        val plateState = level.getBlockState(plate)
        if (!plateState.hasProperty(BlockStateProperties.FACING)) return null
        val baseOrientation = parent?.logicalPose()?.orientation() ?: Quaterniond()
        val childOrientation = child.logicalPose().orientation()
        val baseFrame = Quaterniond(baseOrientation).mul(Quaterniond(facing.rotation))
        val childFrame = Quaterniond(childOrientation).mul(Quaterniond(plateState.getValue(BlockStateProperties.FACING).rotation))
        val relative = baseFrame.invert().mul(childFrame).normalize()
        val angle = FiniteRotation.wrap(Math.toDegrees(2.0 * atan2(relative.y, relative.w)))
        val axis =
            Quaterniond(
                baseOrientation,
            ).transform(Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble()))
        val system = SubLevelContainer.getContainer(level)?.physicsSystem() ?: return null
        if (child.massTracker.centerOfMass == null || child.massTracker.mass <= 0.0) return null
        val childVelocity = system.getPhysicsHandle(child).getAngularVelocity(Vector3d())
        val parentVelocity = parent?.let { system.getPhysicsHandle(it).getAngularVelocity(Vector3d()) } ?: Vector3d()
        val velocity = Math.toDegrees(childVelocity.sub(parentVelocity).dot(axis))
        return if (angle.isFinite() && velocity.isFinite()) Observation(angle, velocity) else null
    }

    @JvmStatic
    fun tick(
        entity: SwivelBearingBlockEntity,
        session: BearingSession,
    ) {
        val level = entity.level ?: return
        if (level.isClientSide) return
        val sample = sample(entity)
        if (entity.subLevelID != session.child || sample == null || !session.valid()) {
            release(entity, session)
            return
        }
        session.motion.observe(sample.angle, sample.velocity, level.gameTime)
    }

    fun release(
        entity: SwivelBearingBlockEntity,
        session: BearingSession,
    ) {
        val access = entity as BearingAccess
        if (access.getServoSession() !== session) return
        if (session.motion.active) access.setServoNativeAngle(sample(entity)?.angle ?: FiniteRotation.wrap(session.motion.angle))
        access.setServoSession(null)
        if (!entity.isRemoved && entity.level?.isClientSide == false) entity.updateServoCoefficients()
    }
}
