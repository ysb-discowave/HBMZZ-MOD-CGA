package com.cga.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;

/**
 * "手里拿着木斧吗"的唯一判据。
 * <p>
 * 沿用 HBMZZ 的约定：本地开关门只认木斧（左键不挖门、右键开关门、潜行右键开设置界面），
 * 空手右键不动门、只提示一句，免得玩家以为"门坏了"。
 */
public final class WandHandler {

    private WandHandler() {
    }

    public static boolean isWand(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        return player.getHeldItemMainhand().getItem() == Items.WOODEN_AXE;
    }
}
