# HBMZZ-MOD-CGA

HBMZZ 的独立分支 mod，目标是**还原 Roblox 核心游戏体验**。
当前首波内容：各种各样的**防爆门**（blast-proof doors）。其它玩法待定。

- modid：`cga`
- 名称：`HBMZZ-MOD-CGA`
- 目标版本：Minecraft 1.12.2 / Forge 14.23.5.x / Java 8
- 独立工程：不依赖 HBMZZ 主 mod，可单独安装

---

## 防爆门（Blast Door）

一扇可开关、可配置尺寸、抗爆炸的多格门。

### 组成

| 部件 | 说明 |
|------|------|
| **控制器**（`cga:blast_door`） | 门的左下角那一格，是唯一的"有物品"方块。带方块实体（`TileCGABlastDoor`）负责开门/关门动画、配置、红石。 |
| **门板**（`cga:blast_door_panel`） | 由控制器按尺寸生成 / 移除，**没有对应物品**，不会进创造栏、挖了也不掉落——从根上杜绝"孤儿门板"。 |

### 用法

1. 放下方块（默认 **3×3、向下收**）。
2. 手持**木斧**：
   - **右键** = 建造；已建造则开/关门。
   - **潜行 + 右键** = 在预设之间循环切换（尺寸 / 收进方向 / 反向信号），并播报当前配置。
   - 预设循环：`3×3↓ → 3×3↑ → 3×3↓反向 → 3×3↑反向 → 3×5↓ → 5×3↓ → …`
3. **红石信号**也能开/关门（可设反向信号）。
4. **空手右键**只会提示"需要木斧"，不会乱动门。

### 行为细节

- **开门动画**：`openAmount` 从 0..1 平滑推进（约 0.042/tick），门板按行逐格出现/消失，没有自定义 TESR 渲染器。
- **不夹人**：关门时若门片里有实体，先暂缓（pendingClose），等实体离开再合上。
- **抗爆**：控制器与门板爆炸抗性均为 **6000**（黑曜石是 1200），足以扛住大当量爆炸；但可正常挖掉/拆掉。
- **拆门板 = 拆整扇门**：拆掉任意一格门板会连带拆除整扇门（拆控制器则保留门板逻辑由控制器统一清掉）。
- **碰撞**：按门体真实位置做连续碰撞（`coveredRange`），门完全收起的那一行无碰撞盒。
- **门板故意没有物品**：拆了不掉落，避免玩家手里出现拿不走的残片。

### 方块状态（blockstate）

- 控制器：`facing`（水平朝向）× `open`（开合历史位）× `retract`（收进方向）→ 16 变体
- 门板：`open` × `retract` → 4 变体

---

## 工程结构

```
_hbmzz_400_cga/
├─ build.gradle / settings.gradle / gradle*        # 独立 Forge 工程
└─ src/main/
   ├─ java/com/cga/
   │  ├─ CGA.java            # @Mod 入口，preInit 显式注册方块 + 方块实体，init 做注册自检
   │  ├─ CGABlocks.java      # 方块/物品注册 + 反射自检 verifyRegistered()
   │  ├─ CGAItems.java       # 创造栏 CGATab + ItemBlock 工厂
   │  ├─ block/BlockCGABlastDoor.java          # 控制器
   │  ├─ block/BlockCGABlastDoorPanel.java     # 门板
   │  ├─ tile/TileCGABlastDoor.java            # 方块实体（动画/配置/红石/NBT/同步）
   │  ├─ util/WandHandler.java                 # isWand = 手持木斧
   │  ├─ common/CommonProxy.java
   │  └─ client/ClientProxy.java
   └─ resources/
      ├─ mcmod.info
      └─ assets/cga/
         ├─ blockstates/  ├─ models/{block,item}/  ├─ textures/block/  └─ lang/
```

## 约定（与 HBMZZ / rsaudio 一致）

- 注册不走注解注入：`preInit` 里 `MinecraftForge.EVENT_BUS.register(CGABlocks.class)`，`@SubscribeEvent` 接 `RegistryEvent`。
- 注册名在构造函数里**只设一次**（父类构造函数不再设，否则启动崩）。
- `init` 里跑 `verifyRegistered()`：反射扫"本类声明的方块"与注册表逐项比对，缺了就打日志。

## 待办 / 其它 Roblox 核心玩法

- 更多类型的防爆门（不同尺寸 / 材质 / 开合方式）
- 基础搭建与交互（Roblox 风格的方块/平台）
- 后续视需求再补 GUI 配置界面（当前用木斧潜行循环切换已够用）
