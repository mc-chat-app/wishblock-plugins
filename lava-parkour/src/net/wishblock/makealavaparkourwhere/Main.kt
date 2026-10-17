package net.wishblock.makealavaparkourwhere

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class Main : JavaPlugin(), Listener {
  private val courses = mutableListOf<Course>()
  private val standingOn = mutableMapOf<UUID, Platform>()

  override fun onEnable() {
    server.pluginManager.registerEvents(this, this)
    for (line in config.getStringList("courses")) {
      val p = line.split(",")
      val world = server.getWorld(p[0]) ?: continue
      courses += Course(world, p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt())
    }
  }

  private fun save() {
    config.set("courses", courses.map { "${it.world.name},${it.x},${it.y},${it.z},${it.fx},${it.fz}" })
    saveConfig()
  }

  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
    val player = sender as? Player ?: run { sender.sendMessage("Only players can build a lava parkour."); return true }
    val loc = player.location
    val yaw = ((loc.yaw % 360) + 360) % 360
    val (fx, fz) = when {
      yaw < 45 || yaw >= 315 -> 0 to 1
      yaw < 135 -> -1 to 0
      yaw < 225 -> 0 to -1
      else -> 1 to 0
    }
    val course = Course(loc.world, loc.blockX, loc.blockY, loc.blockZ, fx, fz)
    course.build()
    courses += course
    save()
    player.teleport(course.start())
    player.sendMessage(Component.text("Lava parkour built! Jump across - but every platform melts once you jump off it!", NamedTextColor.GOLD))
    player.playSound(player.location, Sound.BLOCK_LAVA_AMBIENT, 1f, 1f)
    return true
  }

  inner class Platform(val course: Course, val blocks: List<Block>) {
    var melting = false
  }

  inner class Course(val world: World, val x: Int, val y: Int, val z: Int, val fx: Int, val fz: Int) {
    // the sideways direction, square to the way the course runs
    private val px = -fz
    private val pz = fx
    val platforms = (0 until PLATFORMS).map { i ->
      val f0 = 3 + 3 * i
      Platform(this, (f0..f0 + 1).flatMap { f -> (-1..1).map { p -> at(f, -1, p) } })
    }

    fun at(f: Int, up: Int, p: Int): Block = world.getBlockAt(x + fx * f + px * p, y + up, z + fz * f + pz * p)

    fun start(): Location {
      val b = at(0, 0, 0)
      return b.location.add(0.5, 0.0, 0.5).apply { yaw = when { fz == 1 -> 0f; fx == -1 -> 90f; fz == -1 -> 180f; else -> -90f } }
    }

    fun build() {
      for (f in -2..LENGTH + 1) for (p in -3..3) {
        at(f, -2, p).type = Material.STONE_BRICKS
        at(f, -1, p).type = Material.STONE_BRICKS
        for (up in 0..5) at(f, up, p).type = Material.AIR
      }
      for (f in 2..LENGTH - 3) for (p in -2..2) at(f, -1, p).type = Material.LAVA
      for (f in LENGTH - 2..LENGTH) for (p in -1..1) at(f, -1, p).type = Material.GOLD_BLOCK
      for (f in -1..1) for (p in -1..1) at(f, -1, p).type = Material.POLISHED_BLACKSTONE
      reset()
    }

    fun reset() {
      for (pl in platforms) {
        pl.melting = false
        pl.blocks.forEach { it.type = PLATFORM }
      }
    }

    // where the player is, counted along and across the course
    private fun local(l: Location): Pair<Double, Double> {
      val dx = l.x - (x + 0.5)
      val dz = l.z - (z + 0.5)
      return (dx * fx + dz * fz) to (dx * px + dz * pz)
    }

    fun contains(l: Location): Boolean {
      if (l.world != world || l.y < y - 4 || l.y > y + 8) return false
      val (f, p) = local(l)
      return f > -3 && f < LENGTH + 2 && p > -4 && p < 4
    }

    fun onFinish(l: Location): Boolean {
      val (f, p) = local(l)
      return l.y >= y - 0.1 && l.y < y + 1 && f > LENGTH - 2.8 && p > -1.8 && p < 1.8
    }

    fun platformAt(b: Block) = platforms.firstOrNull { b in it.blocks }
  }

  private fun melt(pl: Platform) {
    if (pl.melting) return
    pl.melting = true
    val stages = listOf(Material.ORANGE_TERRACOTTA, Material.MAGMA_BLOCK, Material.LAVA)
    stages.forEachIndexed { i, mat ->
      server.scheduler.runTaskLater(this, Runnable {
        if (!pl.melting) return@Runnable
        pl.blocks.forEach { it.type = mat }
        val mid = pl.blocks[pl.blocks.size / 2].location.add(0.5, 1.0, 0.5)
        mid.world.spawnParticle(if (mat == Material.LAVA) Particle.LAVA else Particle.SMOKE, mid, 12, 0.6, 0.2, 0.6, 0.02)
        mid.world.playSound(mid, if (mat == Material.LAVA) Sound.BLOCK_LAVA_POP else Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1f)
      }, 4L + i * 10L)
    }
    // it cools back into a platform after a while, so the course can be played again
    server.scheduler.runTaskLater(this, Runnable {
      if (!pl.melting) return@Runnable
      pl.melting = false
      pl.blocks.forEach { it.type = PLATFORM }
      val mid = pl.blocks[pl.blocks.size / 2].location.add(0.5, 1.0, 0.5)
      mid.world.spawnParticle(Particle.CLOUD, mid, 10, 0.6, 0.2, 0.6, 0.01)
    }, 20L * 12)
  }

  @EventHandler
  fun onMove(e: PlayerMoveEvent) {
    val player = e.player
    val to = e.to
    val course = courses.firstOrNull { it.contains(to) }
    if (course == null) { standingOn.remove(player.uniqueId); return }

    if (player.isInLava || to.block.type == Material.LAVA || to.y < course.y - 0.5) {
      standingOn.remove(player.uniqueId)
      player.fireTicks = 0
      course.reset()
      player.teleport(course.start())
      server.scheduler.runTask(this, Runnable { player.fireTicks = 0 })
      player.showTitle(Title.title(Component.text("SPLASH!", NamedTextColor.RED), Component.text("Back to the start!", NamedTextColor.GOLD)))
      player.playSound(player.location, Sound.ENTITY_GENERIC_BURN, 1f, 1f)
      return
    }

    if (course.onFinish(to) && player.isOnGround) {
      standingOn.remove(player.uniqueId)
      player.showTitle(Title.title(Component.text("You made it!", NamedTextColor.GOLD), Component.text("You beat the lava parkour!", NamedTextColor.YELLOW)))
      player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
      player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, player.location.add(0.0, 1.0, 0.0), 60, 0.5, 1.0, 0.5, 0.3)
      course.reset()
      player.teleport(course.start())
      return
    }

    val current = standingOn[player.uniqueId]
    val under = if (player.isOnGround) course.platformAt(to.clone().subtract(0.0, 0.2, 0.0).block) else null
    if (current != null && current !== under) {
      melt(current)
      standingOn.remove(player.uniqueId)
    }
    if (under != null && !under.melting) standingOn[player.uniqueId] = under
  }

  @EventHandler
  fun onDamage(e: EntityDamageEvent) {
    val player = e.entity as? Player ?: return
    if (e.cause != EntityDamageEvent.DamageCause.LAVA && e.cause != EntityDamageEvent.DamageCause.FIRE &&
      e.cause != EntityDamageEvent.DamageCause.FIRE_TICK) return
    if (courses.any { it.contains(player.location) }) {
      e.isCancelled = true
      player.fireTicks = 0
    }
  }

  @EventHandler
  fun onQuit(e: PlayerQuitEvent) { standingOn.remove(e.player.uniqueId) }

  companion object {
    const val PLATFORMS = 8
    const val LENGTH = 29 // the far edge of the gold finish
    val PLATFORM: Material = Material.SMOOTH_STONE
  }
}
