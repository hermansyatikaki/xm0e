package miku.moe.app;

import android.os.Build;

import miku.moe.app.api.AnxGuardInterceptor;

public final class AnxPlayerUa {

    private AnxPlayerUa() {}

    public static String userAgent() {
        String release = Build.VERSION.RELEASE;
        if (release == null || release.trim().isEmpty()) release = "14";
        return "Player Anime X Nonton v" + AnxGuardInterceptor.APP_VERSION_NAME + "/"
                + AnxGuardInterceptor.VERSION_CODE
                + " Emu:false PlayStore:true (Linux;Android " + release + ") AndroidXMedia3/1.11.1";
    }
}
