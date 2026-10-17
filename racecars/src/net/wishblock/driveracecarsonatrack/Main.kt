package net.wishblock.driveracecarsonatrack

import io.papermc.paper.entity.TeleportFlag
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.wishblock.lib.Textures
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.time.Duration
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a car is doing right now, kept only while the server runs. */
class CarState {
  var speed = 0.0
  var fall = 0.0
  var lapStart = 0L
  var wentRound = false
  var lastZ = Double.NaN
  var ticks = 0
}

class Main : JavaPlugin(), Listener {
  private val colors = listOf("red", "blue", "green", "yellow")
  private val carKey by lazy { NamespacedKey(this, "car") }
  private val seatKey by lazy { NamespacedKey(this, "seat_of") }
  private val itemKey by lazy { NamespacedKey(this, "car_item") }
  private val states = HashMap<UUID, CarState>()

  // The track: an oval with two straights along z, curbs, a wall round the outside.
  private val straight = 20
  private val inner = 7.0
  private val outer = 14.0
  private val trackBlocks = setOf(
    Material.GRAY_CONCRETE, Material.RED_CONCRETE, Material.WHITE_CONCRETE, Material.BLACK_CONCRETE,
  )

  // How high the car's middle sits above the ground, so the driver looks seated in the cockpit.
  private val seatY = 0.2

