package com.xigaigai.syncglance;

import android.app.Application;

import com.xigaigai.syncglance.core.SyncCore;

/**
 * 应用入口。
 *
 * 只做一件事：把 {@link SyncCore} 尽早初始化。
 * 这样即使 SDL 服务还没起来（甚至因为没连车机而退出），
 * 数据采集与车机屏幕预览也能独立工作 —— 这是把预览器从连接层解耦的前提。
 *
 * 将来把预览器抽成独立库时，宿主 App 只要在自己的 Application 里调一次
 * {@code SyncCore.get().init(this)} 即可。
 */
public class SyncGlanceApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SyncCore.get().init(this);
    }
}
