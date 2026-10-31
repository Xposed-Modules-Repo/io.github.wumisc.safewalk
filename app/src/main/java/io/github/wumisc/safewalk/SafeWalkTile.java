package io.github.wumisc.safewalk;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/* loaded from: classes3.dex */
public final class SafeWalkTile extends TileService {
    @Override // android.service.quicksettings.TileService
    public void onStartListening() {
        Config.reload();
        refresh();
    }

    @Override // android.service.quicksettings.TileService
    public void onClick() {
        boolean on = SafeWalkCore.toggleWalkMode();
        refresh(on);
    }

    private void refresh(boolean on) {
        Tile t = getQsTile();
        t.setState(on ? 2 : 1);
        t.setLabel("行走路况");
        t.setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher));
        t.updateTile();
    }

    private void refresh() {
        Config.reload();
        refresh(Config.enable && Config.walkMode);
    }
}
