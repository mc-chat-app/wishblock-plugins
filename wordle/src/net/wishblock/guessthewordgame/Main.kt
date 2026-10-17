package net.wishblock.guessthewordgame

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import net.wishblock.lib.Textures
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

enum class Mark(val tile: String, val color: NamedTextColor) {
  GREEN("green", NamedTextColor.GREEN),
  YELLOW("yellow", NamedTextColor.YELLOW),
  GRAY("gray", NamedTextColor.DARK_GRAY),
}

class Game(val answer: String) {
  val guesses = mutableListOf<String>()
  val marks = mutableListOf<List<Mark>>()
  val won get() = guesses.lastOrNull() == answer
  val over get() = won || guesses.size >= 6

  fun guess(word: String) {
    val result = Array(5) { Mark.GRAY }
    val left = answer.groupingBy { it }.eachCount().toMutableMap()
    for (i in 0 until 5) if (word[i] == answer[i]) {
      result[i] = Mark.GREEN
      left[word[i]] = left[word[i]]!! - 1
    }
    for (i in 0 until 5) if (result[i] != Mark.GREEN && (left[word[i]] ?: 0) > 0) {
      result[i] = Mark.YELLOW
      left[word[i]] = left[word[i]]!! - 1
    }
    guesses += word
    marks += result.toList()
  }

  // The best thing known about each letter tried so far.
  fun letterMarks(): Map<Char, Mark> {
    val best = mutableMapOf<Char, Mark>()
    guesses.forEachIndexed { g, word ->
      word.forEachIndexed { i, c ->
        val m = marks[g][i]
        val old = best[c]
        if (old == null || m.ordinal < old.ordinal) best[c] = m
      }
    }
    return best
  }
}

class Board(val playerId: UUID) : InventoryHolder {
  lateinit var inv: Inventory
  override fun getInventory() = inv
}

class Main : JavaPlugin(), Listener {
  private lateinit var answers: List<String>
  private lateinit var valid: Set<String>
  private val games = mutableMapOf<UUID, Game>()
  private val typing = mutableSetOf<UUID>()

  private val guessSlot = 26
  private val newSlot = 44
  private val lettersSlot = 18

  override fun onEnable() {
    Textures.register(this)
    answers = read("answers.txt")
    valid = read("valid.txt").toSet() + answers
    server.pluginManager.registerEvents(this, this)
  }

