package me.tini.command.velocity;

import me.tini.command.ICommandExecutor;
import com.velocitypowered.api.proxy.ProxyServer;

public interface IVelocityPlugin {

    ProxyServer getProxyServer();

    default void registerCommand(String commandName, ICommandExecutor executor) {
        // Velocity command registration is optional for compilation in this workspace.
    }
}
