package com.cga.tile;

import com.cga.CGA;
import com.cga.block.BlockCGABlastDoor;
import com.cga.block.BlockCGABlastDoorPanel;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

/**
 * CGA 防爆门的方块实体（挂在控制器那一格上）。
 *
 * <h3>门的形状</h3>
 * 控制器永远在门的<b>左下角</b>。门面沿"控制器朝向的右手方向"水平展开 {@code 宽度} 格、
 * 向上展开 {@code 高度} 格，其余格子都是门板。玩家只要记住"以我放的那块为准，往右往上长"。
 *
 * <h3>开 / 关</h3>
 * <ul>
 *   <li>开门：门让出通道，玩家能走过去。</li>
 *   <li>关门：<b>先检查门里有没有实体</b>；有就记下"待关"，每 tick 重试，直到清空才关，
 *       不把人夹死。</li>
 * </ul>
 *
 * <h3>红石</h3>
 * 只在电平<b>变化</b>时响应（边沿触发）。手动开关门之后，不会被"红石电平没变"的状态顶回去。
 *
 * <h3>连续开合</h3>
 * 用 0..1 的平滑进度 {@link #openAmount} 表示门让到哪；门板方块随之"长出来 / 收回去"，
 * 于是"看到的门让到哪"和"能不能走过去"是同一个量。
 */
public class TileCGABlastDoor extends TileEntity implements ITickable {

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 7;
    public static final int DEFAULT_WIDTH = 3;
    public static final int DEFAULT_HEIGHT = 3;

    /** 每 tick 推进的 0..1 比例。全程耗时 ≈ 1/(speed*20) 秒，普通门约 1.2 秒。 */
    public static final float SPEED_NORMAL = 0.042F;

    private static final int CELL_RETRY_MAX = 40;

    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    /** true = 向上收（收进门楣），false = 向下收（沉进地板） */
    private boolean retractUp = false;
    /** 反向：有红石信号时反而关门 */
    private boolean invert = false;

    private boolean built = false;
    private boolean wantOpen = false;
    private float openAmount = 0.0F;
    /** 客户端自己插值的副本（transient，不随 NBT 存） */
    public transient float clientAnim = 0.0F;
    private transient boolean animSeeded = false;
    private boolean pendingClose = false;
    private boolean closeHeldAnnounced = false;
    private boolean cellBlockedReported = false;
    private boolean cellsDirty = false;
    private int cellRetryTicks = 0;

    private boolean initialized = false;
    private boolean lastPowered = false;
    private boolean removing = false;

    private String status = "";

    // ---------------------------------------------------------------- 参数

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isRetractUp() {
        return retractUp;
    }

    public boolean isInvert() {
        return invert;
    }

    public boolean isBuilt() {
        return built;
    }

    public boolean isOpen() {
        return wantOpen && !isMoving();
    }

    public boolean wantsOpen() {
        return wantOpen;
    }

    public boolean isMoving() {
        if (!built) {
            return false;
        }
        float target = wantOpen ? 1.0F : 0.0F;
        return Math.abs(openAmount - target) > 0.001F;
    }

    public float getOpenAmount() {
        return openAmount;
    }

    public float getClientAnim() {
        return clientAnim;
    }

    public String getStatus() {
        return status == null ? "" : status;
    }

    /** 客户端渲染每帧调用：把 clientAnim 缓动到服务端同步过来的 openAmount。 */
    public void stepClientAnim() {
        float target = openAmount;
        if (Math.abs(clientAnim - target) <= 0.0005F) {
            clientAnim = target;
            return;
        }
        float step = 0.08F;
        if (clientAnim < target) {
            clientAnim = Math.min(target, clientAnim + step);
        } else {
            clientAnim = Math.max(target, clientAnim - step);
        }
    }

