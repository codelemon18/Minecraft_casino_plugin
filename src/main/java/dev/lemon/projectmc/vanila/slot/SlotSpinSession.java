package dev.lemon.projectmc.vanila.slot;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;
import java.util.UUID;

/**
 * 단일 플레이어 슬롯 스핀 세션. 애니메이션 및 결과 확정 관리
 */
public class SlotSpinSession {
    public enum State { IDLE, SPINNING }

    private final SlotMachineManager manager;
    private final UUID playerUUID;
    private int bet;
    private Inventory inventory;
    private State state = State.IDLE;

    private int globalFrame;
    private BukkitTask task;

    private SlotSymbol finalLeft, finalMid, finalRight;
    private boolean leftRunning, midRunning, rightRunning;

    private final Random animationRandom = new Random();
    private SlotSymbol lastCenterLeft, lastCenterMid, lastCenterRight; // 연속 중복 방지용

    public SlotSpinSession(SlotMachineManager manager, UUID playerUUID, int bet, Inventory inventory) {
        this.manager = manager;
        this.playerUUID = playerUUID;
        this.bet = bet;
        this.inventory = inventory;
    }

    public void beginSpin(int frameIntervalTicks, int leftFrames, int middleDelay, int rightDelay) {
        if (state == State.SPINNING) return;
        state = State.SPINNING;
        globalFrame = 0;
        leftRunning = true; midRunning = true; rightRunning = true;
        finalLeft = null; finalMid = null; finalRight = null;

        int stopLeft = leftFrames;
        int stopMid = leftFrames + middleDelay;
        int stopRight = stopMid + rightDelay;

        cancelTaskSafe();
        task = Bukkit.getScheduler().runTaskTimer(manager.getPlugin(), () -> {
            globalFrame++;
            // 애니메이션 프레임 갱신: 고등급 편향 animationReelList 사용
            if (leftRunning) animateReel(SlotMachineManager.LEFT_TOP, SlotMachineManager.LEFT_CENTER, SlotMachineManager.LEFT_BOTTOM);
            if (midRunning) animateReel(SlotMachineManager.MID_TOP, SlotMachineManager.MID_CENTER, SlotMachineManager.MID_BOTTOM);
            if (rightRunning) animateReel(SlotMachineManager.RIGHT_TOP, SlotMachineManager.RIGHT_CENTER, SlotMachineManager.RIGHT_BOTTOM);

            // 정지 시점 도달 시 실제 확률로 최종 결과 확정
            if (leftRunning && globalFrame >= stopLeft) {
                leftRunning = false;
                finalLeft = manager.randomSymbol(animationRandom); // 실제 확률
                setFinalReel(finalLeft, SlotMachineManager.LEFT_TOP, SlotMachineManager.LEFT_CENTER, SlotMachineManager.LEFT_BOTTOM);
            }
            if (midRunning && globalFrame >= stopMid) {
                midRunning = false;
                finalMid = manager.randomSymbol(animationRandom);
                setFinalReel(finalMid, SlotMachineManager.MID_TOP, SlotMachineManager.MID_CENTER, SlotMachineManager.MID_BOTTOM);
            }
            if (rightRunning && globalFrame >= stopRight) {
                rightRunning = false;
                finalRight = manager.randomSymbol(animationRandom);
                setFinalReel(finalRight, SlotMachineManager.RIGHT_TOP, SlotMachineManager.RIGHT_CENTER, SlotMachineManager.RIGHT_BOTTOM);
            }

            if (!leftRunning && !midRunning && !rightRunning) {
                cancelTaskSafe();
                manager.finishSpin(this, finalLeft, finalMid, finalRight);
            }
        }, 0L, frameIntervalTicks);
    }

    private void animateReel(int topSlot, int centerSlot, int bottomSlot) {
        if (inventory == null) return;
        SlotSymbol center = manager.randomAnimationSymbol(animationRandom);
        // 연속 중앙 중복 금지 적용
        if (manager.isAnimationNoConsecutive()) {
            if (centerSlot == SlotMachineManager.LEFT_CENTER && lastCenterLeft == center) {
                center = pickDifferent(center, lastCenterLeft);
            } else if (centerSlot == SlotMachineManager.MID_CENTER && lastCenterMid == center) {
                center = pickDifferent(center, lastCenterMid);
            } else if (centerSlot == SlotMachineManager.RIGHT_CENTER && lastCenterRight == center) {
                center = pickDifferent(center, lastCenterRight);
            }
        }
        SlotSymbol top = manager.randomAnimationSymbol(animationRandom);
        SlotSymbol bottom = manager.randomAnimationSymbol(animationRandom);
        inventory.setItem(topSlot, manager.symbolItem(top));
        inventory.setItem(centerSlot, manager.symbolItem(center));
        inventory.setItem(bottomSlot, manager.symbolItem(bottom));
        if (centerSlot == SlotMachineManager.LEFT_CENTER) lastCenterLeft = center;
        if (centerSlot == SlotMachineManager.MID_CENTER) lastCenterMid = center;
        if (centerSlot == SlotMachineManager.RIGHT_CENTER) lastCenterRight = center;
    }

    private SlotSymbol pickDifferent(SlotSymbol current, SlotSymbol last) {
        int guard = 0;
        SlotSymbol next = current;
        while (next == last && guard < 10) { // 최대 10회 재시도
            next = manager.randomAnimationSymbol(animationRandom);
            guard++;
        }
        return next;
    }

    private void setFinalReel(SlotSymbol center, int topSlot, int centerSlot, int bottomSlot) {
        if (inventory == null) return;
        // 양 옆은 애니메이션 리스트에서 랜덤, 센터는 확정 결과
        SlotSymbol top = manager.randomAnimationSymbol(animationRandom);
        SlotSymbol bottom = manager.randomAnimationSymbol(animationRandom);
        inventory.setItem(topSlot, manager.symbolItem(top));
        inventory.setItem(centerSlot, manager.symbolItem(center));
        inventory.setItem(bottomSlot, manager.symbolItem(bottom));
    }

    public void cancelTaskSafe() { if (task != null) { task.cancel(); task = null; } }

    public UUID getPlayerUUID() { return playerUUID; }
    public int getBet() { return bet; }
    public void setBet(int bet) { this.bet = bet; }
    public Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
}
