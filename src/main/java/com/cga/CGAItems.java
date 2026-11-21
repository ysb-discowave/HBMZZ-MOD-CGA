package com.cga;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * CGA 创造栏与方块物品工厂。
 * <p>
 * 创造栏沿用 HBMZZ/rsaudio 的做法：方块/物品通过 {@code setCreativeTab(TAB)} 挂进来，
 * 由 Forge 自动收集进栏，不需要手写清单。
 */
public final class CGAItems {

    public static final CGATab TAB = new CGATab();

    private CGAItems() {
    }

    /** 给方块造一个 ItemBlock 并挂到 CGA 创造栏。注册名沿用方块的 registry name。 */
    public static ItemBlock makeItemBlock(Block block) {
        ItemBlock item = new ItemBlock(block);
        item.setRegistryName(block.getRegistryName());
        item.setCreativeTab(TAB);
        return item;
    }

    /** CGA 的创造模式物品栏。 */
    public static final class CGATab extends CreativeTabs {

        public CGATab() {
            super(CGA.MODID);
        }

        /** 1.12.2 里创造模式物品栏的图标方法叫 getTabIconItem（1.13+ 才改名 createIcon） */
        @Override
        @SideOnly(Side.CLIENT)
        public ItemStack getTabIconItem() {
            return new ItemStack(CGABlocks.BLAST_DOOR);
        }
    }
}