  private fun read(name: String) =
    getResource(name)!!.bufferedReader().readLines().map { it.trim() }.filter { it.length == 5 }

  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
    val p = sender as? Player ?: run { sender.sendMessage("Only players can play Wordle."); return true }
    if (args.isNotEmpty()) submit(p, args[0]) else open(p)
    return true
  }

  private fun game(p: Player) = games.getOrPut(p.uniqueId) { Game(answers.random()) }

  private fun tile(model: String, name: Component, lore: List<Component> = emptyList()) =
    ItemStack(Material.PAPER).apply {
      editMeta {
        it.setItemModel(NamespacedKey(this@Main, model))
        it.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) it.lore(lore.map { l -> l.decoration(TextDecoration.ITALIC, false) })
      }
    }

  private fun button(mat: Material, name: String, color: NamedTextColor, vararg lore: String) =
    ItemStack(mat).apply {
      editMeta {
        it.displayName(Component.text(name, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
        it.lore(lore.map { l -> Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false) })
      }
    }

  private fun open(p: Player) {
    val g = game(p)
    val holder = Board(p.uniqueId)
    val inv = Bukkit.createInventory(holder, 54, Component.text("Wordle", NamedTextColor.DARK_GREEN, TextDecoration.BOLD))
    holder.inv = inv
    for (row in 0 until 6) for (col in 0 until 5) {
      val slot = row * 9 + col + 2
      inv.setItem(slot, if (row < g.guesses.size) {
        val c = g.guesses[row][col]
        val m = g.marks[row][col]
        tile("${c}_${m.tile}", Component.text(c.uppercaseChar().toString(), m.color, TextDecoration.BOLD))
      } else tile("empty", Component.text(" ")))
    }
    val known = g.letterMarks()
    val keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { row ->
      row.fold(Component.empty()) { acc, c ->
        val color = known[c]?.color ?: NamedTextColor.WHITE
        acc.append(Component.text(c.uppercaseChar() + " ", color, TextDecoration.BOLD))
      }
    }
    inv.setItem(lettersSlot, button(Material.BOOK, "Letters", NamedTextColor.AQUA).apply {
      editMeta { it.lore(keyRows.map { r -> r.decoration(TextDecoration.ITALIC, false) } + listOf(
        Component.text("Green: right spot", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
        Component.text("Yellow: in the word, wrong spot", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
        Component.text("Gray: not in the word", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))) }
    })
    if (g.over) {
      inv.setItem(guessSlot, if (g.won) button(Material.EMERALD, "You won!", NamedTextColor.GREEN, "in ${g.guesses.size} guesses")
        else button(Material.REDSTONE, "The word was ${g.answer.uppercase()}", NamedTextColor.RED))
    } else {
      inv.setItem(guessSlot, button(Material.WRITABLE_BOOK, "Make a guess", NamedTextColor.GREEN,
        "Click, then type a", "5-letter word in chat", "Guess ${g.guesses.size + 1} of 6"))
    }
    inv.setItem(newSlot, button(Material.CLOCK, "New word", NamedTextColor.GOLD, "Start a new game"))
    p.openInventory(inv)
  }

  private fun submit(p: Player, raw: String) {
    val word = raw.trim().lowercase()
    val g = game(p)
    if (g.over) { p.sendMessage(Component.text("This game is over! Open /wordle and click New word.", NamedTextColor.GOLD)); return }
    if (word.length != 5 || !word.all { it in 'a'..'z' }) {
      p.sendMessage(Component.text("Your guess must be 5 letters. Try again!", NamedTextColor.RED)); return
    }
    if (word !in valid) {
      p.sendMessage(Component.text("${word.uppercase()} isn't in the word list. Try again!", NamedTextColor.RED)); return
    }
    typing -= p.uniqueId
    g.guess(word)
    val line = g.marks.last().zip(word.toList()).fold(Component.empty()) { acc, (m, c) ->
      acc.append(Component.text("[${c.uppercaseChar()}]", m.color, TextDecoration.BOLD))
    }
    p.sendMessage(Component.text("Guess ${g.guesses.size}: ", NamedTextColor.WHITE).append(line))
    when {
      g.won -> {
        p.showTitle(Title.title(Component.text("You got it!", NamedTextColor.GREEN), Component.text(g.answer.uppercase() + " in ${g.guesses.size}", NamedTextColor.YELLOW)))
        p.playSound(p.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
      }
      g.over -> {
        p.showTitle(Title.title(Component.text("Out of guesses!", NamedTextColor.RED), Component.text("The word was " + g.answer.uppercase(), NamedTextColor.YELLOW)))
        p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
      }
      else -> p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f)
    }
    open(p)
  }

  @EventHandler
  fun onClick(e: InventoryClickEvent) {
    val holder = e.view.topInventory.holder as? Board ?: return
    e.isCancelled = true
    val p = e.whoClicked as? Player ?: return
    if (e.clickedInventory != e.view.topInventory) return
    when (e.slot) {
      guessSlot -> if (!game(p).over) {
        typing += p.uniqueId
        p.closeInventory()
        p.showTitle(Title.title(Component.text("Type your guess", NamedTextColor.GREEN), Component.text("a 5-letter word in chat", NamedTextColor.WHITE)))
        p.sendMessage(Component.text("Type a 5-letter word in chat! (or 'cancel')", NamedTextColor.GREEN))
      }
      newSlot -> {
        games[holder.playerId] = Game(answers.random())
        p.playSound(p.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
        open(p)
      }
    }
  }

  @EventHandler
  fun onDrag(e: InventoryDragEvent) {
    if (e.view.topInventory.holder is Board) e.isCancelled = true
  }

  @EventHandler
  fun onChat(e: AsyncChatEvent) {
    val p = e.player
    if (p.uniqueId !in typing) return
    e.isCancelled = true
    val text = PlainTextComponentSerializer.plainText().serialize(e.message())
    Bukkit.getScheduler().runTask(this, Runnable {
      if (text.trim().equals("cancel", true)) { typing -= p.uniqueId; open(p) } else submit(p, text)
    })
  }

  @EventHandler
  fun onQuit(e: PlayerQuitEvent) { typing -= e.player.uniqueId }
}
