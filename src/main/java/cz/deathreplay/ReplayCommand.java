package cz.deathreplay;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ReplayCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS =
            List.of("list", "play", "pause", "speed", "seek", "cam", "restart", "stop", "delete", "reload");
    // Time of death shown in /replay list (server time zone). Use ZoneId.of("Europe/Prague") to force one.
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DeathReplayPlugin plugin;
    private final ReplayStore store;

    public ReplayCommand(DeathReplayPlugin plugin, ReplayStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(DeathReplayPlugin.PERM)) {
            msg(sender, "You don't have permission to use death replays.", NamedTextColor.RED);
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender, args.length > 1 ? args[1] : null);
            case "play" -> play(sender, args);
            case "delete" -> delete(sender, args);
            case "reload" -> reload(sender);
            case "pause", "speed", "seek", "cam", "restart", "stop" -> control(sender, sub, args);
            default -> help(sender);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("deathreplay.reload")) {
            msg(sender, "You don't have permission to reload DeathReplay.", NamedTextColor.RED);
            return;
        }
        plugin.reload();
        msg(sender, "DeathReplay reloaded (config + " + plugin.replayCount() + " saved replays).", NamedTextColor.GREEN);
    }

    private void list(CommandSender sender, String victim) {
        List<Model.Replay> list = store.recent(8, victim);
        if (list.isEmpty()) {
            msg(sender, "No saved replays.", NamedTextColor.GRAY);
            return;
        }
        msg(sender, "Recent deaths:", NamedTextColor.GOLD);
        for (Model.Replay r : list) {
            Component line = Component.text(r.victimName(), NamedTextColor.YELLOW)
                    .append(Component.text(" \u2190 " + r.killerName() + " ", NamedTextColor.GRAY))
                    .append(Component.text("[" + TIME.format(Instant.ofEpochMilli(r.time())) + ", "
                            + ago(r.time()) + "] ", NamedTextColor.DARK_GRAY));
            if (sender instanceof Player) {
                line = line.append(Component.text("[\u25b6]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/replay play " + r.id()))
                        .hoverEvent(HoverEvent.showText(Component.text("Play replay #" + r.id()))));
            }
            sender.sendMessage(line);
        }
    }

    private void play(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "Only players can play replays in-game.", NamedTextColor.RED);
            return;
        }
        Integer id = args.length > 1 ? parseInt(args[1]) : null;
        if (id == null) {
            msg(sender, "Usage: /replay play <id>   (find the id with /replay list)", NamedTextColor.RED);
            return;
        }
        Model.Replay r = store.get(id);
        if (r == null) {
            msg(sender, "Replay #" + id + " does not exist.", NamedTextColor.RED);
            return;
        }
        World w = Bukkit.getWorld(r.world());
        if (w == null) {
            msg(sender, "World '" + r.world() + "' is not loaded.", NamedTextColor.RED);
            return;
        }
        try {
            plugin.start(p, r);
        } catch (RuntimeException ex) {
            msg(sender, "Could not start the replay: " + ex.getMessage(), NamedTextColor.RED);
        }
    }

    private void delete(CommandSender sender, String[] args) {
        Integer id = args.length > 1 ? parseInt(args[1]) : null;
        if (id == null) {
            msg(sender, "Usage: /replay delete <id>", NamedTextColor.RED);
            return;
        }
        if (store.delete(id)) {
            msg(sender, "Replay #" + id + " deleted.", NamedTextColor.GREEN);
        } else {
            msg(sender, "Replay #" + id + " does not exist.", NamedTextColor.RED);
        }
    }

    private void control(CommandSender sender, String sub, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "This can only be used in-game.", NamedTextColor.RED);
            return;
        }
        ReplaySession s = plugin.session(p);
        if (s == null) {
            msg(sender, "You are not playing a replay. Use /replay list.", NamedTextColor.RED);
            return;
        }
        switch (sub) {
            case "pause" -> msg(sender, s.togglePause() ? "Paused." : "Resumed.", NamedTextColor.GRAY);
            case "restart" -> s.restart();
            case "stop" -> {
                s.stop();
                msg(sender, "Replay ended.", NamedTextColor.GRAY);
            }
            case "speed" -> {
                Double v = args.length > 1 ? parseDouble(args[1]) : null;
                if (v == null) {
                    msg(sender, "Usage: /replay speed <0.1 - 4>", NamedTextColor.RED);
                } else {
                    s.setSpeed(v);
                }
            }
            case "seek" -> {
                Double v = args.length > 1 ? parseDouble(args[1]) : null;
                if (v == null) {
                    msg(sender, "Usage: /replay seek <seconds>  (negative = backwards)", NamedTextColor.RED);
                } else {
                    s.seek(v);
                }
            }
            case "cam" -> {
                String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (!s.camera(mode)) {
                    msg(sender, "Usage: /replay cam <victim|killer|free>  (killer only if a player/mob made the kill)",
                            NamedTextColor.RED);
                }
            }
            default -> help(sender);
        }
    }

    private void help(CommandSender sender) {
        msg(sender, "/replay list [player] | play <id> | pause | speed <x> | seek <s> | cam <victim|killer|free> | restart | stop | delete <id> | reload",
                NamedTextColor.GRAY);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission(DeathReplayPlugin.PERM)) {
            return List.of();
        }
        if (args.length == 1) {
            return match(SUBS, args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "cam":
                    return match(List.of("victim", "killer", "free"), args[1]);
                case "speed":
                    return match(List.of("0.25", "0.5", "1", "2"), args[1]);
                case "seek":
                    return match(List.of("-5", "-2", "2", "5"), args[1]);
                case "play":
                case "delete": {
                    ArrayList<String> ids = new ArrayList<>();
                    for (Model.Replay r : store.recent(10, null)) {
                        ids.add(String.valueOf(r.id()));
                    }
                    return match(ids, args[1]);
                }
                case "list": {
                    ArrayList<String> names = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        names.add(p.getName());
                    }
                    return match(names, args[1]);
                }
                default:
                    return List.of();
            }
        }
        return List.of();
    }

    private static List<String> match(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        ArrayList<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    private static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.replace("#", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String ago(long time) {
        long sec = Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
        if (sec < 60L) {
            return sec + " s ago";
        }
        if (sec < 3600L) {
            return sec / 60L + " min ago";
        }
        if (sec < 86400L) {
            return sec / 3600L + " h ago";
        }
        return sec / 86400L + " d ago";
    }
}
