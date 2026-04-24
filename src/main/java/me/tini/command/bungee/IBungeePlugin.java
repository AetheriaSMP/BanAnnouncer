package me.tini.command.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

import me.tini.command.ICommandExecutor;
import me.tini.command.ICommandSender;

public interface IBungeePlugin {

    default void registerCommand(String commandName, ICommandExecutor executor) {
        Plugin plugin = (Plugin) this;
        plugin.getProxy().getPluginManager().registerCommand(plugin, new Command(commandName) {

            @Override
            public void execute(CommandSender sender, String[] args) {
                executor.handle(new BungeeCommandSender(sender), args);
            }
        });
    }

    final class BungeeCommandSender implements ICommandSender {

        private final CommandSender sender;

        BungeeCommandSender(CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void sendMessage(String message) {
            sender.sendMessage(TextComponent.fromLegacyText(message));
        }
    }
}
