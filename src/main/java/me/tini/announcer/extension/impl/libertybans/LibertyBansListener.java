package me.tini.announcer.extension.impl.libertybans;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.tini.announcer.BanAnnouncerPlugin;
import me.tini.announcer.PunishmentInfo;
import me.tini.announcer.PunishmentListener;
import space.arim.libertybans.api.AddressVictim;
import space.arim.libertybans.api.CompositeVictim;
import space.arim.libertybans.api.LibertyBans;
import space.arim.libertybans.api.Operator;
import space.arim.libertybans.api.Operator.OperatorType;
import space.arim.libertybans.api.PlayerOperator;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.Victim;
import space.arim.libertybans.api.Victim.VictimType;
import space.arim.libertybans.api.event.PostPardonEvent;
import space.arim.libertybans.api.event.PostPunishEvent;
import space.arim.libertybans.api.punish.Punishment;
import space.arim.omnibus.Omnibus;
import space.arim.omnibus.OmnibusProvider;
import space.arim.omnibus.events.EventBus;
import space.arim.omnibus.events.RegisteredListener;
import space.arim.omnibus.util.concurrent.CentralisedFuture;

public class LibertyBansListener extends PunishmentListener {

    private final LibertyBans libertyBans = findLibertyBansInstance();
    private final EventBus eventBus;
    private final Map<Long, TrackedTempBan> trackedTempBans = new ConcurrentHashMap<>();

    private RegisteredListener postPunishListenerMarker;
    private RegisteredListener postPardonListenerMarker;
    private ScheduledExecutorService expirationWatcher;

    public LibertyBansListener(BanAnnouncerPlugin plugin) {
        super(plugin.getAnnouncer());
        eventBus = libertyBans.getOmnibus().getEventBus();
    }

    @Override
    public void register() {
        if (isRegistered()) {
            throw new IllegalStateException("call unregister() first");
        }
        postPunishListenerMarker = eventBus.registerListener(PostPunishEvent.class, (byte) 0, this::onPostPunishEvent);
        postPardonListenerMarker = eventBus.registerListener(PostPardonEvent.class, (byte) 0, this::onPostPardonEvent);
        startExpirationWatcher();
        loadActiveTempBans();
    }

    @Override
    public void unregister() {
        if (isRegistered()) {
            eventBus.unregisterListener(postPunishListenerMarker);
            eventBus.unregisterListener(postPardonListenerMarker);
        }
        trackedTempBans.clear();
        stopExpirationWatcher();
        postPunishListenerMarker = null;
        postPardonListenerMarker = null;
    }

    private boolean isRegistered() {
        return postPunishListenerMarker != null || postPardonListenerMarker != null;
    }

    private void onPostPunishEvent(PostPunishEvent event) {
        Punishment punishment = event.getPunishment();
        handle(punishment.getOperator(), punishment, false);
        trackTempBan(punishment);
    }

    private void onPostPardonEvent(PostPardonEvent event) {
        trackedTempBans.remove(event.getPunishment().getIdentifier());
        handle(event.getOperator(), event.getPunishment(), true);
    }

