package net.wishblock.addsethomeandhomecommands

import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
  override fun onEnable() {}


  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
    val player = sender as? Player ?: run {
      sender.sendMessage("Only players can have a home.")
      return true
    }
    val key = "homes.${player.uniqueId}"
    when (command.name.lowercase()) {
      "sethome" -> {
        config.set(key, player.location)
        saveConfig()
        player.sendMessage("§aHome set! Type §e/home §ato come back here.")
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f)
        player.world.spawnParticle(Particle.HAPPY_VILLAGER, player.location.add(0.0, 1.0, 0.0), 20, 0.5, 0.8, 0.5)
      }
      "home" -> {
        val home = config.getLocation(key)
        if (home == null || home.world == null) {
          player.sendMessage("§cYou don't have a home yet. Type §e/sethome §cfirst!")
          return true
        }
        player.world.spawnParticle(Particle.PORTAL, player.location.add(0.0, 1.0, 0.0), 60, 0.4, 0.8, 0.4)
        player.teleport(home)
        player.world.spawnParticle(Particle.PORTAL, home.clone().add(0.0, 1.0, 0.0), 60, 0.4, 0.8, 0.4)
        player.playSound(home, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)
        player.sendMessage("§aWelcome home!")
      }
    }
    return true
  }
}
