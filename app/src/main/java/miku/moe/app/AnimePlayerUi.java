package miku.moe.app;

import androidx.media3.common.Player;

public interface AnimePlayerUi {
    void attachPlayer(Player player);

    void release();

    void setTitle(String title);

    void setTitle(String animeTitle, String episodeLine);

    void setQualityLabel(String label);

    void setScaleLabel(String label);

    void setRotateState(boolean landscape);

    void setPopupMenuVisible(boolean visible);

    void setActionListener(AnimePlayerChrome.ActionListener listener);

    void setNavigationListener(AnimePlayerChrome.NavigationListener listener);

    void setAvailabilityProvider(AnimePlayerChrome.AvailabilityProvider provider);

    void updateNavigation(boolean playerHasPrevious, boolean playerHasNext);

    void showChoiceMenu(String[] labels, int checkedIndex, java.util.function.IntConsumer onPick, Runnable onClosed);
}