    private void handle(Operator operator, Punishment pun, boolean isRevoked) {
        PunishmentInfo punishment = new PunishmentInfo();

        boolean isConsole = operator.getType() == OperatorType.CONSOLE;

        String operatorName = isConsole
                ? getAnnouncer().getConfig().getConsoleName()
                : getOperatorName(operator);

        punishment.setId(Long.toString(pun.getIdentifier()));
        punishment.setReason(pun.getReason());
        punishment.setPermanent(pun.isPermanent());

        if (punishment.isPermanent()) {
            punishment.setDuration("permanent");
        } else {
            long durationSeconds = Math.max(0L, pun.getEndDateSeconds() - pun.getStartDateSeconds());
            punishment.setDuration(libertyBans.getFormatter().formatDuration(Duration.ofSeconds(durationSeconds)));
        }

        if (isRevoked && pun.isExpired()) {
            punishment.setOperator(getAnnouncer().getConfig().getExpiredOperatorName());
        } else {
            punishment.setOperator(operatorName);
        }

        punishment.setPlayer(getVictimName(pun.getVictim()));

        punishment.setPlayerId(getVictimId(pun.getVictim()));

        switch (pun.getType()) {
        case BAN:
            boolean isBanIP = pun.getVictim().getType() == VictimType.ADDRESS;

            if (isBanIP) {
                if (isRevoked) {
                    punishment.setType(PunishmentInfo.Type.UNBANIP);
                } else {
                    punishment.setType(punishment.isPermanent() ? PunishmentInfo.Type.BANIP : PunishmentInfo.Type.TEMPBANIP);
                }
            } else {
                if (isRevoked) {
                    punishment.setType(PunishmentInfo.Type.UNBAN);
                } else {
                    punishment.setType(punishment.isPermanent() ? PunishmentInfo.Type.BAN : PunishmentInfo.Type.TEMPBAN);
                }
            }

            break;
        case KICK:
            punishment.setType(PunishmentInfo.Type.KICK);
            break;
        case MUTE:
            if (isRevoked) {
                punishment.setType(PunishmentInfo.Type.UNMUTE);
            } else {
                punishment.setType(punishment.isPermanent() ? PunishmentInfo.Type.MUTE : PunishmentInfo.Type.TEMPMUTE);
            }
            break;
        case WARN:
            if (isRevoked) {
                punishment.setType(PunishmentInfo.Type.UNWARN);
            } else {
                punishment.setType(punishment.isPermanent() ? PunishmentInfo.Type.WARN : PunishmentInfo.Type.TEMPWARN);
            }
            break;
        default:
            break;
        }

        if (!punishment.isPermanent()) {
        	punishment.setStart(pun.getStartDateSeconds() * 1000);
        	punishment.setEnd(pun.getEndDateSeconds() * 1000);
        }

        handlePunishment(punishment);
    }

