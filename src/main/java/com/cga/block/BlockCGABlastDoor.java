package com.cga.block;

import com.cga.CGA;
import com.cga.CGAItems;
import com.cga.tile.TileCGABlastDoor;
import com.cga.util.WandHandler;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * CGA 防爆门的<b>控制器</b>（同时是门的左下角那一格）。
 *
 * <h3>用法</h3>
 * <ol>
 *   <li>放下方块（默认 3×3、向下收）。</li>
 *   <li>手持木斧<b>潜行 + 右键</b>循环切换尺寸 / 收进方向 / 反向信号。</li>
 *   <li>手持木斧右键 = 建造 / 开关门（先建造，再开合）。</li>
 *   <li>红石信号同样能开门（可反向）；关门时如果门里有实体就先等一等，不夹人。</li>
 * </ol>
 *
 * <h3>抗爆</h3>
 * 控制器和门板的爆炸抗性都是 {@code 6000}（黑曜石是 1200）——足以扛住大当量爆炸，
 * 但仍然可以被正常挖掉/拆掉，不会留下拆不掉的残骸。
 * <p>
 * 门板没有对应的物品：它只能由控制器生成，拆掉任意一格门板会连带拆掉整扇门。
 */
public class BlockCGABlastDoor extends Block implements ITileEntityProvider {

    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    public static final PropertyBool OPEN = PropertyBool.create("open");
    public static final PropertyBool RETRACT = PropertyBool.create("retract");

    public static final float BLAST_RESISTANCE = 6000.0F;

    public BlockCGABlastDoor() {
        super(Material.IRON);
        // ★ 注册名只在这里用字面量设一次（与 HBMZZ/rsaudio 约定一致：
        //   父类构造函数不能再设一次，否则启动即崩）。
        setUnlocalizedName(CGA.MODID + ".blast_door");
        setRegistryName(CGA.MODID, "blast_door");
        setCreativeTab(CGAItems.TAB);
        setHardness(50.0F);
        setResistance(BLAST_RESISTANCE);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(OPEN, Boolean.FALSE)
                .withProperty(RETRACT, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, OPEN, RETRACT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(OPEN, (meta & 4) != 0)
                .withProperty(RETRACT, (meta & 8) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(OPEN)) {
            meta |= 4;
        }
        if (state.getValue(RETRACT)) {
            meta |= 8;
        }
        return meta;
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        // 门的正面朝玩家
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    // ------------------------------------------------------------------ 碰撞

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        if (worldIn instanceof World) {
            TileCGABlastDoor te = getTile((World) worldIn, pos);
            if (te != null && te.isBuilt()) {
                float[] r = TileCGABlastDoor.coveredRange(0, te.getHeight(), te.getSlide(), te.isRetractUp());
                return r == null ? NULL_AABB
                        : new AxisAlignedBB(pos.getX(), pos.getY() + r[0], pos.getZ(),
                                            pos.getX() + 1, pos.getY() + r[1], pos.getZ() + 1);
            }
        }
        return state.getValue(OPEN) ? NULL_AABB : FULL_BLOCK_AABB.offset(pos);
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean causesSuffocation(IBlockState state) {
        return false;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileCGABlastDoor();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileCGABlastDoor();
    }

    public static TileCGABlastDoor getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileCGABlastDoor ? (TileCGABlastDoor) te : null;
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 手持木斧右键 = 开 / 关门（没建造时先建造）；手持木斧潜行右键 = 循环切换设置。
     * 空手右键不动门，只提示一句。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (WandHandler.isWand(playerIn)) {
            if (playerIn.isSneaking()) {
                cycleConfig(worldIn, pos, playerIn);
                return true;
            }
            if (!worldIn.isRemote) {
                TileCGABlastDoor tile = getTile(worldIn, pos);
                if (tile != null) {
                    if (!tile.isBuilt()) {
                        String err = tile.build(playerIn);
                        if (err != null) {
                            playerIn.sendStatusMessage(new TextComponentTranslation("chat.cga.door.build_fail", err), true);
                        }
                    } else {
                        tile.toggleDoor(playerIn);
                    }
                }
            }
            return true;
        }
        if (playerIn.getHeldItemMainhand().isEmpty() && !worldIn.isRemote) {
            notifyNeedsAxe(playerIn);
        }
        return false;
    }

    /**
     * 潜行右键：在几个预设之间循环切换（尺寸 / 收进方向 / 反向），并播报当前配置。
     * <p>循环：3×3↓ → 3×3↑ → 反向关 → 反向开 → 3×5↓ → …（预设表见下）。
     * 这样不用 GUI 也能把常用配置都调到，后续若加 GUI 也不冲突。
     */
    /** 门板也用它（门面是一整扇，玩家分不清哪格是控制器）。 */
    public static void cycleConfig(World worldIn, BlockPos pos, EntityPlayer playerIn) {
        TileCGABlastDoor tile = getTile(worldIn, pos);
        if (tile == null) {
            return;
        }
        int w = tile.getWidth();
        int h = tile.getHeight();
        boolean up = tile.isRetractUp();
        boolean inv = tile.isInvert();
        // 预设表（顺序即循环顺序）
        int[][] sizes = new int[][]{{3, 3}, {3, 3}, {3, 3}, {3, 3}, {3, 5}, {5, 3}};
        boolean[] ups = new boolean[]{false, true, false, true, false, false};
        boolean[] invs = new boolean[]{false, false, true, true, false, false};
        int idx = -1;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i][0] == w && sizes[i][1] == h && ups[i] == up && invs[i] == inv) {
                idx = i;
                break;
            }
        }
        int next = (idx + 1) % sizes.length;
        tile.configure(sizes[next][0], sizes[next][1], ups[next], invs[next], playerIn);
    }

    /** 「本地开关门只能用木斧」这条规则给玩家看的说法。 */
    public static void notifyNeedsAxe(EntityPlayer player) {
        if (player != null) {
            player.sendStatusMessage(new TextComponentTranslation("chat.cga.door.need_axe"), true);
        }
    }

    // ------------------------------------------------------------------ 被拆掉

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            TileCGABlastDoor tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.removeDoor(false, "控制器方块被拆（玩家或爆炸）");
            }
        }
        super.breakBlock(worldIn, pos, state);
    }

    /** 找"覆盖了某个位置"的控制器（在门最大尺寸 7×7 范围内扫）。 */
    public static BlockPos findController(World world, BlockPos from) {
        for (int dy = 0; dy <= TileCGABlastDoor.MAX_SIZE; dy++) {
            for (int dx = -TileCGABlastDoor.MAX_SIZE; dx <= TileCGABlastDoor.MAX_SIZE; dx++) {
                for (int dz = -TileCGABlastDoor.MAX_SIZE; dz <= TileCGABlastDoor.MAX_SIZE; dz++) {
                    BlockPos candidate = from.add(dx, -dy, dz);
                    if (!world.isBlockLoaded(candidate)) {
                        continue;
                    }
                    if (!(world.getBlockState(candidate).getBlock() instanceof BlockCGABlastDoor)) {
                        continue;
                    }
                    TileCGABlastDoor tile = getTile(world, candidate);
                    if (tile != null && tile.contains(from)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, net.minecraft.client.util.ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.cga.door.1"));
        tooltip.add(I18n.translateToLocal("tooltip.cga.door.2"));
        tooltip.add(I18n.translateToLocal("tooltip.cga.door.3"));
    }
}
