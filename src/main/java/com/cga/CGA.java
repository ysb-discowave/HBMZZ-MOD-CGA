/*
 * HBMZZ-MOD-CGA — a HBMZZ branch that recreates Roblox core gameplay.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.cga;

import com.cga.client.ClientProxy;
import com.cga.common.CommonProxy;
import com.cga.tile.TileCGABlastDoor;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.Logger;

/**
 * HBMZZ-MOD-CGA（CGA）。
 *
 * <p>HBMZZ 的一个分支：目标是逐步还原 Roblox 的核心玩法。本分支目前只落了第一块
 * 内容——<b>防爆门</b>（可开关、可配置尺寸、抗爆炸的多格门）。其余玩法（更多门型、
 * 基础搭建交互等）后续按需追加。</p>
 */
@Mod(modid = CGA.MODID, name = CGA.NAME, version = CGA.VERSION,
        acceptedMinecraftVersions = "[1.12,1.13)")
public class CGA {

    public static final String MODID = "cga";
    public static final String NAME = "HBMZZ-MOD-CGA";
    public static final String VERSION = "0.1.0";

    @Mod.Instance(MODID)
    public static CGA instance;

    @SidedProxy(clientSide = "com.cga.client.ClientProxy", serverSide = "com.cga.common.CommonProxy")
    public static CommonProxy proxy;

    public static Logger logger;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        try {
            java.security.CodeSource source = CGA.class.getProtectionDomain().getCodeSource();
            logger.info("HBMZZ-MOD-CGA {} loaded from {}", VERSION,
                    source == null ? "unknown source" : source.getLocation());
        } catch (Throwable ignored) {
            logger.info("HBMZZ-MOD-CGA {} loaded (source unavailable)", VERSION);
        }
        MinecraftForge.EVENT_BUS.register(CGABlocks.class);
        GameRegistry.registerTileEntity(TileCGABlastDoor.class,
                new ResourceLocation(MODID, "blast_door"));
        proxy.preInit(event);
        logger.info("HBMZZ-MOD-CGA loaded (blast doors).");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        int missing = CGABlocks.verifyRegistered();
        if (missing > 0) {
            logger.warn("CGA 注册自检发现 {} 项缺失（见上方日志）", missing);
        }
    }

    public static void log(String fmt, Object... args) {
        if (logger != null) {
            logger.info(fmt, args);
        }
    }
}
