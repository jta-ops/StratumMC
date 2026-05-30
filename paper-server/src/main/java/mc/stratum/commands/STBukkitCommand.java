package mc.stratum.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * Thin {@link Command} wrapper so {@link StratumCommand} can be registered
 * directly with {@link org.bukkit.command.CommandMap} without a plugin.yml entry.
 */
public final class STBukkitCommand extends Command {

    private final CommandExecutor executor;
    private final TabCompleter    completer;

    public STBukkitCommand(final String name, final StratumCommand handler) {
        super(name);
        this.executor  = handler;
        this.completer = handler;

        setDescription("Stratum MC administrative command");
        setUsage("/ST [subcommand]");
        setPermission("stratum.use");
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        return executor.onCommand(sender, this, commandLabel, args);
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        final List<String> result = completer.onTabComplete(sender, this, alias, args);
        return result != null ? result : List.of();
    }
}
