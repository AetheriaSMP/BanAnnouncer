package me.tini.announcer.extension.impl.betterjails;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.function.Consumer;

import me.tini.announcer.PunishmentInfo;
import me.tini.announcer.PunishmentInfo.Type;
import me.tini.announcer.plugin.bukkit.BanAnnouncerBukkit;
import me.tini.announcer.plugin.bukkit.BukkitPunishmentListener;

public class BetterJailsListener extends BukkitPunishmentListener {

    private final Object betterJails;

    public BetterJailsListener(BanAnnouncerBukkit plugin) {
        super(plugin);
        Object loaded = null;
        try {
            Class<?> serviceClass = Class.forName("com.github.fefo.betterjails.api.BetterJails");
            loaded = plugin.getServer().getServicesManager().load(serviceClass);
        } catch (ClassNotFoundException ignored) {
            // BetterJails is optional.
        }
        this.betterJails = loaded;
    }

    @Override
    public void register() {
        if (betterJails == null) {
            return;
        }

        subscribe("com.github.fefo.betterjails.api.event.prisoner.PlayerImprisonEvent", this::onPlayerImprison);
        subscribe("com.github.fefo.betterjails.api.event.prisoner.PrisonerReleaseEvent", this::onPrisonerRelease);
    }

    @Override
    public void unregister() {
        if (betterJails == null) {
            return;
        }

        try {
            Object eventBus = invoke(betterJails, "getEventBus");
            Method unsubscribe = eventBus.getClass().getMethod("unsubscribe", Object.class);
            unsubscribe.invoke(eventBus, getPlugin());
        } catch (ReflectiveOperationException exception) {
            getPlugin().getLogger().warning("Failed to unregister BetterJails listeners: " + exception.getMessage());
        }
    }

    private void onPlayerImprison(Object event) {
        handle(resolvePrisoner(event), false);
    }

    private void onPrisonerRelease(Object event) {
        handle(resolvePrisoner(event), true);
    }

    private void handle(Object prisoner, boolean released) {
        if (prisoner == null) {
            return;
        }

        String jail = asString(invoke(invoke(prisoner, "jail"), "name"));
        String player = asString(invoke(prisoner, "name"));
        String operator = asString(invoke(prisoner, "jailedBy"));

        PunishmentInfo punishment = new PunishmentInfo(released ? Type.UNJAIL : Type.JAIL);

        Object jailedUntil = invoke(prisoner, "jailedUntil");
        long jailedUntilMillis = jailedUntil instanceof Instant ? ((Instant) jailedUntil).toEpochMilli() : System.currentTimeMillis();
        long durationMillis = jailedUntilMillis - System.currentTimeMillis();

        punishment.setJail(jail);
        punishment.setPlayer(player);
        punishment.setOperator(operator);
        punishment.setDuration(TimeUtils.parseMillis(durationMillis));

        if (released && durationMillis <= 0) {
            punishment.setOperator(getPlugin().getAnnouncer().getConfig().getExpiredOperatorName());
        }

        handlePunishment(punishment);
    }

    @SuppressWarnings("unchecked")
    private void subscribe(String eventClassName, Consumer<Object> listener) {
        try {
            Object eventBus = invoke(betterJails, "getEventBus");
            Class<?> eventClass = Class.forName(eventClassName);
            Method subscribe = eventBus.getClass().getMethod("subscribe", Object.class, Class.class, Consumer.class);
            subscribe.invoke(eventBus, getPlugin(), eventClass, listener);
        } catch (ReflectiveOperationException exception) {
            getPlugin().getLogger().warning("Failed to subscribe BetterJails event '" + eventClassName + "': " + exception.getMessage());
        }
    }

    private Object resolvePrisoner(Object event) {
        if (event == null) {
            return null;
        }
        return invoke(event, "prisoner");
    }

    private Object invoke(Object target, String method) {
        if (target == null) {
            return null;
        }

        try {
            Method methodRef = target.getClass().getMethod(method);
            return methodRef.invoke(target);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? "Unknown" : String.valueOf(value);
    }
}
