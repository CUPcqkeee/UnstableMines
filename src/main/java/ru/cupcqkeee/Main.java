package ru.cupcqkeee;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener, CommandExecutor {
    private static final int RISK_DECAY_INTERVAL_TICKS = 1_200;
    private static final long WARNING_ACTIONBAR_COOLDOWN_MILLIS = 2_000;
    private static final List<String> MESSAGE_KEYS = List.of(
            "warning",
            "collapse",
            "reinforced",
            "reload",
            "no-permission",
            "reload-success",
            "reload-failed"
    );

    private final Map<BlockPosition, RiskState> risks = new HashMap<>();
    private final Map<UUID, Long> lastWarningActionbar = new HashMap<>();
    private final Random random = new Random();
    private Settings settings;
    private YamlConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages_ru.yml", false);
        saveResource("messages_en.yml", false);

        try {
            reloadSettings();
        } catch (IllegalArgumentException | IOException exception) {
            getLogger().severe("Не удалось загрузить плагин: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("unstablemines") == null) {
            getLogger().severe("Команда не найдена");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("unstablemines").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::decayRisks,
                RISK_DECAY_INTERVAL_TICKS, RISK_DECAY_INTERVAL_TICKS);
    }

    @Override
    public void onDisable() {
        risks.clear();
        lastWarningActionbar.clear();
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (isRock(block.getType()) && isUnderground(block.getLocation())) {
            MinedBlock minedBlock = new MinedBlock(BlockPosition.from(block), block.getBlockData().clone(),
                    event.getPlayer().getUniqueId());
            increaseNearbyRisk(block.getLocation(), settings.miningIncrease(), minedBlock);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (isWoodenSupport(block.getType())) {
            reinforceNearbyTunnels(block.getLocation(), event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        destabilizeNearExplosion(event.getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        destabilizeNearExplosion(event.getBlock().getLocation());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastWarningActionbar.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sendMessage(sender, "reload");
            return true;
        }
        if (!sender.hasPermission("unstablemines.admin")) {
            sendMessage(sender, "no-permission");
            return true;
        }

        try {
            reloadConfig();
            reloadSettings();
            sendMessage(sender, "reload-success");
        } catch (IllegalArgumentException | IOException exception) {
            getLogger().severe("Не удалось перезагрузить конфигурацию плагина: " + exception.getMessage());
            sendMessage(sender, "reload-failed");
        }
        return true;
    }

    private void reloadSettings() throws IOException {
        String configuredLanguage = getConfig().getString("language", "ru");
        if (configuredLanguage == null) {
            throw new IllegalArgumentException("Язык может быть 'ru' или 'en'");
        }
        String language = configuredLanguage.toLowerCase(Locale.ROOT);
        if (!language.equals("ru") && !language.equals("en")) {
            throw new IllegalArgumentException("Язык может быть 'ru' или 'en'");
        }

        String pluginName = getConfig().getString("plugin-name");
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("Плейсхолдер названия плагина не установлен");
        }

        double miningIncrease = getConfig().getDouble("risk.mining-increase");
        double explosionIncrease = getConfig().getDouble("risk.explosion-increase");
        double warningThreshold = getConfig().getDouble("risk.warning-threshold");
        double collapseThreshold = getConfig().getDouble("risk.collapse-threshold");
        double collapseChance = getConfig().getDouble("risk.collapse-chance");
        double riskDecay = getConfig().getDouble("risk.decay-per-minute");
        double supportRelief = getConfig().getDouble("risk.support-relief");
        int scanRadius = getConfig().getInt("risk.scan-radius");
        int maximumTunnelY = getConfig().getInt("risk.maximum-tunnel-y");
        int collapseDelayTicks = getConfig().getInt("risk.collapse-delay-ticks");
        int maximumCollapseBlocks = getConfig().getInt("collapse.maximum-mined-blocks", 32);
        int maximumTrackedBlocksPerSection = getConfig().getInt("collapse.maximum-tracked-blocks-per-section", 128);
        int playerNoticeRadius = getConfig().getInt("risk.player-notice-radius");
        String collapseModeName = getConfig().getString("collapse.mode", "RESTORE_MINED_BLOCKS");

        if (collapseModeName == null || collapseModeName.isBlank()) {
            throw new IllegalArgumentException("collapse.mode не может быть пустым");
        }
        CollapseMode collapseMode;
        try {
            collapseMode = CollapseMode.valueOf(collapseModeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "collapse.mode должен быть RESTORE_MINED_BLOCKS или FALL_ON_PLAYER", exception);
        }

        if (!Double.isFinite(miningIncrease) || !Double.isFinite(explosionIncrease)
                || !Double.isFinite(warningThreshold) || !Double.isFinite(collapseThreshold)
                || !Double.isFinite(collapseChance) || !Double.isFinite(riskDecay)
                || !Double.isFinite(supportRelief)
                || miningIncrease <= 0 || explosionIncrease <= 0 || warningThreshold <= 0
                || collapseThreshold < warningThreshold || collapseChance < 0 || collapseChance > 100
                || riskDecay < 0 || supportRelief < 0 || scanRadius < 1 || scanRadius > 8
                || collapseDelayTicks < 1 || maximumCollapseBlocks < 1 || maximumCollapseBlocks > 64
                || maximumTrackedBlocksPerSection < maximumCollapseBlocks
                || maximumTrackedBlocksPerSection > 512
                || playerNoticeRadius < 1 || playerNoticeRadius > 128) {
            throw new IllegalArgumentException("Один или несколько параметров риска некорректны");
        }

        String particleName = getConfig().getString("cracks.particle", "BLOCK");
        String materialName = getConfig().getString("cracks.material", "STONE");
        if (particleName == null || particleName.isBlank() || materialName == null || materialName.isBlank()) {
            throw new IllegalArgumentException("cracks.particle и cracks.material не могут быть пустыми");
        }
        Particle crackParticle;
        Material crackMaterial;
        try {
            crackParticle = Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
            crackMaterial = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Некорректный cracks.particle или cracks.material", exception);
        }
        if (!crackMaterial.isBlock()) {
            throw new IllegalArgumentException("cracks.material должен быть блоком");
        }

        YamlConfiguration loadedMessages = YamlConfiguration.loadConfiguration(
                new File(getDataFolder(), "messages_" + language + ".yml"));
        for (String key : MESSAGE_KEYS) {
            if (loadedMessages.getString(key) == null) {
                throw new IllegalArgumentException("Не найдено '" + key + "' в messages_" + language + ".yml.");
            }
        }

        settings = new Settings(pluginName, miningIncrease, explosionIncrease, warningThreshold,
                collapseThreshold, collapseChance, riskDecay, supportRelief, scanRadius,
                maximumTunnelY, collapseDelayTicks, maximumCollapseBlocks,
                maximumTrackedBlocksPerSection, playerNoticeRadius, collapseMode,
                crackParticle, crackMaterial.createBlockData());
        messages = loadedMessages;
    }

    private void increaseNearbyRisk(Location center, double increase) {
        increaseNearbyRisk(center, increase, null);
    }

    private void increaseNearbyRisk(Location center, double increase, MinedBlock minedBlock) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        Map<BlockPosition, Block> tunnelCandidates = new HashMap<>();
        Map<BlockPosition, Double> candidateDistances = new HashMap<>();
        int radius = settings.scanRadius();
        for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
            for (int y = Math.max(world.getMinHeight(), center.getBlockY() - radius);
                 y <= Math.min(settings.maximumTunnelY(), center.getBlockY() + radius); y++) {
                for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                    Block tunnelAir = world.getBlockAt(x, y, z);
                    if (!isTunnelAir(tunnelAir)) {
                        continue;
                    }

                    BlockPosition position = BlockPosition.zoneFor(tunnelAir);
                    double distance = tunnelAir.getLocation().distanceSquared(center);
                    if (distance >= candidateDistances.getOrDefault(position, Double.MAX_VALUE)) {
                        continue;
                    }
                    candidateDistances.put(position, distance);
                    tunnelCandidates.put(position, tunnelAir);
                }
            }
        }

        Map.Entry<BlockPosition, Block> candidate = tunnelCandidates.entrySet().stream()
                .min((left, right) -> Double.compare(
                        left.getValue().getLocation().distanceSquared(center),
                        right.getValue().getLocation().distanceSquared(center)))
                .orElse(null);
        if (candidate == null) {
            return;
        }

        Block tunnelAir = candidate.getValue();
        BlockPosition position = candidate.getKey();
        RiskState state = risks.computeIfAbsent(position, ignored -> new RiskState());
        state.tunnelPosition = BlockPosition.from(tunnelAir);
        if (hasNearbySupport(tunnelAir)) {
            state.risk = Math.max(0, state.risk - increase);
            return;
        }

        if (minedBlock != null) {
            if (!state.minedBlocks.containsKey(minedBlock.position())
                    && state.minedBlocks.size() >= settings.maximumTrackedBlocksPerSection()) {
                state.minedBlocks.remove(state.minedBlocks.keySet().iterator().next());
            }
            state.minedBlocks.putIfAbsent(minedBlock.position(), minedBlock);
            state.lastMiner = minedBlock.minerId();
        }

        state.risk = Math.min(100, state.risk + increase);
        showCracks(tunnelAir.getLocation());
        if (state.risk >= settings.warningThreshold() && !state.warned) {
            state.warned = true;
            notifyNearby(tunnelAir.getLocation(), "warning");
            world.playSound(tunnelAir.getLocation(), Sound.BLOCK_STONE_BREAK, 0.7f, 0.65f);
        }
        if (state.risk >= settings.collapseThreshold() && !state.collapseScheduled
                && random.nextDouble(100) < settings.collapseChance()) {
            state.collapseScheduled = true;
            scheduleCollapse(position, state);
        }
    }

    private void destabilizeNearExplosion(Location location) {
        if (isUnderground(location)) {
            increaseNearbyRisk(location, settings.explosionIncrease());
        }
    }

    private void reinforceNearbyTunnels(Location supportLocation, Player player) {
        World world = supportLocation.getWorld();
        if (world == null) {
            return;
        }
        int reinforced = 0;
        for (RiskState state : risks.values()) {
            BlockPosition position = state.tunnelPosition;
            if (state.risk == 0 || position == null || !position.worldId().equals(world.getUID())
                    || Math.abs(position.x() - supportLocation.getBlockX()) > 2
                    || Math.abs(position.y() - supportLocation.getBlockY()) > 2
                    || Math.abs(position.z() - supportLocation.getBlockZ()) > 2) {
                continue;
            }
            state.risk = Math.max(0, state.risk - settings.supportRelief());
            if (state.risk < settings.warningThreshold()) {
                state.warned = false;
            }
            reinforced++;
        }
        if (reinforced > 0) {
            sendActionBar(player, "reinforced");
            world.playSound(supportLocation, Sound.BLOCK_WOOD_PLACE, 0.8f, 1.0f);
        }
    }

    private void scheduleCollapse(BlockPosition position, RiskState state) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (risks.get(position) != state) {
                return;
            }
            BlockPosition tunnelPosition = state.tunnelPosition;
            if (tunnelPosition == null) {
                risks.remove(position);
                return;
            }
            World world = Bukkit.getWorld(tunnelPosition.worldId());
            if (world == null) {
                risks.remove(position);
                return;
            }
            Block tunnelAir = world.getBlockAt(tunnelPosition.x(), tunnelPosition.y(), tunnelPosition.z());
            if (state.risk < settings.collapseThreshold() || !isTunnelAir(tunnelAir)
                    || hasNearbySupport(tunnelAir)) {
                state.collapseScheduled = false;
                return;
            }

            List<MinedBlock> minedBlocks = new ArrayList<>(state.minedBlocks.values());
            boolean collapsed = false;
            if (!minedBlocks.isEmpty() && settings.collapseMode() == CollapseMode.FALL_ON_PLAYER
                    && state.lastMiner != null) {
                collapsed = dropMinedBlocksOnPlayer(world, tunnelAir.getLocation(), minedBlocks, state.lastMiner);
            }
            if (!collapsed && !minedBlocks.isEmpty()) {
                collapsed = restoreMinedBlocks(world, minedBlocks);
            }
            if (!collapsed) {
                collapsed = dropRoofBlocks(world, tunnelAir);
            }
            if (collapsed) {
                spawnCracks(world, tunnelAir.getLocation().add(0.5, 1, 0.5), 18, 0.35);
                world.playSound(tunnelAir.getLocation(), Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.8f);
                notifyNearby(tunnelAir.getLocation(), "collapse");
            }
            risks.remove(position);
        }, settings.collapseDelayTicks());
    }

    private boolean dropRoofBlocks(World world, Block tunnelAir) {
        int dropped = 0;
        for (int y = 1; y <= 4 && dropped < settings.maximumCollapseBlocks(); y++) {
            for (int x = -1; x <= 1 && dropped < settings.maximumCollapseBlocks(); x++) {
                for (int z = -1; z <= 1 && dropped < settings.maximumCollapseBlocks(); z++) {
                    Block roofBlock = tunnelAir.getRelative(x, y, z);
                    if (!isNaturalRock(roofBlock.getType())) {
                        continue;
                    }
                    BlockData data = roofBlock.getBlockData().clone();
                    Location spawnLocation = roofBlock.getLocation().add(0.5, 0, 0.5);
                    roofBlock.setType(Material.AIR, false);
                    spawnFallingBlock(world, spawnLocation, data);
                    dropped++;
                }
            }
        }
        return dropped > 0;
    }

    private boolean restoreMinedBlocks(World world, List<MinedBlock> minedBlocks) {
        int restored = 0;
        for (MinedBlock minedBlock : minedBlocks) {
            if (restored >= settings.maximumCollapseBlocks()) {
                break;
            }
            BlockPosition position = minedBlock.position();
            if (!position.worldId().equals(world.getUID())) {
                continue;
            }
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            if (block.getType().isAir()) {
                block.setBlockData(minedBlock.data().clone(), false);
                restored++;
            }
        }
        return restored > 0;
    }

    private boolean dropMinedBlocksOnPlayer(
            World world, Location tunnelLocation, List<MinedBlock> minedBlocks, UUID minerId
    ) {
        Player target = Bukkit.getPlayer(minerId);
        if (target == null || !target.getWorld().equals(world)
                || target.getLocation().distanceSquared(tunnelLocation)
                > settings.playerNoticeRadius() * settings.playerNoticeRadius()) {
            return false;
        }

        Location targetLocation = target.getLocation();
        int dropped = 0;
        for (MinedBlock minedBlock : minedBlocks) {
            if (!minerId.equals(minedBlock.minerId()) || dropped >= settings.maximumCollapseBlocks()) {
                continue;
            }
            int column = dropped % 9;
            int layer = dropped / 9;
            double offsetX = (column % 3 - 1) * 0.9;
            double offsetZ = (column / 3 - 1) * 0.9;
            Location spawnLocation = targetLocation.clone().add(offsetX, 5 + layer * 1.2, offsetZ);
            spawnFallingBlock(world, spawnLocation, minedBlock.data().clone());
            dropped++;
        }
        return dropped > 0;
    }

    private void spawnFallingBlock(World world, Location location, BlockData blockData) {
        world.spawn(location, org.bukkit.entity.FallingBlock.class, fallingBlock -> {
            fallingBlock.setBlockData(blockData);
            fallingBlock.setHurtEntities(true);
            fallingBlock.setDropItem(false);
        });
    }

    private void showCracks(Location location) {
        World world = location.getWorld();
        if (world != null) {
            spawnCracks(world, location.clone().add(0.5, 0.5, 0.5), 6, 0.35);
        }
    }

    private void spawnCracks(World world, Location location, int count, double offset) {
        if (settings.crackParticle() == Particle.BLOCK) {
            world.spawnParticle(settings.crackParticle(), location, count, offset, offset, offset,
                    0.01, settings.crackData());
        } else {
            world.spawnParticle(settings.crackParticle(), location, count, offset, offset, offset, 0.01);
        }
    }

    private void notifyNearby(Location location, String messageKey) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        double radiusSquared = settings.playerNoticeRadius() * settings.playerNoticeRadius();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                if (messageKey.equals("warning")) {
                    long now = System.currentTimeMillis();
                    long lastSent = lastWarningActionbar.getOrDefault(player.getUniqueId(), 0L);
                    if (now - lastSent < WARNING_ACTIONBAR_COOLDOWN_MILLIS) {
                        continue;
                    }
                    lastWarningActionbar.put(player.getUniqueId(), now);
                }
                sendActionBar(player, messageKey);
            }
        }
    }

    private void sendActionBar(Player player, String key) {
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalStateException("Сообщение не загружено " + key);
        }
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(message.replace("{plugin}", settings.pluginName())));
    }

    private void sendMessage(CommandSender sender, String key) {
        String message = messages.getString(key);
        if (message == null) {
            throw new IllegalStateException("Сообщение не загружено " + key);
        }
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(message.replace("{plugin}", settings.pluginName())));
    }

    private void decayRisks() {
        Iterator<Map.Entry<BlockPosition, RiskState>> iterator = risks.entrySet().iterator();
        while (iterator.hasNext()) {
            RiskState state = iterator.next().getValue();
            state.risk = Math.max(0, state.risk - settings.riskDecay());
            if (state.risk == 0 && !state.collapseScheduled) {
                iterator.remove();
            } else if (state.risk < settings.warningThreshold()) {
                state.warned = false;
            }
        }
    }

    private boolean isUnderground(Location location) {
        World world = location.getWorld();
        return world != null && location.getBlockY() >= world.getMinHeight()
                && location.getBlockY() <= settings.maximumTunnelY();
    }

    private boolean isTunnelAir(Block block) {
        if (!block.getType().isAir() || !isUnderground(block.getLocation())) {
            return false;
        }
        int surroundingRock = 0;
        for (int[] direction : DIRECTIONS) {
            if (isRock(block.getRelative(direction[0], direction[1], direction[2]).getType())) {
                surroundingRock++;
            }
        }
        return surroundingRock >= 2;
    }

    private boolean hasNearbySupport(Block block) {
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if ((x != 0 || y != 0 || z != 0)
                            && isWoodenSupport(block.getRelative(x, y, z).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isRock(Material material) {
        return isNaturalRock(material) || material.name().endsWith("_ORE");
    }

    private boolean isNaturalRock(Material material) {
        String name = material.name();
        return name.equals("STONE") || name.equals("GRANITE") || name.equals("DIORITE")
                || name.equals("ANDESITE") || name.equals("TUFF") || name.equals("CALCITE")
                || name.equals("DEEPSLATE") || name.equals("COBBLED_DEEPSLATE")
                || name.equals("BLACKSTONE") || name.equals("BASALT")
                || name.equals("NETHERRACK") || name.equals("END_STONE");
    }

    private boolean isWoodenSupport(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_PLANKS")
                || name.endsWith("_FENCE") || name.endsWith("_STEM") || name.endsWith("_HYPHAE")
                || name.equals("BAMBOO_BLOCK") || name.equals("STRIPPED_BAMBOO_BLOCK");
    }

    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private record BlockPosition(UUID worldId, int x, int y, int z) {
        private static BlockPosition from(Block block) {
            return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        private static BlockPosition zoneFor(Block block) {
            return new BlockPosition(block.getWorld().getUID(),
                    Math.floorDiv(block.getX(), 8), Math.floorDiv(block.getY(), 8), Math.floorDiv(block.getZ(), 8));
        }
    }

    private static final class RiskState {
        private double risk;
        private boolean warned;
        private boolean collapseScheduled;
        private BlockPosition tunnelPosition;
        private UUID lastMiner;
        private final Map<BlockPosition, MinedBlock> minedBlocks = new LinkedHashMap<>();
    }

    private record MinedBlock(BlockPosition position, BlockData data, UUID minerId) {
    }

    private enum CollapseMode {
        RESTORE_MINED_BLOCKS,
        FALL_ON_PLAYER
    }

    private record Settings(
            String pluginName,
            double miningIncrease,
            double explosionIncrease,
            double warningThreshold,
            double collapseThreshold,
            double collapseChance,
            double riskDecay,
            double supportRelief,
            int scanRadius,
            int maximumTunnelY,
            int collapseDelayTicks,
            int maximumCollapseBlocks,
            int maximumTrackedBlocksPerSection,
            int playerNoticeRadius,
            CollapseMode collapseMode,
            Particle crackParticle,
            BlockData crackData
    ) {
    }
}
