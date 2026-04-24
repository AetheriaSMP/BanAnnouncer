package me.tini.announcer.extension.impl.ultrapunishments;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import me.tini.announcer.PunishmentInfo;
import me.tini.announcer.plugin.bukkit.BanAnnouncerBukkit;
import me.tini.announcer.plugin.bukkit.BukkitPunishmentListener;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

public final class UltraPunishmentsListener extends BukkitPunishmentListener {

    public UltraPunishmentsListener(BanAnnouncerBukkit plugin) {
        super(plugin);
    }

    @Override
    public void register() {
        registerEvent("me.TechsCode.UltraPunishments.event.BanEvent", this::onBan);
        registerEvent("me.TechsCode.UltraPunishments.event.UnbanEvent", this::onUnban);
        registerEvent("me.TechsCode.UltraPunishments.event.KickEvent", this::onKick);
        registerEvent("me.TechsCode.UltraPunishments.event.ReportEvent", this::onReport);
        registerEvent("me.TechsCode.UltraPunishments.event.FreezeEvent", this::onFreeze);
        registerEvent("me.TechsCode.UltraPunishments.event.UnfreezeEvent", this::onUnfreeze);
    }

    @Override
    public void unregister() {
        HandlerList.unregisterAll((Listener) this);
    }

    private void onBan(Object event) {
        final PunishmentInfo punishment = new PunishmentInfo();
        punishment.setType(PunishmentInfo.Type.BAN);
        punishment.setPermanent(true);
        punishment.setReason(asString(invoke(event, "getReason")));
        punishment.setOperator(resolveNestedString(event, "getIssuer", "getName"));
        punishment.setPlayer(resolveNestedString(event, "getPlayer", "getName"));
        punishment.setPlayerId(asString(invoke(invoke(event, "getPlayer"), "getUniqueId")));

        handlePunishment(punishment);
    }

    private void onUnban(Object event) {
        final PunishmentInfo punishment = new PunishmentInfo();
        punishment.setType(PunishmentInfo.Type.UNBAN);
        punishment.setOperator(resolveNestedString(event, "getIssuer", "getName"));
        punishment.setPlayer(resolveNestedString(event, "getPlayer", "getName"));
        punishment.setPlayerId(asString(invoke(invoke(event, "getPlayer"), "getUniqueId")));

        handlePunishment(punishment);
    }

    private void onKick(Object event) {
        final PunishmentInfo punishment = new PunishmentInfo();
        punishment.setType(PunishmentInfo.Type.KICK);
        punishment.setReason(asString(invoke(event, "getReason")));
        punishment.setOperator(resolveNestedString(event, "getIssuer", "getName"));
        punishment.setPlayer(resolveNestedString(event, "getPlayer", "getName"));
        punishment.setPlayerId(asString(invoke(invoke(event, "getPlayer"), "getUniqueId")));

        handlePunishment(punishment);
    }

    private void onReport(Object event) {
        // Currently not handled
    }

    private void onFreeze(Object event) {
        // Currently not handled
    }

    private void onUnfreeze(Object event) {
        // Currently not handled
    }

    @SuppressWarnings("unchecked")
    private void registerEvent(String eventClassName, Consumer<Object> handler) {
        try {
            Class<?> eventClassRaw = Class.forName(eventClassName);
            if (!Event.class.isAssignableFrom(eventClassRaw)) {
                return;
            }

            Class<? extends Event> eventClass = (Class<? extends Event>) eventClassRaw;
            EventExecutor executor = new EventExecutor() {
                @Override
                public void execute(Listener listener, Event event) throws EventException {
                    handler.accept(event);
                }
            };

            getPlugin().getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.NORMAL, executor, getPlugin());
        } catch (ClassNotFoundException ignored) {
            // UltraPunishments is optional.
        }
    }

    private Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private String resolveNestedString(Object target, String firstMethod, String secondMethod) {
        return asString(invoke(invoke(target, firstMethod), secondMethod));
    }

    private String asString(Object value) {
        return value == null ? "Unknown" : String.valueOf(value);
    }
}
