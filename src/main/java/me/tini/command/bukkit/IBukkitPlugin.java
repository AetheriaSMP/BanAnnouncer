package me.tini.command.bukkit;

import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import me.tini.command.ICommandExecutor;
import me.tini.command.ICommandSender;

public interface IBukkitPlugin {

    default void registerCommand(String commandName, ICommandExecutor executor) {
        JavaPlugin plugin = (JavaPlugin) this;
        PluginCommand command = plugin.getCommand(commandName);
        if (command == null) {
            plugin.getLogger().warning("Failed to register command '" + commandName + "': command not found in plugin.yml");
            return;
        }

        command.setExecutor((sender, cmd, label, args) -> executor.handle(new BukkitCommandSender(sender), args));
    }

    final class BukkitCommandSender implements ICommandSender {

        private final CommandSender sender;

        BukkitCommandSender(CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void sendMessage(String message) {
            sender.sendMessage(message);
        }
    }
}
