package org.blog.teamPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 팀 점령전 + 병과 선택 (Shift+Q 트리거)
 * - Shift+Q: IDLE → 시작/취소 GUI, COLLECTING → 병과 선택 GUI
 * - /tcap menu 로도 시작 메뉴 열기, 방송 수락/거절은 /tcap yes|no
 * - 20초 모집 후 랜덤 2분할, 4인 미만이면 취소
 * - 게임 시작 후 병과 변경 불가
 * - 기마 클래스는 말 즉시 소환/탑승, 배치 시 말째 이동
 */
public final class TeamPlugin extends JavaPlugin implements Listener {

    /* ===== 타이틀/슬롯 ===== */
    private static final String MENU_TITLE_ROOT   = "§a팀 점령전";
    private static final String MENU_TITLE_CLASS  = "§a병과 선택";

    private static final int SLOT_START  = 13;
    private static final int SLOT_CANCEL = 22;

    // 병과 선택창(가운데 정렬 느낌)
    private static final int CSLOT_BOW_INF   = 10;
    private static final int CSLOT_SWORD_INF = 12;
    private static final int CSLOT_LANCE_INF = 14;
    private static final int CSLOT_BOW_CAV   = 16;
    private static final int CSLOT_SWORD_CAV = 22;

    /* ===== 상태 ===== */
    private Conquest current; // 하나만 동시 진행(간단 버전)
    private final Set<UUID> spawnedHorses = new HashSet<>(); // 정리용

    /* ===== 모집 시간(틱) ===== */
    private static final long JOIN_WINDOW_TICKS = 20L * 40; // 20초

    /* ===== 병과 ===== */
    public enum ClassType { BOW_INF, SWORD_INF, LANCE_INF, BOW_CAV, SWORD_CAV }

    enum Phase { IDLE, COLLECTING, STARTED }

    static final class Conquest {
        Phase phase = Phase.IDLE;
        UUID initiator;
        long startedAtMs;
        int joinTaskId = -1;
        final Set<UUID> accepted = new HashSet<>();
        final Set<UUID> declined = new HashSet<>();
        final Map<UUID, Scoreboard> prevBoards = new HashMap<>();
        final Map<UUID, ClassType> chosenClass = new HashMap<>();
        final Set<UUID> teamA = new HashSet<>();
        final Set<UUID> teamB = new HashSet<>();
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TeamConquest Enabled");
    }

    @Override
    public void onDisable() {
        resetEvent();
    }

    /* -------------------------------------------------
     *           Shift+Q → 메뉴/병과 선택
     * ------------------------------------------------- */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDropWhileSneaking(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking()) return; // Shift 아닐 때 무시
        e.setCancelled(true);        // 아이템 드랍 방지

        if (current == null || current.phase == Phase.IDLE) {
            openRootMenu(p);
            return;
        }

        if (current.phase == Phase.COLLECTING) {
            if (!current.accepted.contains(p.getUniqueId())) {
                p.sendMessage("§c참가자만 병과를 선택할 수 있습니다.");
                return;
            }
            openClassMenu(p);
            return;
        }

