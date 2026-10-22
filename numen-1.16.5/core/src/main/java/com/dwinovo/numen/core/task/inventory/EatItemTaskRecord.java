package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.core.sdk.ServerCall;
import com.dwinovo.numen.core.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for {@code inv eat}: consume a food / drink from the
 * companion's inventory. {@link EatCompanionTask} eats it natively (the player
 * body's own held-use path), so hunger + saturation + consume effects apply
 * exactly as they do for a real player when the chew completes.
 */
public final class EatItemTaskRecord extends TaskRecord {

    /** Generous — covers any food's eat duration (most ~1.6s) plus buffer. */
    private static final long TIMEOUT_TICKS = 15 * 20;

    /** The food item to eat (one is consumed on completion). */
    public final Item item;
    /** Human-readable label for messages / debug overlay (e.g. "golden_apple"). */
    public final String label;
    /**
     * 这一餐是本能(auto_eat)开的吗:是则不反过来按住那条本能(它防的是"模型在喂她"这件事,
     * 不需要防它自己)——否则反射自己那一口会把下一口锁到 resumeAllReflexes 才解开。
     */
    public final boolean fromReflex;

    public EatItemTaskRecord(ServerCall source, Item item, String label) {
        super(source, source.her().level().getGameTime() + TIMEOUT_TICKS);
        this.item = item;
        this.label = label;
        this.fromReflex = false;
    }

    /** 本能开的这一口:没有调用方,名字与号自己起(照 AttackTaskRecord 对反射的口径)。 */
    public EatItemTaskRecord(String name, String toolCallId, long deadlineGameTime,
                             Item item, String label, boolean fromReflex) {
        super(name, toolCallId, deadlineGameTime);
        this.item = item;
        this.label = label;
        this.fromReflex = fromReflex;
    }

    @Override
    public String describe() {
        return getToolName() + " " + label;
    }
}