    private void startExpirationWatcher() {
        if (expirationWatcher != null) {
            return;
        }
        expirationWatcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BanAnnouncer-LibertyBans-ExpiryWatcher");
            thread.setDaemon(true);
            return thread;
        });
        expirationWatcher.scheduleAtFixedRate(this::notifyExpiredTempBans, 10L, 10L, TimeUnit.SECONDS);
    }

    private void loadActiveTempBans() {
        libertyBans.getSelector().selectionBuilder()
                .type(PunishmentType.BAN)
                .selectActiveOnly()
                .build()
                .getAllSpecificPunishments()
                .toCompletableFuture()
                .whenComplete((punishments, throwable) -> {
                    if (!isRegistered()) {
                        return;
                    }
                    if (throwable != null) {
                        getAnnouncer().getLogger().warning("Failed to load active LibertyBans tempbans for expiry tracking.");
                        throwable.printStackTrace();
                        return;
                    }
                    for (Punishment punishment : punishments) {
                        trackTempBan(punishment);
                    }
                });
    }

    private void stopExpirationWatcher() {
        if (expirationWatcher != null) {
            expirationWatcher.shutdownNow();
            expirationWatcher = null;
        }
    }

    private void trackTempBan(Punishment punishment) {
        if (punishment.getType() != PunishmentType.BAN || punishment.isPermanent()) {
            return;
        }

        long durationSeconds = Math.max(0L, punishment.getEndDateSeconds() - punishment.getStartDateSeconds());

        TrackedTempBan tracked = new TrackedTempBan(
                punishment.getIdentifier(),
                punishment.getVictim().getType() == VictimType.ADDRESS,
                getVictimName(punishment.getVictim()),
                getVictimId(punishment.getVictim()),
                punishment.getReason(),
                libertyBans.getFormatter().formatDuration(Duration.ofSeconds(durationSeconds)),
                punishment.getStartDateSeconds(),
                punishment.getEndDateSeconds());

        trackedTempBans.put(tracked.punishmentId, tracked);
    }

    private void notifyExpiredTempBans() {
        try {
            long nowEpochSeconds = System.currentTimeMillis() / 1000L;

            for (TrackedTempBan tracked : trackedTempBans.values()) {
                if (tracked.endDateSeconds > nowEpochSeconds) {
                    continue;
                }

                if (!trackedTempBans.remove(tracked.punishmentId, tracked)) {
                    continue;
                }

                handlePunishment(buildExpiredTempBanEnding(tracked));
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private PunishmentInfo buildExpiredTempBanEnding(TrackedTempBan tracked) {
        PunishmentInfo punishment = new PunishmentInfo();

        punishment.setId(Long.toString(tracked.punishmentId));
        punishment.setType(tracked.isBanIp ? PunishmentInfo.Type.UNBANIP : PunishmentInfo.Type.UNBAN);
        punishment.setOperator(getAnnouncer().getConfig().getExpiredOperatorName());
        punishment.setPlayer(tracked.player);
        punishment.setPlayerId(tracked.playerId);
        punishment.setReason(tracked.reason);
        punishment.setPermanent(false);
        punishment.setDuration(tracked.duration);
        punishment.setStart(tracked.startDateSeconds * 1000L);
        punishment.setEnd(tracked.endDateSeconds * 1000L);

        return punishment;
    }

    private String getVictimName(Victim victim) {
        if (victim.getType() == VictimType.PLAYER) {
            return getPlayerName(((PlayerVictim) victim).getUUID());
        }
        if (victim.getType() == VictimType.ADDRESS) {
            return ((AddressVictim) victim).getAddress().toInetAddress().getHostAddress();
        }
        if (victim.getType() == VictimType.COMPOSITE) {
            return getPlayerName(((CompositeVictim) victim).getUUID());
        }
        return "<Unknown>";
    }

    private String getVictimId(Victim victim) {
        if (victim.getType() == VictimType.PLAYER) {
            return ((PlayerVictim) victim).getUUID().toString();
        }
        if (victim.getType() == VictimType.COMPOSITE) {
            return ((CompositeVictim) victim).getUUID().toString();
        }
        return new UUID(0, 0).toString();
    }

    private String getOperatorName(Operator operator) {
        return (operator.getType() == OperatorType.CONSOLE)
               ? getAnnouncer().getConfig().getConsoleName()
               : getPlayerName(((PlayerOperator) operator).getUUID());
    }

    private String getPlayerName(UUID uuid) {
        CentralisedFuture<Optional<String>> lookup = libertyBans.getUserResolver().lookupName(uuid);

        try {
            Optional<String> optional = lookup.get();
            if (optional.isPresent()) {
                return optional.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        return "<Unknown>";
    }

    private static LibertyBans findLibertyBansInstance() {
         Omnibus omnibus = OmnibusProvider.getOmnibus();
         Optional<LibertyBans> instance = omnibus.getRegistry().getProvider(LibertyBans.class);
         if (!instance.isPresent()) {
             throw new IllegalStateException("LibertyBans not found");
         }
         return instance.get();
    }

    private static final class TrackedTempBan {
        private final long punishmentId;
        private final boolean isBanIp;
        private final String player;
        private final String playerId;
        private final String reason;
        private final String duration;
        private final long startDateSeconds;
        private final long endDateSeconds;

        private TrackedTempBan(long punishmentId, boolean isBanIp, String player, String playerId,
                String reason, String duration, long startDateSeconds, long endDateSeconds) {
            this.punishmentId = punishmentId;
            this.isBanIp = isBanIp;
            this.player = player;
            this.playerId = playerId;
            this.reason = reason;
            this.duration = duration;
            this.startDateSeconds = startDateSeconds;
            this.endDateSeconds = endDateSeconds;
        }
    }
}
