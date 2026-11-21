package com.cga.block;

import com.cga.CGA;
import com.cga.tile.TileCGABlastDoor;
import com.cga.util.WandHandler;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * CGA 防爆门的<b>门板</b>：由 {@link BlockCGABlastDoor} 控制器生成，玩家拿不到它的物品。
 * <p>
 * 拆掉任意一格门板 = 拆掉整扇门，避免留下拆不掉的孤儿门板。
 * <p>
 * "门板消失"有两条来源必须分清：玩家/爆炸把门板拆了（→ 连拆整扇门）；
 * 门自己开门时把这一格收成空气（→ 什么都不做）。两者走同一个 {@code breakBlock} 回调，
 * 靠 {@link TileCGABlastDoor#cellIsVacated} 现场重探区分。
 */
public class BlockCGABlastDoorPanel extends Block {

    /** 是否已开门（历史兜底字段；"门板在不在"以方块本身的有无为准） */
    public static final PropertyBool OPEN = PropertyBool.create("open");
    /** 收进方向：true = 向上收，false = 向下收 */
    public static final PropertyBool RETRACT = PropertyBool.create("retract");

    public BlockCGABlastDoorPanel() {
        super(Material.IRON);
        setUnlocalizedName(CGA.MODID + ".blast_door_panel");
        setRegistryName(CGA.MODID, "blast_door_panel");
        // ★ 不加进创造模式物品栏：门板只能由控制器生成
        setHardness(50.0F);
        setResistance(BlockCGABlastDoor.BLAST_RESISTANCE);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(OPEN, Boolean.FALSE)
                .withProperty(RETRACT, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, OPEN, RETRACT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(OPEN, (meta & 1) != 0)
                .withProperty(RETRACT, (meta & 2) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = 0;
        if (state.getValue(OPEN)) {
            meta |= 1;
        }
        if (state.getValue(RETRACT)) {
            meta |= 2;
        }
        return meta;
    }

    // ------------------------------------------------------------------ 碰撞

    /**
     * 走连续碰撞：按门体真实位置求交，返回部分高度的盒子。
     * 门板没有方块实体，先在 7×7 范围内找控制器，再算自己是第几行。查不到退回整格兜底。
     */
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        if (worldIn instanceof World) {
            World world = (World) worldIn;
            BlockPos controller = BlockCGABlastDoor.findController(world, pos);
            if (controller != null) {
                TileCGABlastDoor te = BlockCGABlastDoor.getTile(world, controller);
                if (te != null && te.isBuilt()) {
                    int dy = pos.getY() - controller.getY();
                    float[] r = TileCGABlastDoor.coveredRange(dy, te.getHeight(), te.getSlide(), te.isRetractUp());
                    return r == null ? NULL_AABB
                            : new AxisAlignedBB(pos.getX(), pos.getY() + r[0], pos.getZ(),
                                                pos.getX() + 1, pos.getY() + r[1], pos.getZ() + 1);
                }
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

    /** 门板不该因为"材质是金属方块"而窒息（详见 HBMZZ 门板的同类修复）。 */
    @Override
    public boolean causesSuffocation(IBlockState state) {
        return false;
    }

    @Override
    public boolean canSilkHarvest(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
        return false;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            BlockPos controller = BlockCGABlastDoor.findController(worldIn, pos);
            if (controller != null) {
                TileCGABlastDoor tile = BlockCGABlastDoor.getTile(worldIn, controller);
                if (tile != null) {
                    // 先问"这一格是门自己收掉的吗"——是就别当有人拆门板
                    if (!tile.cellIsVacated(pos)) {
                        tile.removeDoor(true, "门板被拆：" + pos + " → 连带拆掉整扇门");
                    }
                }
            }
        }
        super.breakBlock(worldIn, pos, state);
    }

    /** 门板也吃"木斧右键开关门 / 潜行右键切换设置"（和控制器一样）。 */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (WandHandler.isWand(playerIn)) {
            BlockPos controller = BlockCGABlastDoor.findController(worldIn, pos);
            if (controller == null) {
                return false;
            }
            TileCGABlastDoor tile = BlockCGABlastDoor.getTile(worldIn, controller);
            if (tile == null) {
                return false;
            }
            if (playerIn.isSneaking()) {
                BlockCGABlastDoor.cycleConfig(worldIn, controller, playerIn);
                return true;
            }
            if (!worldIn.isRemote) {
                if (!tile.isBuilt()) {
                    String err = tile.build(playerIn);
                    if (err != null) {
                        playerIn.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation("chat.cga.door.build_fail", err), true);
                    }
                } else {
                    tile.toggleDoor(playerIn);
                }
            }
            return true;
        }
        if (playerIn.getHeldItemMainhand().isEmpty() && !worldIn.isRemote) {
            BlockCGABlastDoor.notifyNeedsAxe(playerIn);
        }
        return false;
    }
}
