package com.cga.client;

import com.cga.common.CommonProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * 客户端代理。
 * <p>
 * CGA 防爆门用<b>方块模型</b>渲染（门板逐行长出来 / 收回去，控制器那格靠 OPEN 属性切换
 * 开/关两张贴图），不需要 TESR / 实体渲染器，所以这里暂时没有客户端要注册的东西。
 * 保留本类只为 {@code @SidedProxy} 在客户端侧有落点；后续加 GUI / 渲染器时在这里扩展。
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }
}
