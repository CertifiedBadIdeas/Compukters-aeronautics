/* Copyright 2026 Vsevolod Petrov (lazyhat). Licensed under Apache-2.0. */
import aeronautics.servo.Servo

fun main() {
    val arm = Servo.named("arm")
    arm.rotateBy(90.0, 45.0)
    if (!arm.awaitStopped(1200)) {
        arm.stop()
        println("Target not reached: check the kinetic supply and physical load")
        readln()
        arm.close()
        return
    }
    arm.rotateBy(-720.0, 90.0)
    if (!arm.awaitStopped(1200)) arm.stop()
    println("Holding at " + arm.state().angle + " degrees. Enter releases native control.")
    readln()
    arm.close()
}