        // STARTED 이후엔 병과 변경 불가
        p.sendMessage("§c게임이 이미 시작되어 병과를 바꿀 수 없습니다.");
    }

    /* -------------------------------------------------
     *                     GUI
     * ------------------------------------------------- */
    private void openRootMenu(Player opener) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_ROOT);

        inv.setItem(SLOT_START, icon(Material.LIME_CONCRETE, "§a팀 점령전 시작",
                "§7모집 시간: §f40초", "§7최소 인원: §f4명(2v2)", "§e클릭하여 시작"));
        inv.setItem(SLOT_CANCEL, icon(Material.RED_CONCRETE, "§c취소", "§7창을 닫습니다."));

        opener.openInventory(inv);
    }

    private void openClassMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_CLASS);

        inv.setItem(CSLOT_BOW_INF,   icon(Material.BOW,          "§a활 보병"));
        inv.setItem(CSLOT_SWORD_INF, icon(Material.DIAMOND_SWORD,"§a검 보병"));
        inv.setItem(CSLOT_LANCE_INF, icon(Material.NETHERITE_AXE,"§a창 보병"));
        inv.setItem(CSLOT_BOW_CAV,   icon(Material.SADDLE,       "§a활 기마"));
        inv.setItem(CSLOT_SWORD_CAV, icon(Material.DIAMOND_HORSE_ARMOR,"§a검 기마"));

        p.openInventory(inv);
    }

    private ItemStack icon(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null && lore.length > 0) m.setLore(Arrays.asList(lore));
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title == null) return;

        if (MENU_TITLE_ROOT.equals(title)) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            int slot = e.getRawSlot();
            if (slot == SLOT_START) {
                p.closeInventory();
                startJoinPhase(p);
            } else if (slot == SLOT_CANCEL) {
                p.closeInventory();
            }
            return;
        }

        if (MENU_TITLE_CLASS.equals(title)) {
            e.setCancelled(true);
            if (current == null || current.phase != Phase.COLLECTING) { p.closeInventory(); return; }
            if (!current.accepted.contains(p.getUniqueId())) { p.sendMessage("§c참가자만 병과를 선택할 수 있습니다."); p.closeInventory(); return; }

            ClassType picked = null;
            switch (e.getRawSlot()) {
                case CSLOT_BOW_INF   -> picked = ClassType.BOW_INF;
                case CSLOT_SWORD_INF -> picked = ClassType.SWORD_INF;
                case CSLOT_LANCE_INF -> picked = ClassType.LANCE_INF;
                case CSLOT_BOW_CAV   -> picked = ClassType.BOW_CAV;
                case CSLOT_SWORD_CAV -> picked = ClassType.SWORD_CAV;
            }
            if (picked == null) return;

            current.chosenClass.put(p.getUniqueId(), picked);
            giveLoadout(p, picked);
            p.closeInventory();
            p.sendMessage("§a병과가 §f" + classDisplay(picked) + "§a 로 설정되었습니다.");
        }
    }

    private String classDisplay(ClassType t) {
        return switch (t) {
            case BOW_INF -> "활 보병";
            case SWORD_INF -> "검 보병";
            case LANCE_INF -> "창 보병";
            case BOW_CAV -> "활 기마";
            case SWORD_CAV -> "검 기마";
        };
    }

    /* -------------------------------------------------
     *         모집/배치/시작 + 방송/커맨드
     * ------------------------------------------------- */

    private void startJoinPhase(Player opener) {
        current = new Conquest();
        current.phase = Phase.COLLECTING;
        current.initiator = opener.getUniqueId();
        current.startedAtMs = System.currentTimeMillis();

        broadcastJoinMessage(opener);

        current.joinTaskId = getServer().getScheduler().runTaskLater(this, () -> {
            if (current == null || current.phase != Phase.COLLECTING) return;
            doTeamAssignOrCancel();
        }, JOIN_WINDOW_TICKS).getTaskId();
    }

    private void broadcastJoinMessage(Player opener) {
        TextComponent prefix = Component.text("[팀 점령전] ", NamedTextColor.GOLD);
        Component q = Component.text(opener.getName(), NamedTextColor.GREEN)
                .append(Component.text(" 님이 팀 점령전을 시작했습니다. 참가하시겠습니까? ", NamedTextColor.WHITE));

        Component accept = Component.text("[수락] ", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tcap yes"));
        Component decline = Component.text("[거절]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/tcap no"));

        Component line = prefix.append(q).append(accept).append(Component.space()).append(decline);

        for (Player pl : Bukkit.getOnlinePlayers()) pl.sendMessage(line);
        getLogger().info("TeamConquest: 모집 방송 발송됨");
    }

    /* /tcap yes|no|menu (plugin.yml 없이 전처리로 처리) */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!msg.startsWith("/tcap")) return;

        e.setCancelled(true);
        String[] parts = msg.split("\\s+");

        if (parts.length >= 2 && "menu".equals(parts[1])) {
            if (current != null && current.phase != Phase.IDLE) {
                e.getPlayer().sendMessage("§c현재 다른 팀 점령전이 준비/진행 중입니다.");
                return;
            }
            openRootMenu(e.getPlayer());
            return;
        }

        if (parts.length >= 2 && ("yes".equals(parts[1]) || "no".equals(parts[1]))) {
            if (current == null || current.phase != Phase.COLLECTING) {
                e.getPlayer().sendMessage("§c지금은 참가를 받을 수 없습니다.");
                return;
            }
            if ("yes".equals(parts[1])) onAccept(e.getPlayer());
            else onDecline(e.getPlayer());
            return;
        }

        e.getPlayer().sendMessage("§c/tcap yes | /tcap no | /tcap menu");
    }

    private void onAccept(Player p) {
        if (current.accepted.contains(p.getUniqueId())) {
            p.sendMessage("§7이미 참가에 동의했습니다.");
            return;
        }
        current.accepted.add(p.getUniqueId());
        current.declined.remove(p.getUniqueId());
        current.prevBoards.put(p.getUniqueId(), p.getScoreboard());

        // 준비 장소: 월드 스폰 근처
        Location prep = p.getWorld().getSpawnLocation().clone().add(0, 1, 0);
        p.teleport(prep);

        // 병과 선택창 자동 오픈
        getServer().getScheduler().runTask(this, () -> openClassMenu(p));

        p.sendMessage("§a팀 점령전에 참가하였습니다. 병과를 선택하세요!");
    }

    private void onDecline(Player p) {
        if (current.declined.contains(p.getUniqueId())) {
            p.sendMessage("§7이미 거절했습니다.");
            return;
        }
        current.declined.add(p.getUniqueId());
        current.accepted.remove(p.getUniqueId());
        p.sendMessage("§c참가를 거절했습니다.");
    }

    private void doTeamAssignOrCancel() {
        // 온라인인 수락자만
        List<Player> participants = current.accepted.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .filter(Player::isOnline)
                .collect(Collectors.toList());

        if (participants.size() < 4) {
            for (Player p : participants) p.sendMessage("§c참가 인원이 부족하여 팀 점령전이 취소되었습니다. (최소 4명 필요)");
            resetEvent();
            return;
        }

        // 병과 미선택자 기본값(검 보병)
        for (Player p : participants) current.chosenClass.putIfAbsent(p.getUniqueId(), ClassType.SWORD_INF);

        // 랜덤 셔플 후 반으로 분리(홀수면 자동 한 명 차이)
        Collections.shuffle(participants);
        int mid = participants.size() / 2;
        List<Player> A = new ArrayList<>(participants.subList(0, mid));
        List<Player> B = new ArrayList<>(participants.subList(mid, participants.size()));

        current.teamA.clear(); current.teamB.clear();
        A.forEach(pl -> current.teamA.add(pl.getUniqueId()));
        B.forEach(pl -> current.teamB.add(pl.getUniqueId()));

        // 관점별 스코어보드(아군 파랑, 적군 흰색)
        for (Player viewer : participants) applyPerPlayerScoreboard(viewer, A, B);

        // 팀별 이동(스폰 기준 좌/우 5블럭, 기마는 말째 이동)
        for (Player pl : A) teleportWithMount(pl, +5);
        for (Player pl : B) teleportWithMount(pl, -5);

        // 시작 알림
        for (Player pl : participants)
            pl.sendMessage("§a팀 배정 완료! §b아군 닉네임은 파란색§a, §f적군은 흰색입니다. §7(게임 시작)");

        current.phase = Phase.STARTED;
        getLogger().info("TeamConquest started: A=" + A.size() + ", B=" + B.size());
    }

    /* -------------------------------------------------
     *               장비/말/이동 유틸
     * ------------------------------------------------- */

    private void giveLoadout(Player p, ClassType t) {
        // 인벤 초기화
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setItemInOffHand(null);

        // 공통 방어구(보호 II)
        p.getInventory().setHelmet(enchant(new ItemStack(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setChestplate(enchant(new ItemStack(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setLeggings(enchant(new ItemStack(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setBoots(enchant(new ItemStack(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));

        // 공통 소모품
        p.getInventory().setItem(8, new ItemStack(Material.GOLDEN_APPLE, 8));
        p.getInventory().setItem(7, new ItemStack(Material.COOKED_BEEF, 32));

        switch (t) {
            case SWORD_INF -> {
                p.getInventory().setItem(0, enchant(new ItemStack(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2)); // 날카 II
                p.getInventory().setItem(1, enchant(new ItemStack(Material.DIAMOND_AXE), Enchantment.DAMAGE_UNDEAD, 1));    // 강타 I
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));                                        // 방패
            }
            case SWORD_CAV -> {
                p.getInventory().setItem(0, enchant(new ItemStack(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2));
                p.getInventory().setItem(1, enchant(new ItemStack(Material.DIAMOND_AXE), Enchantment.DAMAGE_UNDEAD, 1));
                p.getInventory().setItem(2, new ItemStack(Material.HAY_BLOCK, 64)); // 밀짚
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
                ensureMountedOnHorse(p);
            }
            case LANCE_INF -> {
                // 창 = 네더라이트 도끼(날카 III + 강타 III)
                ItemStack spear = new ItemStack(Material.NETHERITE_AXE);
                spear.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 3);
                spear.addUnsafeEnchantment(Enchantment.DAMAGE_UNDEAD, 3);
                p.getInventory().setItem(0, spear);
                p.getInventory().setItem(1, enchant(new ItemStack(Material.DIAMOND_AXE), Enchantment.DAMAGE_UNDEAD, 1));
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            }
            case BOW_INF -> {
                ItemStack bow = enchant(new ItemStack(Material.BOW), Enchantment.ARROW_DAMAGE, 3); // 힘 III
                bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
                p.getInventory().setItem(0, bow);
                // 화살은 내부 인벤(핫바 아닌 곳)
                p.getInventory().setItem(18, new ItemStack(Material.ARROW, 1));
            }
            case BOW_CAV -> {
                ItemStack bow = enchant(new ItemStack(Material.BOW), Enchantment.ARROW_DAMAGE, 3);
                bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
                p.getInventory().setItem(0, bow);
                p.getInventory().setItem(1, new ItemStack(Material.HAY_BLOCK, 64)); // 밀짚
                p.getInventory().setItem(18, new ItemStack(Material.ARROW, 1));
                ensureMountedOnHorse(p);
            }
        }
        p.updateInventory();
    }

    private void ensureMountedOnHorse(Player player) {
        // 이미 말 타고 있으면 스킵
        if (player.getVehicle() instanceof Horse) return;
        Horse h = spawnHorseFor(player);
        if (h != null) {
            h.addPassenger(player);
            spawnedHorses.add(h.getUniqueId());
        }
    }

    private void teleportWithMount(Player p, int dx) {
        Location base = p.getWorld().getSpawnLocation().clone().add(dx, 0, 0);
        int y = p.getWorld().getHighestBlockYAt(base);
        Location safe = new Location(base.getWorld(), base.getX(), y + 1, base.getZ());

        if (p.getVehicle() instanceof Horse h && !h.isDead()) {
            h.teleport(safe);
            // 탑승 유지: 보통 텔레포트로 유지되지만, 혹시 떨어지면 다시 태움
            if (!h.getPassengers().contains(p)) h.addPassenger(p);
        } else {
            p.teleport(safe);
        }
    }

    private ItemStack enchant(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
        return item;
    }

    /* 네가 준 말 스탯 그대로 적용 */
    private Horse spawnHorseFor(Player player) {
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        try { horse.setOwner(player); } catch (Throwable ignored) {} // 버전에 따라 Deprecated
        horse.setTamed(true);
        horse.setAdult();
        horse.setMaxHealth(30.0);
        horse.setHealth(30.0);

        AttributeInstance speed = horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.30);
        horse.setJumpStrength(1.0);

        ItemStack armor = new ItemStack(Material.DIAMOND_HORSE_ARMOR);
        ItemMeta meta = armor.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 1, true); // 보호 I
        armor.setItemMeta(meta);

        horse.getInventory().setArmor(armor);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        return horse;
    }

    /* 개인 스코어보드: 아군 파랑, 적군 흰색 */
    private void applyPerPlayerScoreboard(Player viewer, List<Player> A, List<Player> B) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        Scoreboard board = sm.getNewScoreboard();

        boolean viewerInA = A.stream().anyMatch(p -> p.getUniqueId().equals(viewer.getUniqueId()));
        List<Player> allies = viewerInA ? A : B;
        List<Player> enemies = viewerInA ? B : A;

        Team ally = board.registerNewTeam("ally");
        ally.setColor(org.bukkit.ChatColor.BLUE);
        ally.setCanSeeFriendlyInvisibles(true);

        Team enemy = board.registerNewTeam("enemy");
        enemy.setColor(org.bukkit.ChatColor.WHITE);

        for (Player p : allies) ally.addEntry(p.getName());
        for (Player p : enemies) enemy.addEntry(p.getName());

        viewer.setScoreboard(board);
    }

    private void resetEvent() {
        if (current != null && current.joinTaskId != -1) {
            getServer().getScheduler().cancelTask(current.joinTaskId);
        }

        // 스코어보드 원복
        if (current != null) {
            for (Map.Entry<UUID, Scoreboard> ent : current.prevBoards.entrySet()) {
                Player p = Bukkit.getPlayer(ent.getKey());
                if (p != null && p.isOnline()) {
                    try { p.setScoreboard(ent.getValue()); } catch (Exception ignored) {}
                }
            }
        }

        // 생성했던 말 정리
        for (UUID hid : new HashSet<>(spawnedHorses)) {
            for (World w : Bukkit.getWorlds()) {
                var ent = w.getEntity(hid);
                if (ent instanceof Horse h && !h.isDead()) h.remove();
            }
            spawnedHorses.remove(hid);
        }

        current = null;
    }
}
