# Compukters: Aeronautics

Control a physical part of a Sable construction from Guest Kotlin using Simulated's existing
**Swivel Bearing** (`simulated:swivel_bearing`). This is an independent NeoForge addon, with its own
Guest bundle and typed peripheral; the base Compukters mod does not depend on it.

The development target is Minecraft 1.21.1, NeoForge 21.1.252, Compukters 0.7, Simulated 1.3.2
(supplied by the Aeronautics 1.3.2 bundle), Sable 2.0.6 and Create 6.0.10. The exact Compukters
minimum is derived from the SDK used to build the addon. The mixin is intentionally restricted to
the pinned Simulated release. Upstream Create is neither patched nor included in this addon.

## Connect a servo

1. Build and assemble a normal Swivel Bearing using its native assembly mechanics. Attach the intended
   blocks with the upstream glue/assembly tools. Its moving side must be a separate physical construction.
2. Supply kinetic rotation to its cog input, as for the normal bearing. The through-shaft remains native.
3. Bind the computer and bearing to the same peripheral network with the Compukters Peripheral
   Configurator, then name the bearing `arm`. Discovery works on an assembled, loaded bearing.
   Devices on moving constructions additionally use the Compukters: Sable network integration.
4. Add `"aeronautics"` to the project's `addons` in `compukter.toml`.

```kotlin
import aeronautics.servo.Servo

fun main() {
    val arm = Servo.named("arm")
    arm.rotateBy(90.0, 45.0)
    check(arm.awaitStopped(1200))
    arm.rotateBy(-720.0, 90.0)
    check(arm.awaitStopped(1200))
    println(arm.state().angle)
    readln() // Keep the program alive to hold the position.
    arm.close()
}
```

## Motion and lifetime

`rotateBy(degrees, speed)` replaces any previous motion with a signed displacement from the actual
angle when the command is accepted. `moveTo(degrees, speed)` replaces it with an absolute unwrapped
target. Both have a one-argument overload using 90 degrees/second. Requested speed is 0.1–360 degrees/second;
actual movement is limited by available native kinetic RPM and the physical load. Reversing the command
does not require reversing the kinetic supply. An unpowered cog cannot advance the commanded target.
An active native Sequenced Gearshift instruction must finish before digital control starts.

Angles use degrees about the outward bearing axis and are relative to the physical base, including when
the base rotates. Positive angles follow the right-hand rule. The initial value is the native joint angle
in −180..180; complete turns are counted while the handle is acquired. Closing and reacquiring starts a
new angle observation session. Absolute targets and resulting relative targets are bounded to ±360000
degrees. Observations use server ticks and the upstream logical pose/solver velocity. External motion
faster than half a revolution per observation tick is outside the unwrapped-angle contract.

`state()` reports actual `angle`, final `targetAngle`, actual `angularVelocity` in degrees/second,
requested `speed`, `controlled`, `settled` and `gameTick`. The tick identifies the last bearing
observation, which can precede the request tick. The native motor follows a bounded waypoint
within 15 degrees of actual motion, so a blocked arm cannot silently skip full turns. Sable still owns
constraints, collisions, reaction forces and native motor stiffness/damping.

`awaitStopped(timeoutTicks)` returns `true` after five distinct observations within 0.5 degrees of the
target and at most 1 degree/second. It returns `false` on timeout; the target remains active. Timeouts
are 1–72000 server ticks, default 1200. `stop()` cancels remaining travel and holds the observed position;
braking takes physical time. Loss, replacement, disassembly or disconnection raises a catchable
`compukter.io.IOException` for a stale handle instead of reporting completion. Uninitialized physics
or an unavailable assembled mechanism may raise `IllegalStateException`.

Acquisition uses Compukters' exact-device exclusive lease. Other programs cannot acquire the same
bearing concurrently. `close()`, program exit/termination and observed network loss cancel digital
travel, capture the current actual angle and return control to native kinetics/redstone. A powered
native cog may immediately resume normal rotation after release. To hold the target, keep the program
running. Saved execution with an acquired servo is currently unsupported: unload/reload releases
control and the computer cold boots with its files retained. Dimension transfers are outside this version.

## Docking observations (API 1.1)

`aeronautics.docking.DockingConnector.named("fuel-port").state()` reads the native server tick,
`connected`, `locked`, `extended` and `retracted`. Bind ordinary Simulated docking connectors with
normal peripheral tools. The API never supplies redstone, locks a port or transfers fluid itself.
Native locked connectors physically fix their bodies; release them and wait for retraction before
commanding flight. A stale or replaced connector fails the exact-device handle contract.

## Development

The build uses the public Compukters SDK and produces Java 21 bytecode; Gradle requires JDK 25.
The default workspace layout is the `addons/aeronautics` submodule inside Compukters. Initialize it
from the Compukters root with `git submodule update --init addons/aeronautics`. Select a Compukters
source checkout explicitly when developing this repository elsewhere:

```sh
./gradlew-sandbox-dev-parallel-summary check -PcompuktersWorkspace=/path/to/Compukters
./gradlew-sandbox-dev-parallel runGameTestServer -PcompuktersWorkspace=/path/to/Compukters
```

Prepare `stageAddonDevelopmentDependencies` in the selected Compukters checkout first. Run
`updateCompuktersAddonAbiLock` separately when intentionally changing the Guest ABI. The build has no
dependency on the independent Orbitalis development stand. The stand can select this checkout through
its optional `aeronauticsCheckout` setting and consumes `developmentModJar`.

Behavioral unit tests cover angle unwrapping, signed multi-turn goals, speed/waypoint limits,
retargeting, stopping, invalid input and measured settling. Real GameTests exercise native bearing
assembly and Sable motion, a moving physical parent and a compiled Guest program with timeout and
control cleanup. No client worlds are changed by these server tests.

`DockingConnector.fluidState()` reads its own 1000 mB finite buffer, including residual stock
while disconnected. It never fills, drains or unlocks the native connector.
