package me.tini.command;

public interface ICommandExecutor {

    boolean handle(ICommandSender sender, String[] args);
}