  override fun onEnable() {
    Textures.register(this)
    server.pluginManager.registerEvents(this, this)
    server.scheduler.runTaskTimer(this, Runnable { tick() }, 1L, 1L)
  }

  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
    val player = sender as? Player ?: run { sender.sendMessage("Only a player can do that."); return true }
    when (command.name.lowercase()) {
      "racecar" -> {
        val color = args.firstOrNull()?.lowercase() ?: "red"
        if (color !in colors) {
          player.sendMessage(Component.text("Colors: ${colors.joinToString(", ")}", NamedTextColor.RED))
          return true
        }
        player.inventory.addItem(carItem(color))
        player.sendMessage(Component.text("Here's your ${color} racecar! Right-click the ground to put it down.", NamedTextColor.GOLD))
      }
      "racetrack" -> {
        buildTrack(player)
      }
    }
    return true
  }

  override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
    if (command.name.equals("racecar", true) && args.size == 1) colors.filter { it.startsWith(args[0].lowercase()) } else emptyList()

  private fun carItem(color: String): ItemStack {
    val item = ItemStack(Material.MINECART)
    item.editMeta { meta ->
      meta.displayName(Component.text("${color.replaceFirstChar { it.uppercase() }} Racecar", NamedTextColor.GOLD)
        .decoration(TextDecoration.ITALIC, false))
      meta.lore(listOf(
        Component.text("Right-click the ground to park it", NamedTextColor.GRAY),
        Component.text("W/S: gas and brake, A/D: steer", NamedTextColor.GRAY),
        Component.text("Space: handbrake, Shift: get out", NamedTextColor.GRAY),
      ))
      meta.setItemModel(NamespacedKey(this, "racecar_$color"))
      meta.persistentDataContainer.set(itemKey, PersistentDataType.STRING, color)
    }
    return item
  }

  // ---------- Placing, getting in, picking up ----------

  @EventHandler
  fun onUseItem(event: PlayerInteractEvent) {
    val item = event.item ?: return
    val color = item.itemMeta?.persistentDataContainer?.get(itemKey, PersistentDataType.STRING) ?: return
    event.isCancelled = true
    if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
    val block = event.clickedBlock ?: return
    val ground = if (event.blockFace == BlockFace.UP) block.location.add(0.5, 1.0, 0.5)
    else block.getRelative(event.blockFace).location.add(0.5, 0.0, 0.5)
    spawnCar(ground, event.player.location.yaw, color)
    if (event.player.gameMode != GameMode.CREATIVE) item.amount -= 1
    event.player.playSound(ground, Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.6f)
  }

  private fun spawnCar(ground: Location, yaw: Float, color: String): ItemDisplay {
    val at = ground.clone().add(0.0, seatY, 0.0)
    at.yaw = yaw
    at.pitch = 0f
    val car = ground.world.spawn(at, ItemDisplay::class.java) { d ->
      d.setItemStack(ItemStack(Material.MINECART).apply { editMeta { it.setItemModel(NamespacedKey(this@Main, "racecar_$color")) } })
      d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
      d.transformation = Transformation(Vector3f(0f, (0.5 - seatY).toFloat(), 0f), Quaternionf(), Vector3f(1f, 1f, 1f), Quaternionf())
      d.teleportDuration = 2
      d.viewRange = 2f
      d.persistentDataContainer.set(carKey, PersistentDataType.STRING, color)
    }
    spawnSeat(car)
    return car
  }

  /** The invisible box a player clicks to get in, or hits to pick the car up. Only there while the car is empty. */
  private fun spawnSeat(car: ItemDisplay) {
    val ground = car.location.clone().subtract(0.0, seatY, 0.0)
    ground.yaw = 0f
    ground.world.spawn(ground, Interaction::class.java) { i ->
      i.interactionWidth = 2.2f
      i.interactionHeight = 0.9f
      i.isResponsive = true
      i.persistentDataContainer.set(seatKey, PersistentDataType.STRING, car.uniqueId.toString())
    }
  }

  private fun carOf(seat: Interaction): ItemDisplay? {
    val id = seat.persistentDataContainer.get(seatKey, PersistentDataType.STRING) ?: return null
    return Bukkit.getEntity(UUID.fromString(id)) as? ItemDisplay
  }

  @EventHandler
  fun onClickSeat(event: PlayerInteractEntityEvent) {
    val seat = event.rightClicked as? Interaction ?: return
    if (!seat.persistentDataContainer.has(seatKey)) return
    event.isCancelled = true
    if (event.hand != EquipmentSlot.HAND) return
    val car = carOf(seat) ?: run { seat.remove(); return }
    if (car.passengers.isNotEmpty() || event.player.isInsideVehicle) return
    seat.remove()
    car.addPassenger(event.player)
    states[car.uniqueId] = CarState()
    event.player.playSound(car.location, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.7f, 1.5f)
    event.player.sendActionBar(Component.text("W/S gas & brake, A/D steer, Shift to get out", NamedTextColor.YELLOW))
  }

  @EventHandler
  fun onHitSeat(event: PrePlayerAttackEntityEvent) {
    val seat = event.attacked as? Interaction ?: return
    if (!seat.persistentDataContainer.has(seatKey)) return
    event.isCancelled = true
    val car = carOf(seat)
    val color = car?.persistentDataContainer?.get(carKey, PersistentDataType.STRING) ?: "red"
    car?.remove()
    seat.remove()
    if (event.player.gameMode != GameMode.CREATIVE || !event.player.inventory.containsAtLeast(carItem(color), 1)) {
      event.player.inventory.addItem(carItem(color))
    }
    event.player.playSound(seat.location, Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f)
  }

  @EventHandler
  fun onDismount(event: EntityDismountEvent) {
    val car = event.dismounted as? ItemDisplay ?: return
    if (!car.persistentDataContainer.has(carKey)) return
    states.remove(car.uniqueId)
    server.scheduler.runTask(this, Runnable {
      if (car.isValid && car.passengers.isEmpty()) spawnSeat(car)
    })
  }

  // ---------- Driving ----------

  private fun tick() {
    for (player in server.onlinePlayers) {
      val car = player.vehicle as? ItemDisplay ?: continue
      if (!car.persistentDataContainer.has(carKey)) continue
      drive(player, car, states.getOrPut(car.uniqueId) { CarState() })
    }
  }

  private fun solid(world: World, x: Double, y: Double, z: Double) =
    !world.getBlockAt(floor(x).toInt(), floor(y).toInt(), floor(z).toInt()).isPassable

  private fun drive(player: Player, car: ItemDisplay, s: CarState) {
    val input = player.currentInput
    val loc = car.location
    val world = car.world
    var feetY = loc.y - seatY
    val under = world.getBlockAt(floor(loc.x).toInt(), floor(feetY - 0.05).toInt(), floor(loc.z).toInt())
    val onTrack = under.type in trackBlocks
    val top = if (onTrack) 0.8 else 0.35
    s.ticks++

    // Gas, brake, reverse, coasting.
    when {
      input.isForward && !input.isBackward -> s.speed += if (s.speed < 0) 0.06 else 0.022
      input.isBackward && !input.isForward -> s.speed -= if (s.speed > 0) 0.06 else 0.015
      else -> { s.speed *= 0.97; if (abs(s.speed) < 0.01) s.speed = 0.0 }
    }
    if (input.isJump) s.speed *= 0.88
    if (s.speed > top) s.speed = max(top, s.speed - 0.04)
    if (s.speed < -0.25) s.speed = -0.25

    // Steering: turns faster the faster it goes, backwards when reversing.
    var yaw = loc.yaw
    val grip = min(1.0, abs(s.speed) / 0.25) * (if (s.speed < 0) -1 else 1)
    if (input.isLeft) yaw -= (5.0 * grip).toFloat()
    if (input.isRight) yaw += (5.0 * grip).toFloat()

    val rad = Math.toRadians(yaw.toDouble())
    val dx = -sin(rad)
    val dz = cos(rad)
    var nx = loc.x + dx * s.speed
    var nz = loc.z + dz * s.speed

    // Bump into walls: look at the corners on the side we're driving towards.
    if (s.speed != 0.0) {
      val sign = if (s.speed > 0) 1.0 else -1.0
      val hit = listOf(-0.7, 0.0, 0.7).any { side ->
        val px = nx + dx * 1.4 * sign + dz * side
        val pz = nz + dz * 1.4 * sign - dx * side
        solid(world, px, feetY + 0.1, pz)
      }
      if (hit) {
        if (abs(s.speed) > 0.3) {
          world.playSound(loc, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1f, 1.2f)
          world.spawnParticle(Particle.CRIT, loc.clone().add(dx * 1.5 * sign, 0.3, dz * 1.5 * sign), 15, 0.3, 0.3, 0.3, 0.2)
        }
        s.speed = -s.speed * 0.3
        nx = loc.x
        nz = loc.z
      }
    }

    // Fall off edges, land on the ground.
    if (!solid(world, nx, feetY - 0.05, nz)) {
      s.fall = max(s.fall - 0.06, -1.5)
      feetY += s.fall
      if (solid(world, nx, feetY, nz)) { feetY = floor(feetY) + 1; s.fall = 0.0 }
    } else s.fall = 0.0
    if (feetY < world.minHeight - 10) { player.leaveVehicle(); car.remove(); return }

    car.teleport(Location(world, nx, feetY + seatY, nz, yaw, 0f), TeleportFlag.EntityState.RETAIN_PASSENGERS)

    // Engine noise and smoke.
    val speedAbs = abs(s.speed)
    if (speedAbs > 0.02 && s.ticks % 3 == 0) {
      world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.25f, (0.5 + speedAbs * 1.2).toFloat())
    }
    if (input.isForward && s.ticks % 2 == 0) {
      world.spawnParticle(Particle.SMOKE, nx - dx * 1.6, feetY + 0.35, nz - dz * 1.6, 2, 0.1, 0.05, 0.1, 0.01)
    }
    if ((input.isLeft || input.isRight) && speedAbs > 0.6 && onTrack) {
      world.spawnParticle(Particle.CLOUD, nx - dx * 1.0, feetY + 0.1, nz - dz * 1.0, 1, 0.3, 0.0, 0.3, 0.01)
    }

    laps(player, s, world, nx, nz)

    val kmh = (speedAbs * 20 * 3.6).toInt()
    val time = if (s.lapStart > 0) "   Lap: %.1fs".format((System.currentTimeMillis() - s.lapStart) / 1000.0) else ""
    player.sendActionBar(Component.text("$kmh km/h$time", NamedTextColor.AQUA))
  }

  // ---------- Laps ----------

  private fun laps(player: Player, s: CarState, world: World, x: Double, z: Double) {
    val trackWorld = config.getString("track.world") ?: return
    if (world.name != trackWorld) return
    val cx = x - config.getInt("track.x")
    val cz = z - config.getInt("track.z")
    if (cx < 0) s.wentRound = true
    val last = s.lastZ
    s.lastZ = cz
    if (last.isNaN() || !(last < 0 && cz >= 0) || cx < inner || cx > outer) return

    val now = System.currentTimeMillis()
    if (s.lapStart == 0L) {
      s.lapStart = now
      s.wentRound = false
      player.showTitle(Title.title(Component.text("GO!", NamedTextColor.GREEN), Component.empty(),
        Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(300))))
      player.sendMessage(Component.text("Go! Lap timer started.", NamedTextColor.GREEN))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f)
      return
    }
    if (!s.wentRound) return
    val lap = (now - s.lapStart) / 1000.0
    s.lapStart = now
    s.wentRound = false
    val path = "best.${player.uniqueId}"
    val best = if (config.contains(path)) config.getDouble(path) else null
    val text = "%.2f".format(lap)
    if (best == null || lap < best) {
      config.set(path, lap)
      saveConfig()
      player.showTitle(Title.title(Component.text("${text}s", NamedTextColor.GOLD), Component.text("New best lap!", NamedTextColor.YELLOW),
        Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(400))))
      player.sendMessage(Component.text("Lap: ${text}s - New best lap!", NamedTextColor.GOLD))
      player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f)
    } else {
      player.sendMessage(Component.text("Lap: ${text}s  (best: ${"%.2f".format(best)}s)", NamedTextColor.AQUA))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)
    }
  }

  // ---------- Building the track ----------

  /** How far a spot is from the middle line of the oval (0 on the straights' axis). */
  private fun distance(cx: Double, cz: Double): Double =
    if (abs(cz) <= straight) abs(cx) else hypot(cx, abs(cz) - straight)

  /** How far along the track a spot is, so curbs and walls can be striped. */
  private fun along(cx: Double, cz: Double): Double = when {
    abs(cz) <= straight -> if (cx > 0) cz else -cz
    else -> {
      val ez = if (cz > 0) cz - straight else cz + straight
      10.0 * atan2(cx, abs(ez)) + if (cz > 0) 1000.0 else 2000.0
    }
  }

  private fun buildTrack(player: Player) {
    val world = player.world
    val px = player.location.blockX
    val pz = player.location.blockZ
    val y = player.location.blockY - 1
    // The player ends up in the middle of the right-hand straight, just behind the start line.
    val ox = px - 10
    val oz = pz + 3

    for (lx in -18..17) for (lz in -(straight + 18)..(straight + 17)) {
      val cx = lx + 0.5
      val cz = lz + 0.5
      val d = distance(cx, cz)
      if (d > 17.5) continue
      val x = ox + lx
      val z = oz + lz
      val stripe = (floor(along(cx, cz) / 2).toInt() and 1) == 0
      for (h in 1..7) world.getBlockAt(x, y + h, z).setType(Material.AIR, false)
      world.getBlockAt(x, y - 1, z).setType(Material.DIRT, false)
      val ground = when {
        d < inner -> Material.GRASS_BLOCK
        d < inner + 1 || d > outer - 1 && d <= outer -> if (stripe) Material.RED_CONCRETE else Material.WHITE_CONCRETE
        d <= outer -> if (cx > 0 && abs(cz) < straight && (lz == 0 || lz == 1))
          (if ((lx + lz) and 1 == 0) Material.WHITE_CONCRETE else Material.BLACK_CONCRETE) else Material.GRAY_CONCRETE
        d <= outer + 1 -> Material.STONE_BRICKS
        else -> Material.GRASS_BLOCK
      }
      world.getBlockAt(x, y, z).setType(ground, false)
      if (d > outer && d <= outer + 1) {
        world.getBlockAt(x, y + 1, z).setType(if (stripe) Material.RED_CONCRETE else Material.WHITE_CONCRETE, false)
      }
    }
    // A checkered gantry over the start line.
    for (h in 1..5) {
      world.getBlockAt(ox + 5, y + h, oz).setType(Material.WHITE_CONCRETE, false)
      world.getBlockAt(ox + 15, y + h, oz).setType(Material.WHITE_CONCRETE, false)
    }
    for (lx in 5..15) {
      world.getBlockAt(ox + lx, y + 6, oz).setType(if (lx and 1 == 0) Material.BLACK_CONCRETE else Material.WHITE_CONCRETE, false)
    }

    config.set("track.world", world.name)
    config.set("track.x", ox)
    config.set("track.y", y)
    config.set("track.z", oz)
    saveConfig()

    player.teleport(Location(world, px + 0.5, y + 1.0, pz + 0.5, 0f, 0f))
    player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
    player.sendMessage(Component.text("Racetrack built! Drive south over the checkered line to start a lap.", NamedTextColor.GREEN))
  }
}
