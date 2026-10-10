package com.dwinovo.numen.pathing.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 视线:从眼睛到一格方块某一面上的一点,中间隔着什么。"看不看得见那一格"只在这里判——搜索在快照上调它(用一格方块的站位、
 * 挖一格时挡着几格),执行在活世界上调它(到达复核、挖放与按键的瞄点)。
 *
 * <p>口径照原版准星拾取({@code BlockGetter.clip},{@code ClipContext.Block.OUTLINE},不看流体):沿射线逐格,按方块轮廓
 * (连同交互轮廓的覆写)算碰没碰上。与原版的 clip 只差一处:碰上别的格之后不停,一直数到目标,把挡着的格分成两种——
 * <ul>
 *   <li><b>软遮挡</b>({@link #soft}):往里放一块别种方块时原版会直接顶掉的格(第 0 层 {@link Replaceable#displaced}:草、高草、
 *       蕨、枯灌木、单层雪、没长满的藤蔓……)。一下就掉、清掉它不算改建;清不清归用那一格的一方(挖掘执行清掉它,纯按键点到的就是它);</li>
 *   <li><b>硬遮挡</b>:其余有轮廓的格,要挖开才看得见。</li>
 * </ul>
 */
public final class Sight {

    /** 瞄面上的点时往面里收这么多:射线不贴着棱,从面朝外那一侧射过来才碰得上这一面。 */
    public static final double INSET = 0.02;
    /** 射线打到瞄点之后再往前伸这么多,保证穿进那一面。 */
    private static final double THROUGH = 0.1;

    private Sight() {}

    /**
     * 一次视线:从眼睛朝 {@code point} 射过去的结果。
     *
     * @param point   瞄点
     * @param reached 射线碰上了目标那一格的轮廓
     * @param face    碰上的是目标的哪一面;没碰上为 null
     * @param hard    路上挡着的硬遮挡,按先后
     * @param soft    路上挡着的软遮挡,按先后
     */
    public record Trace(Vec3 point, boolean reached, Direction face, List<BlockPos> hard, List<BlockPos> soft) {

        /** 第一下就碰上目标:准星对过去就落在它上面。给了 {@code side} 时还得是那一面。 */
        public boolean clear(Direction side) {
            return clearable(side) && soft.isEmpty();
        }

        /** 只隔着软遮挡:清掉它们就看得见。给了 {@code side} 时碰上的还得是那一面。 */
        public boolean clearable(Direction side) {
            return reached && hard.isEmpty() && (side == null || face == side);
        }
    }

    /** 软遮挡:挡在视线上、用之前一下清掉就行的格。判据只在这里:往里放一块别种方块时原版会直接顶掉它。 */
    public static boolean soft(BlockState state) {
        return Replaceable.displaced(state);
    }

    /**
     * 从 {@code eye} 朝 {@code point} 射一条视线,数到 {@code target} 为止:碰没碰上它、碰上的是哪一面、路上隔着哪些格。
     * 射线打到瞄点再往前伸一点,穿进那一面。
     */
    public static Trace trace(BlockGetter level, Vec3 eye, Vec3 point, BlockPos target) {
        Vec3 through = point.add(point.subtract(eye).normalize().scale(THROUGH));
        List<BlockPos> hard = new ArrayList<>();
        List<BlockPos> soft = new ArrayList<>();
        BlockHitResult hit = march(eye, through, pos -> {
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getShape(level, pos);
            BlockHitResult on = level.clipWithInteractionOverride(eye, through, pos, shape, state);
            if (on == null) {
                return null;
            }
            if (pos.equals(target)) {
                return on;
            }
            (soft(state) ? soft : hard).add(pos.immutable());
            return null;
        });
        return new Trace(point, hit != null, hit == null ? null : hit.getDirection(), List.copyOf(hard),
                List.copyOf(soft));
    }

    /** 逐格走时对每一格问一次;返回非 null 就地停下,整条走完都没有就返回 null。 */
    @FunctionalInterface
    private interface Cells<T> {
        T at(BlockPos pos);
    }

    /**
     * 沿 {@code from} → {@code to} 逐格走,格子顺序与原版 {@code BlockGetter.traverseBlocks} **逐字一致**。
     *
     * <p><b>为什么不直接调原版那个方法</b>:1.16.5 的它是
     * {@code traverseBlocks(ClipContext, BiFunction, Function)} 三参版,得先造一个 {@code ClipContext};
     * 而 {@code ClipContext} 的构造器**无条件**调 {@code CollisionContext.of(entity)},
     * 传 null 会在 {@code EntityCollisionContext} 里当场 NPE(本仓在真实 jar 的字节码上核过)。
     * 本模块的搜索阶段只有世界快照、根本没有实体可给,所以把它的逐格推进原样搬进来:
     * {@code -1.0E-7} 的容差、{@code Mth.sign}/{@code Mth.frac} 的用法、三个轴的比较顺序,全部照抄,不改一处——
     * 视线口径必须和原版准星拾取一模一样,差一点点都会让"看得见/看不见"的判定漂移。
     *
     * <p>原版那个方法本身只读 {@code ClipContext} 的起终点(实测字节码),从不读它的碰撞上下文,
     * 所以搬进来这一段与调它是等价的。
     */
    private static <T> T march(Vec3 from, Vec3 to, Cells<T> visitor) {
        if (from.equals(to)) {
            return null;
        }
        double d0 = Mth.lerp(-1.0E-7, to.x, from.x);
        double d1 = Mth.lerp(-1.0E-7, to.y, from.y);
        double d2 = Mth.lerp(-1.0E-7, to.z, from.z);
        double d3 = Mth.lerp(-1.0E-7, from.x, to.x);
        double d4 = Mth.lerp(-1.0E-7, from.y, to.y);
        double d5 = Mth.lerp(-1.0E-7, from.z, to.z);
        int i = Mth.floor(d3);
        int j = Mth.floor(d4);
        int k = Mth.floor(d5);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(i, j, k);
        T t = visitor.at(pos);
        if (t != null) {
            return t;
        }
        double d6 = d0 - d3;
        double d7 = d1 - d4;
        double d8 = d2 - d5;
        int l = Mth.sign(d6);
        int i1 = Mth.sign(d7);
        int j1 = Mth.sign(d8);
        double d9 = l == 0 ? Double.MAX_VALUE : (double) l / d6;
        double d10 = i1 == 0 ? Double.MAX_VALUE : (double) i1 / d7;
        double d11 = j1 == 0 ? Double.MAX_VALUE : (double) j1 / d8;
        double d12 = d9 * (l > 0 ? 1.0 - Mth.frac(d3) : Mth.frac(d3));
        double d13 = d10 * (i1 > 0 ? 1.0 - Mth.frac(d4) : Mth.frac(d4));
        double d14 = d11 * (j1 > 0 ? 1.0 - Mth.frac(d5) : Mth.frac(d5));
        while (d12 <= 1.0 || d13 <= 1.0 || d14 <= 1.0) {
            if (d12 < d13) {
                if (d12 < d14) {
                    i += l;
                    d12 += d9;
                } else {
                    k += j1;
                    d14 += d11;
                }
            } else if (d13 < d14) {
                j += i1;
                d13 += d10;
            } else {
                k += j1;
                d14 += d11;
            }
            t = visitor.at(pos.set(i, j, k));
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /**
     * 从 {@code eye} 挖 {@code target} 时朝哪一点看:朝 {@code points} 里的每一点各打一条视线,路上挡着的格(硬的、软的)都
     * {@code clears} 的那些里,取硬遮挡最少的那一条;一条都没有为 null——看得见它的每一处都隔着清不掉的格。挖一格时给站位定价
     * (瞄点是 {@link #faces})与挖的时候先清哪一格(瞄点是身体够得着的那几点)都按这一条挑。
     */
    public static Trace dig(BlockGetter level, Vec3 eye, BlockPos target, List<Vec3> points, Predicate<BlockPos> clears) {
        Trace best = null;
        for (Vec3 point : points) {
            Trace trace = trace(level, eye, point, target);
            if ((best == null || trace.hard().size() < best.hard().size())
                    && trace.hard().stream().allMatch(clears) && trace.soft().stream().allMatch(clears)) {
                best = trace;
            }
        }
        return best;
    }

    /** {@code target} 朝着 {@code eye} 的各面上的瞄点({@link Faces#point} 往面里收 {@link #INSET})。 */
    public static List<Vec3> faces(BlockGetter level, Vec3 eye, BlockPos target) {
        List<Vec3> out = new ArrayList<>(3);
        for (Direction side : Direction.values()) {
            Vec3 onFace = Faces.point(level, target, side);
            if (facing(eye, onFace, side)) {
                out.add(inset(onFace, side));
            }
        }
        return out;
    }

    /** 用一格方块时点它 {@code side} 面的那一点:面上的瞄点({@link Faces#point})往面里收 {@link #INSET}。 */
    public static Vec3 aim(BlockGetter level, BlockPos target, Direction side) {
        return inset(Faces.point(level, target, side), side);
    }

    /** 面上的点往面里收 {@link #INSET}。 */
    public static Vec3 inset(Vec3 onFace, Direction side) {
        return onFace.subtract(Vec3.atLowerCornerOf(side.getNormal()).scale(INSET));
    }

    /** 眼睛在这个面朝外的一侧:从这里看得到这一面。 */
    public static boolean facing(Vec3 eye, Vec3 onFace, Direction side) {
        Vec3 normal = Vec3.atLowerCornerOf(side.getNormal());
        return eye.subtract(onFace).dot(normal) > 1.0E-4;
    }

    /** 这一格有没有可点的轮廓:空气、流体没有,点不中。 */
    public static boolean clickable(BlockGetter level, BlockPos pos) {
        return !level.getBlockState(pos).getShape(level, pos).isEmpty();
    }

    /**
     * {@code target} 的 {@code side} 面敞不敞开:面前那一格不是整块的硬遮挡,视线过得去。整块的硬方块贴着的面从哪儿都看不见,
     * 不必再打射线。
     */
    public static boolean open(BlockGetter level, BlockPos target, Direction side) {
        BlockPos front = target.relative(side);
        BlockState state = level.getBlockState(front);
        return soft(state) || !Block.isShapeFullBlock(state.getShape(level, front));
    }

    /**
     * 从 {@code eye} 用 {@code target} 的 {@code side} 面:这一面敞开({@link #open})、面朝着眼睛、面上的点在交互距离
     * {@code reach} 以内、视线上没有硬遮挡、碰上的就是这一面——这样交出这一次的视线(可能还隔着软遮挡);否则为 null。
     * 面前贴着整块硬方块的面不算,哪怕从一道细缝里斜着看得到它:那样的线差半个鼠标像素就落到别的方块上。
     */
    public static Trace use(BlockGetter level, Vec3 eye, double reach, BlockPos target, Direction side) {
        if (!open(level, target, side)) {
            return null;
        }
        Vec3 onFace = Faces.point(level, target, side);
        if (!facing(eye, onFace, side) || onFace.distanceTo(eye) >= reach) {
            return null;
        }
        Trace trace = trace(level, eye, inset(onFace, side), target);
        return trace.clearable(side) ? trace : null;
    }
}
