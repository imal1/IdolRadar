IdolRadar 图标交付 · 方案 2a 暗夜心动雷达
======================================

统一源图基于 1024px 主图；均为正方形满幅、去圆角，圆角/裁切由系统完成。

iOS/  —— 无 alpha，sRGB，满幅方图
  AppIcon-1024 ... 20         13 个独立尺寸切图
  AppIcon.appiconset/         可直接拖进 Xcode 的 Asset Catalog（含 Contents.json）
  AppStore-variants/          appstore_dark_1024 / appstore_light_1024 深浅背景变体

macOS/
  IdolRadar.icns              Mac 应用图标（内含 32/64/128/256/512/1024）

Android/  —— 自适应图标 108dp（@xxxhdpi 432px），中心 72dp 安全区
  ic_launcher_background_432  背景层（不透明）
  ic_launcher_foreground_432  前景层（透明，图案已收进安全区）
  ic_launcher_monochrome_432  单色层（Android 13+ 主题图标，系统上色）
  playstore_512               Play 商店图 512 满幅

WeChat-MiniProgram/  —— 微信小程序
  logo_144                    程序方图标 144（列表处平台圆形裁切）
  tabbar_normal_81            TabBar 未选中（灰 #9AA0A6）
  tabbar_active_81            TabBar 选中（粉 #C4526E）
