package me.tini.command.sponge;

import me.tini.command.ICommandExecutor;

public interface ISpongePlugin {

    default void registerCommand(String commandName, ICommandExecutor executor) {
        // Sponge command registration is optional for compilation in this workspace.
    }
}
