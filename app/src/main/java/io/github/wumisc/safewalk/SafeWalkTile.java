package io.github.wumisc.safewalk;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷磁贴：点击切换"行走模式"（开启后置摄像头路况）。
 */
public final class SafeWalkTile extends TileService {

    @Override
    public void onStartListening() {
        Config.reload();
        refresh();
    }

    @Override
    public void onClick() {
        boolean on = SafeWalkCore.toggleWalkMode();
        refresh(on);
    }

    private void refresh(boolean on) {
        Tile t = getQsTile();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("行走路况");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_launcher));
        t.updateTile();
    }

    private void refresh() {
        Config.reload();
        refresh(Config.enable && Config.walkMode);
    }
}