    private static float clamp01(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    public static int clampSize(int value) {
        if (value < MIN_SIZE) {
            return MIN_SIZE;
        }
        return value > MAX_SIZE ? MAX_SIZE : value;
    }

    // ---------------------------------------------------------------- 几何

    public net.minecraft.util.EnumFacing getFacing() {
        if (world == null) {
            return net.minecraft.util.EnumFacing.NORTH;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockCGABlastDoor) {
            return state.getValue(BlockCGABlastDoor.FACING);
        }
        return net.minecraft.util.EnumFacing.NORTH;
    }

    /** 门面内的"右"方向（俯视顺时针） */
    public net.minecraft.util.EnumFacing getRight() {
        return getFacing().rotateY();
    }

    /** 第 (dx, dy) 格门的位置（dx 向右，dy 向上，控制器是 (0,0)） */
    public BlockPos cellAt(int dx, int dy) {
        return pos.offset(getRight(), dx).up(dy);
    }

    /** 某个位置是不是这扇门的一格 */
    public boolean contains(BlockPos target) {
        if (target == null) {
            return false;
        }
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                if (cellAt(dx, dy).equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 供界面/日志：{@code 3×3 · 已建 · 已开} */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(width).append('×').append(height);
        sb.append(built ? " · 已建" : " · 未建");
        if (built) {
            sb.append(isMoving() ? " · 开合中" : (wantOpen ? " · 已开" : " · 已关"));
        }
        if (invert) {
            sb.append(" · 反向");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 建造 / 拆除

    /** 这一格门板该写成哪一块方块。 */
    private IBlockState panelState() {
        return com.cga.CGABlocks.BLAST_DOOR_PANEL.getDefaultState()
                .withProperty(BlockCGABlastDoorPanel.OPEN, Boolean.FALSE)
                .withProperty(BlockCGABlastDoorPanel.RETRACT, retractUp);
    }

    /**
     * 建造门板。
     *
     * @return null = 成功；否则是给玩家看的错误文字
     */
    public String build(EntityPlayer player) {
        if (world == null || world.isRemote) {
            return null;
        }
        if (built) {
            return "已经建好了（要改尺寸请先点「拆除」）";
        }
        if (pos.getY() + height - 1 > world.getActualHeight() - 1) {
            return "门太高了，超出世界高度";
        }
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                BlockPos cell = cellAt(dx, dy);
                if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                    continue;
                }
                IBlockState existing = world.getBlockState(cell);
                if (!existing.getBlock().isReplaceable(world, cell)) {
                    setStatus("第 " + (dx + 1) + " 列 " + (dy + 1) + " 行被占着，先清空那一格");
                    return "第 " + (dx + 1) + " 列 " + (dy + 1) + " 行被别的方块占着，先清空";
                }
            }
        }
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                BlockPos cell = cellAt(dx, dy);
                if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                    continue;
                }
                world.setBlockState(cell, panelState(), 3);
            }
        }
        built = true;
        wantOpen = false;
        openAmount = 0.0F;
        pendingClose = false;
        closeHeldAnnounced = false;
        cellBlockedReported = false;
        cellsDirty = false;
        cellRetryTicks = 0;
        applyCells();
        setStatus("已建造 " + width + "×" + height + " 的门"
                + (entitiesInside() ? "（门里有实体，关门会稍等它走开）" : ""));
        CGA.log("[{}, {}, {}] 建造 CGA 防爆门 {}×{}（{}收，朝向 {}）",
                pos.getX(), pos.getY(), pos.getZ(), width, height,
                retractUp ? "向上" : "向下", getFacing());
        markDirty();
        syncToClient();
        return null;
    }

    /**
     * 拆除门板。
     *
     * @param removeControllerBlock 是否连控制器方块一起拆掉
     * @param reason                 给玩家/日志看的成因（必填）
     */
    public void removeDoor(boolean removeControllerBlock, String reason) {
        if (world == null || world.isRemote || removing) {
            return;
        }
        CGA.log("[{}, {}, {}] 拆 CGA 防爆门（{}×{}，{}）：{}",
                pos.getX(), pos.getY(), pos.getZ(), width, height,
                removeControllerBlock ? "连控制器一起" : "保留控制器",
                reason == null ? "★ 未说明成因" : reason);
        removing = true;
        try {
            for (int dy = 0; dy < height; dy++) {
                for (int dx = 0; dx < width; dx++) {
                    BlockPos cell = cellAt(dx, dy);
                    if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                        continue;
                    }
                    if (world.getBlockState(cell).getBlock() instanceof BlockCGABlastDoorPanel) {
                        world.setBlockState(cell, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
            this.built = false;
            this.wantOpen = false;
            this.openAmount = 0.0F;
            this.pendingClose = false;
            this.closeHeldAnnounced = false;
            this.cellBlockedReported = false;
            this.cellsDirty = false;
            this.cellRetryTicks = 0;
            if (removeControllerBlock) {
                world.setBlockToAir(pos);
            } else {
                refreshControllerState();
                markDirty();
                syncToClient();
            }
        } finally {
            removing = false;
        }
    }

    /**
     * 在建造前设置尺寸 / 收进方向 / 反向信号。
     * <p>已建造的门不能改几何（旧门板会变成孤儿）：想改就 {@link #removeDoor} 再 {@link #build}。
     */
    public void configure(int newWidth, int newHeight, boolean newRetractUp, boolean newInvert, EntityPlayer player) {
        this.width = clampSize(newWidth);
        this.height = clampSize(newHeight);
        this.retractUp = newRetractUp;
        this.invert = newInvert;
        markDirty();
        syncToClient();
        String text = "已设为 " + width + "×" + height + "（" + (retractUp ? "向上" : "向下") + "收"
                + (invert ? "，反向" : "") + "）" + (built ? "，请重新建造" : "");
        setStatus(text);
        notify(player, text);
    }

    // ---------------------------------------------------------------- 开 / 关

    /** 手动开关（手持木斧右键）。红石/自动化直接调 {@link #requestOpen}。 */
    public void toggleDoor(EntityPlayer player) {
        if (world == null || world.isRemote) {
            return;
        }
        if (!built) {
            notify(player, "这扇门还没建造（用木斧潜行右键设置）");
            return;
        }
        requestOpen(!wantOpen, player);
    }

    public void requestOpen(boolean desired, EntityPlayer player) {
        if (world == null || world.isRemote) {
            return;
        }
        if (!built) {
            setStatus("还没建造，先点「建造」");
            return;
        }
        if (desired == wantOpen) {
            pendingClose = false;
            return;
        }
        if (!desired && entitiesInside()) {
            pendingClose = true;
            setStatus("门里有实体，等它走开再关");
            notify(player, "门里有实体，等它走开再关");
            return;
        }
        pendingClose = false;
        this.wantOpen = desired;
        markDirty();
        syncToClient();
        setStatus(desired ? "门升起中…" : "门落下中…");
    }

    /** 推进一格平滑进度；返回 openAmount 是否跨到目标。 */
    private boolean stepMotion() {
        float target = wantOpen ? 1.0F : 0.0F;
        if (Math.abs(openAmount - target) <= 1e-4F) {
            openAmount = target;
            closeHeldAnnounced = false;
            return false;
        }
        if (!wantOpen && entitiesInside()) {
            if (!closeHeldAnnounced) {
                closeHeldAnnounced = true;
                setStatus("关门暂停：门里有实体，等它走开");
                CGA.log("[{}, {}, {}] 关门暂停（门里有实体）", pos.getX(), pos.getY(), pos.getZ());
            }
            return false;
        }
        closeHeldAnnounced = false;
        if (openAmount < target) {
            openAmount = Math.min(target, openAmount + SPEED_NORMAL);
        } else {
            openAmount = Math.max(target, openAmount - SPEED_NORMAL);
        }
        return true;
    }

    private static String format(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** 门体当前的滑动量（格）。向上收为正、向下收为负。 */
    public float getSlide() {
        return (retractUp ? 1.0F : -1.0F) * openAmount * Math.max(1, height);
    }

    /**
     * 第 dy 行这一格此刻实际被门体盖住的竖直区间（方块局部 0..1）。
     *
     * @return null = 这一格已经完全让开（没有碰撞）
     */
    public static float[] coveredRange(int dy, int height, float slide, boolean retractUp) {
        float c0;
        float c1;
        if (retractUp) {
            c0 = Math.max(0.0F, slide - dy);
            c1 = 1.0F;
        } else {
            c0 = 0.0F;
            c1 = Math.min(1.0F, slide + height - dy);
        }
        if (c1 - c0 <= 1e-4F) {
            return null;
        }
        return new float[]{c0, c1};
    }

    /**
     * 这一格此刻<b>本来就该是空气</b>吗（门自己在收格，而不是有人拆的）。
     *
     * @return true = 门体此刻没有盖着这一格（门自己收的格，别当成有人拆门板）
     */
    public boolean cellIsVacated(BlockPos cell) {
        if (!built) {
            return true;
        }
        if (cell == null) {
            return false;
        }
        return coveredRange(cell.getY() - pos.getY(), height, getSlide(), retractUp) == null;
    }

    /** 把当前滑动量落到门板方块的有无上 —— 门开到哪儿，方块消失到哪儿。 */
    private void applyCells() {
        if (world == null || world.isRemote) {
            return;
        }
        float slide = getSlide();
        boolean skippedForEntity = false;
        for (int dy = 0; dy < height; dy++) {
            boolean present = built && coveredRange(dy, height, slide, retractUp) != null;
            for (int dx = 0; dx < width; dx++) {
                BlockPos cell = cellAt(dx, dy);
                if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                    continue;
                }
                IBlockState current = world.getBlockState(cell);
                boolean isPanel = current.getBlock() instanceof BlockCGABlastDoorPanel;
                if (present) {
                    if (isPanel) {
                        continue;
                    }
                    if (!current.getBlock().isReplaceable(world, cell)) {
                        if (!cellBlockedReported) {
                            cellBlockedReported = true;
                            CGA.log("[{}, {}, {}] 门板长不回来：{} 被占着（那一格保持原样，仍然挡路）",
                                    pos.getX(), pos.getY(), pos.getZ(), format(cell));
                        }
                        continue;
                    }
                    if (cellHasEntity(cell)) {
                        skippedForEntity = true;
                        continue;
                    }
                    world.setBlockState(cell, panelState(), 3);
                } else if (isPanel) {
                    world.setBlockState(cell, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
                }
            }
        }
        if (skippedForEntity) {
            cellsDirty = true;
            if (++cellRetryTicks > CELL_RETRY_MAX) {
                cellsDirty = false;
                cellRetryTicks = 0;
                CGA.log("[{}, {}, {}] 关门后有门板补不回来：门框里一直有实体挡着", pos.getX(), pos.getY(), pos.getZ());
            }
        } else {
            cellsDirty = false;
            cellRetryTicks = 0;
        }
        refreshControllerState();
    }

    /** 这一格里有没有实体的碰撞盒。放方块前的兜底，宁可留个洞也不砌墙。 */
    private boolean cellHasEntity(BlockPos cell) {
        if (world == null) {
            return false;
        }
        double e = 0.001D;
        AxisAlignedBB box = new AxisAlignedBB(
                cell.getX() + e, cell.getY() + e, cell.getZ() + e,
                cell.getX() + 1.0D - e, cell.getY() + 1.0D - e, cell.getZ() + 1.0D - e);
        return !world.getEntitiesWithinAABB(Entity.class, box).isEmpty();
    }

    /** 刷新控制器那一格的方块状态（控制器不能变空气，只能靠 OPEN 让路）。 */
    private void refreshControllerState() {
        if (world == null || world.isRemote) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockCGABlastDoor)) {
            return;
        }
        boolean rowOpen = coveredRange(0, height, getSlide(), retractUp) == null;
        IBlockState wanted = state
                .withProperty(BlockCGABlastDoor.OPEN, rowOpen)
                .withProperty(BlockCGABlastDoor.RETRACT, retractUp);
        if (wanted != state) {
            world.setBlockState(pos, wanted, 3);
        }
    }

    /** 门里（整片门那一层）有没有实体 */
    private boolean entitiesInside() {
        if (world == null) {
            return false;
        }
        BlockPos far = cellAt(width - 1, height - 1);
        AxisAlignedBB box = new AxisAlignedBB(
                Math.min(pos.getX(), far.getX()), Math.min(pos.getY(), far.getY()),
                Math.min(pos.getZ(), far.getZ()),
                Math.max(pos.getX(), far.getX()) + 1.0D, Math.max(pos.getY(), far.getY()) + 1.0D,
                Math.max(pos.getZ(), far.getZ()) + 1.0D);
        return !world.getEntitiesWithinAABB(Entity.class, box).isEmpty();
    }

    private void setStatus(String text) {
        this.status = text == null ? "" : text;
        markDirty();
        syncToClient();
    }

    private void notify(EntityPlayer player, String text) {
        if (player != null && text != null && !text.isEmpty()) {
            player.sendStatusMessage(new TextComponentString(text), true);
        }
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // ---------------------------------------------------------------- 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote || removing) {
            return;
        }
        boolean powered = world.isBlockPowered(pos);
        if (!initialized) {
            initialized = true;
            lastPowered = powered;
            applyCells();
            return;
        }
        if (powered != lastPowered) {
            lastPowered = powered;
            requestOpen(invert ? !powered : powered, null);
        }
        if (pendingClose && built && !entitiesInside()) {
            pendingClose = false;
            requestOpen(false, null);
        }

        if (built) {
            boolean wasMoving = isMoving();
            boolean advanced = stepMotion();
            boolean justStopped = wasMoving && !isMoving();
            if (advanced || justStopped || cellsDirty) {
                applyCells();
            }
            if (wasMoving) {
                markDirty();
                syncToClient();
                if (justStopped) {
                    setStatus(wantOpen ? "已开门" : "已关门");
                }
            }
        }
    }

    // ---------------------------------------------------------------- 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.width = clampSize(compound.hasKey("Width") ? compound.getInteger("Width") : DEFAULT_WIDTH);
        this.height = clampSize(compound.hasKey("Height") ? compound.getInteger("Height") : DEFAULT_HEIGHT);
        this.retractUp = compound.getBoolean("Retract");
        this.invert = compound.getBoolean("Invert");
        this.built = compound.getBoolean("Built");
        this.wantOpen = compound.getBoolean("Open");
        this.pendingClose = compound.getBoolean("PendingClose");
        this.status = compound.getString("Status");
        this.openAmount = compound.hasKey("OpenAmount")
                ? clamp01(compound.getFloat("OpenAmount")) : (wantOpen ? 1.0F : 0.0F);
        if (!built) {
            this.wantOpen = false;
            this.openAmount = 0.0F;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Width", width);
        compound.setInteger("Height", height);
        compound.setBoolean("Retract", retractUp);
        compound.setBoolean("Invert", invert);
        compound.setBoolean("Built", built);
        compound.setBoolean("Open", wantOpen);
        compound.setBoolean("PendingClose", pendingClose);
        compound.setString("Status", status == null ? "" : status);
        compound.setFloat("OpenAmount", openAmount);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Width", width);
        tag.setInteger("Height", height);
        tag.setBoolean("Retract", retractUp);
        tag.setBoolean("Invert", invert);
        tag.setBoolean("Built", built);
        tag.setBoolean("Open", wantOpen);
        tag.setFloat("OpenAmount", openAmount);
        tag.setString("Status", status == null ? "" : status);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.width = clampSize(tag.hasKey("Width") ? tag.getInteger("Width") : DEFAULT_WIDTH);
        this.height = clampSize(tag.hasKey("Height") ? tag.getInteger("Height") : DEFAULT_HEIGHT);
        this.retractUp = tag.getBoolean("Retract");
        this.invert = tag.getBoolean("Invert");
        this.built = tag.getBoolean("Built");
        this.wantOpen = tag.getBoolean("Open");
        this.openAmount = tag.hasKey("OpenAmount")
                ? clamp01(tag.getFloat("OpenAmount")) : (wantOpen ? 1.0F : 0.0F);
        if (!animSeeded) {
            animSeeded = true;
            this.clientAnim = this.openAmount;
        }
        this.status = tag.getString("Status");
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    /** TESR 可见范围覆盖整扇门，避免门体滑出门框时消失。 */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        BlockPos far = pos.offset(getRight(), w - 1).up(h - 1);
        int minX = Math.min(pos.getX(), far.getX());
        int maxX = Math.max(pos.getX(), far.getX()) + 1;
        int minZ = Math.min(pos.getZ(), far.getZ());
        int maxZ = Math.max(pos.getZ(), far.getZ()) + 1;
        int minY = pos.getY() - h - 1;
        int maxY = pos.getY() + 2 * h + 1;
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
