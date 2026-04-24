package me.tini.command;

public interface ICommandSender {

    boolean hasPermission(String permission);

    void sendMessage(String message);
}
