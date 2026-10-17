package net.wishblock.openyourenderchestanywhere

import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
  override fun onEnable() {}

  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
    if (sender !is Player) {
      sender.sendMessage("Only players have an ender chest!")
      return true
    }
    sender.openInventory(sender.enderChest)
    sender.playSound(sender.location, Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f)
    return true
  }
}
