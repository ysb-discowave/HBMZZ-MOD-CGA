package com.cga;

import com.cga.block.BlockCGABlastDoor;
import com.cga.block.BlockCGABlastDoorPanel;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CGA 方块注册 + 注册自检。
 * <p>
 * 由 {@link CGA#preInit} 显式 {@code MinecraftForge.EVENT_BUS.register(CGABlocks.class)}，
 * 不走注解注入（与 HBMZZ/rsaudio 同一约定，把这条链变成确定的）。
 *
 * <h3>★ 门板故意没有物品</h3>
 * {@link #BLAST_DOOR_PANEL} 只能由控制器生成、也只能由控制器移除，没有物品就不会进创造栏、
 * 挖掉也不掉落，从根上避免"孤儿门板"。
 */
public final class CGABlocks {

    private static boolean blocksRegistered;
    private static boolean itemsRegistered;

    /** 防爆门控制器（有物品） */
    public static final Block BLAST_DOOR = new BlockCGABlastDoor();
    /** 防爆门门板（无物品，由控制器生成/移除） */
    public static final Block BLAST_DOOR_PANEL = new BlockCGABlastDoorPanel();

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        if (blocksRegistered) {
            return;
        }
        blocksRegistered = true;
        event.getRegistry().register(BLAST_DOOR);
        event.getRegistry().register(BLAST_DOOR_PANEL);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<net.minecraft.item.Item> event) {
        if (itemsRegistered) {
            return;
        }
        itemsRegistered = true;
        event.getRegistry().register(com.cga.CGAItems.makeItemBlock(BLAST_DOOR));
        // 门板没有物品（见类注释）
    }

    // ------------------------------------------------------------------ 注册自检

    /** "本类声明了哪些方块"的唯一真源：反射扫 Block / Block[] 静态字段。 */
    private static List<Block> declaredBlocks() {
        List<Block> out = new ArrayList<Block>();
        for (Field field : CGABlocks.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            try {
                if (Block.class.isAssignableFrom(type)) {
                    Block block = (Block) field.get(null);
                    if (block != null) {
                        out.add(block);
                    }
                } else if (type.isArray() && Block.class.isAssignableFrom(type.getComponentType())) {
                    Block[] group = (Block[]) field.get(null);
                    if (group != null) {
                        Collections.addAll(out, group);
                    }
                }
            } catch (IllegalAccessException impossible) {
                // public static；真出问题也被下面 warn 兜住
            }
        }
        return out;
    }

    /** 注册自检：把"本类声明了"与"注册表里真的有"逐项比对，返回缺失条数。 */
    public static int verifyRegistered() {
        int missing = 0;
        for (Block block : declaredBlocks()) {
            ResourceLocation name = block.getRegistryName();
            if (name == null) {
                CGA.log("[注册自检] %s 没有 registry name，注册表不会收它", block.getClass().getSimpleName());
                missing++;
                continue;
            }
            if (ForgeRegistries.BLOCKS.getValue(name) != block) {
                CGA.log("[注册自检] 方块 %s 不在 BLOCKS 注册表里（registerBlocks 少了一行）", name);
                missing++;
            }
            if (block == BLAST_DOOR_PANEL) {
                continue; // 门板故意没有物品
            }
            if (ForgeRegistries.ITEMS.getValue(name) == null) {
                CGA.log("[注册自检] 方块 %s 有方块没物品（registerItems 少了一行）", name);
                missing++;
            }
        }
        return missing;
    }
}
