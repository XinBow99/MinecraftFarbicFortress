package com.xinbow99.fortressduel.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;

/**
 * 一個軸對齊的方塊範圍（兩端都含）。競技場的 n×n 範圍、核心底座、邊界牆都用它表示。
 * 不可變：所有「改範圍」的方法都回傳新的 Region。
 */
public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements Iterable<BlockPos> {

    public static Region of(BlockPos a, BlockPos b) {
        return new Region(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    /** 以 center 為中心、邊長 size 的正方形柱體，垂直方向從 center.y + below 到 center.y + above。 */
    public static Region square(BlockPos center, int size, int below, int above) {
        // 邊長為偶數時中心格會偏一邊，這裡讓 min 側多吃一格，跟 /fill 的直覺一致
        int half = size / 2;
        int lo = half - (size % 2 == 0 ? 1 : 0);
        return new Region(
                center.getX() - lo, center.getY() + below, center.getZ() - lo,
                center.getX() + half, center.getY() + above, center.getZ() + half);
    }

    /**
     * 一個同時把 a、b 兩點框進來的正方形。
     *
     * <p>對戰不再把玩家傳送到預先選好的場地，而是**就地**在雙方之間框出範圍，所以邊長不能是
     * 固定值——兩個人站得多遠，場地就要多大。取「兩點的最大軸距 + 兩側留白」與設定的最小邊長
     * 之中較大的那個。
     */
    public static Region around(BlockPos a, BlockPos b, int minSize, int margin, int below, int above) {
        int centerX = (a.getX() + b.getX()) / 2;
        int centerZ = (a.getZ() + b.getZ()) / 2;
        int span = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
        int size = Math.max(minSize, span + margin * 2);

        // 垂直方向以兩人之中較低的那個為基準，站在山坡上時上面那個人才不會超出範圍
        int baseY = Math.min(a.getY(), b.getY());
        return square(new BlockPos(centerX, baseY, centerZ), size, below, above);
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean contains(double x, double y, double z) {
        // 用方塊座標比較：實體站在 maxX 那一格上時 x 可能是 maxX + 0.9
        return contains(Mth.floor(x), Mth.floor(y), Mth.floor(z));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** 只看水平投影。玩家跳出去或挖到腳下時 Y 會超出，但那不算離開競技場。 */
    public boolean containsHorizontally(double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        return bx >= minX && bx <= maxX && bz >= minZ && bz <= maxZ;
    }

    /** 這一格是不是在水平邊界上（用來砌圍牆）。 */
    public boolean isHorizontalEdge(int x, int z) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }

    public Region expand(int d) {
        return new Region(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
    }

    /** 沿 Z 軸切成兩半（north = z 較小那半）。核心分邊用。 */
    public Region halfNorth() {
        return new Region(minX, minY, minZ, maxX, maxY, minZ + sizeZ() / 2 - 1);
    }

    public Region halfSouth() {
        return new Region(minX, minY, minZ + sizeZ() / 2, maxX, maxY, maxZ);
    }

    public BlockPos center() {
        return new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    public Vec3 centerVec() {
        return new Vec3((minX + maxX + 1) / 2.0, minY, (minZ + maxZ + 1) / 2.0);
    }

    @Override
    public Iterator<BlockPos> iterator() {
        return BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ).iterator();
    }

    // BlockPos.betweenClosed 回傳的是可變的 MutableBlockPos，拿來當 map 的 key 一定要 immutable()

    /** 小工具：避免為了一個 floor 去 import net.minecraft.util.Mth。 */
    private static final class Mth {
        static int floor(double v) {
            int i = (int) v;
            return v < i ? i - 1 : i;
        }
    }
}
