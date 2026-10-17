package net.wishblock.lookatotherplayersinventory

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

// The window: rows 1-3 are the backpack, row 4 the hotbar, row 5 armour and off hand.
class InvseeView(val target: UUID) : InventoryHolder {
  lateinit var inv: Inventory
  var dirty = false
  override fun getInventory() = inv
}

class Main : JavaPlugin(), Listener {
  // Window slot -> player inventory slot (36-39 armour boots..helmet, 40 off hand).
  private val slotMap = HashMap<Int, Int>().apply {
    for (i in 9..35) put(i - 9, i)
    for (i in 0..8) put(27 + i, i)
    put(36, 39); put(37, 38); put(38, 37); put(39, 36); put(40, 40)
  }

  override fun onEnable() {
    server.pluginManager.registerEvents(this, this)
    server.scheduler.runTaskTimer(this, Runnable { refreshAll() }, 5L, 5L)
  }

  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
    val viewer = sender as? Player ?: run { sender.sendMessage("Only players can use this."); return true }
    if (args.isEmpty()) return false
    val target = Bukkit.getPlayer(args[0])
    if (target == null) {
      viewer.sendMessage(Component.text("${args[0]} isn't online right now.", NamedTextColor.RED))
      return true
    }
    val holder = InvseeView(target.uniqueId)
    val inv = Bukkit.createInventory(holder, 54, Component.text("${target.name}'s inventory"))
    holder.inv = inv
    val filler = label(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (i in 41..53) inv.setItem(i, filler)
    inv.setItem(45, label(Material.IRON_HELMET, "Armour: helmet, chest, legs, boots"))
    inv.setItem(46, label(Material.SHIELD, "Off hand is the 5th slot above"))
    copyIn(holder, target)
    viewer.openInventory(inv)
    viewer.sendMessage(Component.text("Looking at ${target.name}'s inventory.", NamedTextColor.GREEN))
    return true
  }

  override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>) =
    if (args.size == 1) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], true) } else emptyList()

  private fun label(mat: Material, name: String) = ItemStack(mat).apply {
    editMeta { it.displayName(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)) }
  }

  private fun copyIn(h: InvseeView, target: Player) {
    for ((w, p) in slotMap) h.inv.setItem(w, target.inventory.getItem(p))
  }

  private fun copyOut(h: InvseeView) {
    val target = Bukkit.getPlayer(h.target) ?: return
    for ((w, p) in slotMap) target.inventory.setItem(p, h.inv.getItem(w))
  }

  private fun refreshAll() {
    for (p in Bukkit.getOnlinePlayers()) {
      val h = p.openInventory.topInventory.holder as? InvseeView ?: continue
      if (h.dirty) continue
      val target = Bukkit.getPlayer(h.target)
      if (target == null) { p.closeInventory(); continue }
      copyIn(h, target)
    }
  }

  // After the viewer moves something, write the window back into the real inventory next tick.
  private fun changed(h: InvseeView) {
    h.dirty = true
    server.scheduler.runTask(this, Runnable { copyOut(h); h.dirty = false })
  }

  @EventHandler
  fun onClick(e: InventoryClickEvent) {
    val h = e.view.topInventory.holder as? InvseeView ?: return
    if (e.clickedInventory == e.view.topInventory && e.slot !in slotMap) { e.isCancelled = true; return }
    changed(h)
  }

  @EventHandler
  fun onDrag(e: InventoryDragEvent) {
    val h = e.view.topInventory.holder as? InvseeView ?: return
    if (e.rawSlots.any { it < 54 && it !in slotMap }) { e.isCancelled = true; return }
    changed(h)
  }

  @EventHandler
  fun onClose(e: InventoryCloseEvent) {
    val h = e.inventory.holder as? InvseeView ?: return
    if (h.dirty) { copyOut(h); h.dirty = false }
  }
}
