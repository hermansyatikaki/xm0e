package miku.moe.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

public abstract class BaseAnimeLoverzGridFragment extends Fragment {
    private static final String TAG = "AnimeLoverzGrid";
    private static final int PAGE_SIZE = 20;
    private static final int PREFETCH_DISTANCE = 6;
    // Nama genre persis seperti parameter ?genre= di API baru (diverifikasi dari HAR: genre=Comedy).
    private static final String[] GENRE_LABELS = new String[] {
            "Action", "Adult Cast", "Adventure", "Animation", "Avant Garde", "Award Winning", "Blood", "Boys Love",
            "Comedy", "Crossdressing", "Cultivation", "Demons", "Drama", "Ecchi", "Erotica", "Fantasy", "Game",
            "Girls Love", "Gourmet", "Guoman", "Harem", "Historical", "Horror", "Isekai", "Iyashikei", "Josei", "Life",
            "Love Polygon", "Magic", "Mahou Shoujo", "Martial Arts", "Mecha", "Medical", "Military", "Music", "Mystery",
            "Mythology", "Otaku Culture", "Performing Arts", "Psychological", "Racing", "Reincarnation", "Romance",
            "School", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice Of Life", "Space", "Sports", "Strategy Game",
            "Super Power", "Supernatural", "Suspense", "Team Sports", "Thriller", "Time Travel", "Tokusatsu", "Vampire",
            "Villainess", "Visual Arts", "Youth"
    };

    protected GridView gridView;
    protected ProgressBar progressBar;
    protected TextView titleTextView;
    protected TextInputLayout searchInputLayout;
    protected TextInputEditText searchEditText;
    protected HorizontalScrollView genreScrollView;
    protected TextView searchHelperTextView;
    protected TabLayout homeTabLayout;
    protected View searchHeaderCard;
    protected ImageView genreFilterButton;
    protected final ArrayList<AnimePost> animePosts = new ArrayList<>();
    protected AnimeGridAdapter adapter;
    protected int currentPage = 0;
    protected boolean isLoading = false;
    protected boolean hasMoreData = true;

    private final HashSet<String> loadedKeys = new HashSet<>();
    private java.util.concurrent.ExecutorService executor; // dibuat per-view: fragment bisa melewati onDestroyView -> onViewCreated lagi
    private int loadGeneration = 0;
    private String lastQuery = "";
    private String selectedCategory = "all";
    private String selectedGenre = "";

    protected abstract String screenTitle();
    protected String initialQuery() { return ""; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(requireActivity() instanceof AnimexAll ? R.layout.fragment_ikiru_manga_grid : R.layout.fragment_anime_grid, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        gridView = view.findViewById(R.id.gridView);
        ViewCompat.setNestedScrollingEnabled(gridView, true);
        progressBar = view.findViewById(R.id.progressBar);
        titleTextView = view.findViewById(R.id.titleTextView);
        if (requireActivity() instanceof AnimexAll) {
            titleTextView.setVisibility(View.GONE);
            TextView subtitle = view.findViewById(R.id.headerSubtitleTextView);
            if (subtitle != null) subtitle.setVisibility(View.GONE);
        }
        searchInputLayout = view.findViewById(R.id.searchInputLayout);
        searchEditText = view.findViewById(R.id.searchEditText);
        genreScrollView = view.findViewById(R.id.genreScrollView);
        searchHelperTextView = view.findViewById(R.id.searchHelperTextView);
        homeTabLayout = view.findViewById(R.id.homeTabLayout);
        searchHeaderCard = view.findViewById(R.id.searchHeaderCard);
        genreFilterButton = view.findViewById(R.id.genreFilterButton);
        titleTextView.setText(screenTitle());
        if (searchHeaderCard != null) searchHeaderCard.setVisibility(View.VISIBLE);
        searchInputLayout.setVisibility(View.VISIBLE);
        searchInputLayout.setHint(screenTitle());
        searchEditText.setHint("Cari anime");
        if (genreScrollView != null) genreScrollView.setVisibility(View.GONE);
        if (searchHelperTextView != null) searchHelperTextView.setVisibility(View.GONE);
        adapter = new AnimeGridAdapter(requireContext(), animePosts, this::openAnime);
        gridView.setAdapter(adapter);
        setupInfiniteScroll();
        setupSearchUi();
        setupHomeTabs();
        if (applyInitialQueryIfNeeded()) return;
        if (animePosts.isEmpty()) fetchCategoryData("all", 1, true);
    }

    @Override
    public void onDestroyView() {
        loadGeneration++; // hasil request yang masih berjalan tidak boleh menyentuh view yang sudah dilepas
        if (executor != null) executor.shutdownNow();
        if (gridView != null) gridView.setAdapter(null);
        super.onDestroyView();
    }

    private void setupInfiniteScroll() {
        gridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}
            @Override public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (totalItemCount == 0 || visibleItemCount == 0) return;
                boolean reachedBottom = firstVisibleItem + visibleItemCount >= totalItemCount - PREFETCH_DISTANCE;
                if (reachedBottom && !isLoading && hasMoreData) loadNextPage();
            }
        });
    }

    private void requestNextPageIfNeeded() {
        if (!isAdded() || getView() == null || isLoading || !hasMoreData || gridView == null || adapter == null) return;
        int total = adapter.getCount();
        if (total <= 0) return;
        int lastVisible = gridView.getLastVisiblePosition();
        if (lastVisible < 0) return;
        if (lastVisible >= total - PREFETCH_DISTANCE) loadNextPage();
    }

    private void requestNextPageAfterRender() {
        if (!hasMoreData || gridView == null) return;
        gridView.post(this::requestNextPageIfNeeded);
    }

    private void setupSearchUi() {
        setupGenreFilterMenu();
        searchInputLayout.setEndIconOnClickListener(v -> startSearch());
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch();
                return true;
            }
            return false;
        });
        if (!lastQuery.isEmpty()) searchEditText.setText(lastQuery);
    }

    private void setupHomeTabs() {
        if (homeTabLayout == null) return;
        homeTabLayout.setVisibility(View.VISIBLE);
        ViewGroup.MarginLayoutParams lp = homeTabLayout.getLayoutParams() instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) homeTabLayout.getLayoutParams() : null;
        if (lp != null) {
            lp.topMargin = 0;
            homeTabLayout.setLayoutParams(lp);
        }
        if (homeTabLayout.getTabCount() == 0) {
            addTab("Terbaru", "all");
            addTab("Baru Upload", "new");
            addTab("Donghua", "donghua");
            addTab("Movie", "movie");
            homeTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    Object tag = tab.getTag();
                    String value = tag == null ? "all" : tag.toString();
                    if (value.equals(selectedCategory) && animePosts.size() > 0 && lastQuery.isEmpty() && selectedGenre.isEmpty()) return;
                    selectedCategory = value;
                    selectedGenre = "";
                    lastQuery = "";
                    searchEditText.setText("");
                    updateGenreFilterButtonState();
                    resetPagingState();
                    fetchCategoryData(selectedCategory, 1, true);
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
        for (int i = 0; i < homeTabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = homeTabLayout.getTabAt(i);
            Object tag = tab == null ? null : tab.getTag();
            if (selectedCategory.equals(tag == null ? "all" : tag.toString())) {
                if (!tab.isSelected()) tab.select();
                break;
            }
        }
    }

    private void addTab(String label, String value) {
        TabLayout.Tab tab = homeTabLayout.newTab().setText(label);
        tab.setTag(value);
        homeTabLayout.addTab(tab);
    }

    private void setupGenreFilterMenu() {
        if (genreFilterButton == null) return;
        genreFilterButton.setVisibility(View.VISIBLE);
        genreFilterButton.setOnClickListener(v -> showGenreFilterMenu());
        updateGenreFilterButtonState();
    }

    private void showGenreFilterMenu() {
        if (genreFilterButton == null || !isAdded()) return;
        PopupMenu popupMenu = new PopupMenu(requireContext(), genreFilterButton);
        android.view.MenuItem all = popupMenu.getMenu().add(0, 0, 0, "Semua");
        all.setCheckable(true);
        all.setChecked(selectedGenre.isEmpty());
        for (int i = 0; i < GENRE_LABELS.length; i++) {
            android.view.MenuItem item = popupMenu.getMenu().add(0, i + 1, i + 1, GENRE_LABELS[i]);
            item.setCheckable(true);
            item.setChecked(GENRE_LABELS[i].equalsIgnoreCase(selectedGenre));
        }
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 0) {
                selectedGenre = "";
                updateGenreFilterButtonState();
                resetPagingState();
                if (!lastQuery.isEmpty()) fetchSearchData(lastQuery, 1, true); else fetchCategoryData(selectedCategory.isEmpty() ? "all" : selectedCategory, 1, true);
                return true;
            }
            int index = item.getItemId() - 1;
            if (index >= 0 && index < GENRE_LABELS.length) {
                selectedGenre = GENRE_LABELS[index];
                lastQuery = "";
                searchEditText.setText("");
                updateGenreFilterButtonState();
                resetPagingState();
                fetchGenreData(selectedGenre, 1, true);
            }
            return true;
        });
        popupMenu.show();
    }

    private void updateGenreFilterButtonState() {
        if (genreFilterButton == null || getView() == null) return;
        boolean active = selectedGenre != null && !selectedGenre.trim().isEmpty();
        int color = MaterialColors.getColor(getView(), active ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant);
        genreFilterButton.setColorFilter(color);
        genreFilterButton.setContentDescription(active ? "Filter genre aktif" : "Filter genre");
    }

    private boolean applyInitialQueryIfNeeded() {
        String startQuery = initialQuery() == null ? "" : initialQuery().trim();
        if (startQuery.isEmpty() || !lastQuery.isEmpty()) return false;
        lastQuery = startQuery;
        selectedGenre = "";
        searchEditText.setText(startQuery);
        searchEditText.setSelection(searchEditText.getText() == null ? 0 : searchEditText.getText().length());
        updateGenreFilterButtonState();
        resetPagingState();
        fetchSearchData(startQuery, 1, true);
        return true;
    }

    private void startSearch() {
        String query = searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), "Masukkan judul anime", Toast.LENGTH_SHORT).show();
            return;
        }
        lastQuery = query;
        selectedGenre = "";
        updateGenreFilterButtonState();
        resetPagingState();
        fetchSearchData(query, 1, true);
    }

    private void loadNextPage() {
        int nextPage = Math.max(1, currentPage + 1);
        if (!lastQuery.trim().isEmpty()) fetchSearchData(lastQuery, nextPage, false);
        else if (!selectedGenre.trim().isEmpty()) fetchGenreData(selectedGenre, nextPage, false);
        else fetchCategoryData(selectedCategory.isEmpty() ? "all" : selectedCategory, nextPage, false);
    }

    private void resetPagingState() {
        currentPage = 0;
        hasMoreData = true;
        loadedKeys.clear();
        animePosts.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** Pemuat satu halaman; dijalankan di thread background. */
    private interface PageLoader {
        AnimeLoverzApi.Page load() throws Exception;
    }

    private void fetchCategoryData(final String category, final int page, final boolean reset) {
        launchLoad(page, reset, null, new PageLoader() {
            @Override public AnimeLoverzApi.Page load() throws Exception {
                if ("new".equals(category)) return AnimeLoverzApi.series(page, PAGE_SIZE, null, null, null, null, "id", "desc");
                if ("donghua".equals(category)) return AnimeLoverzApi.series(page, PAGE_SIZE, null, null, "Donghua", null, "updated", "desc");
                if ("movie".equals(category)) return AnimeLoverzApi.movie(page, PAGE_SIZE);
                return AnimeLoverzApi.ongoing(page, PAGE_SIZE, "all");
            }
        });
    }

    private void fetchGenreData(final String genre, final int page, final boolean reset) {
        launchLoad(page, reset, "Genre ini belum memiliki anime", new PageLoader() {
            @Override public AnimeLoverzApi.Page load() throws Exception {
                return AnimeLoverzApi.series(page, PAGE_SIZE, null, genre.trim(), null, null, "updated", "desc");
            }
        });
    }

    private void fetchSearchData(final String query, final int page, final boolean reset) {
        launchLoad(page, reset, "Anime tidak ditemukan", new PageLoader() {
            @Override public AnimeLoverzApi.Page load() throws Exception {
                return AnimeLoverzApi.series(page, PAGE_SIZE, query.trim(), null, null, null, null, null);
            }
        });
    }

    /**
     * Request terbaru selalu menang: hasil request lama (mis. user ganti tab saat masih memuat) dibuang lewat
     * loadGeneration. Dulu request baru DIBUANG kalau masih loading sehingga grid kosong tanpa fetch.
     */
    private void launchLoad(final int page, final boolean reset, final String emptyMessage, final PageLoader loader) {
        final int generation = ++loadGeneration;
        isLoading = true;
        showLoading(true);
        executor.execute(new Runnable() {
            @Override public void run() {
                AnimeLoverzApi.Page result = null;
                Exception failure = null;
                try {
                    result = loader.load();
                } catch (Exception e) {
                    failure = e;
                }
                final AnimeLoverzApi.Page loaded = result;
                final Exception error = failure;
                android.app.Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isAdded() || getView() == null || generation != loadGeneration) return;
                        try {
                            if (error != null) {
                                Log.e(TAG, "Gagal memuat Animeloverz", error);
                                showToast(error instanceof AnimeLoverzApi.ApiException && !((AnimeLoverzApi.ApiException) error).isNotFound()
                                        ? "Animeloverz: " + error.getMessage() : "Kesalahan jaringan Animeloverz");
                                return;
                            }
                            appendPosts(loaded.posts, page, reset, loaded.hasNext);
                            if (animePosts.isEmpty() && emptyMessage != null) showToast(emptyMessage);
                        } finally {
                            finishLoading();
                        }
                    }
                });
            }
        });
    }

    private void appendPosts(ArrayList<AnimePost> posts, int page, boolean reset, boolean next) {
        if (reset) {
            animePosts.clear();
            loadedKeys.clear();
        }
        if (posts == null || posts.isEmpty()) {
            hasMoreData = false;
            adapter.notifyDataSetChanged();
            return;
        }
        int added = 0;
        for (AnimePost post : posts) {
            if (post == null) continue;
            if (!loadedKeys.add(dedupeKey(post))) continue;
            animePosts.add(post);
            added++;
        }
        currentPage = page;
        hasMoreData = next && added > 0;
        adapter.notifyDataSetChanged();
        requestNextPageAfterRender();
    }

    private String dedupeKey(AnimePost post) {
        if (post == null) return "";
        String slug = post.slug == null ? "" : post.slug.trim();
        while (slug.endsWith("/")) slug = slug.substring(0, slug.length() - 1);
        if (!slug.isEmpty()) return slug.toLowerCase(Locale.US);
        return String.valueOf(post.categoryId);
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value == null ? "" : value.replace(" ", "%20");
        }
    }

    private void openAnime(AnimePost post) {
        if (post == null) return;
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).openAnimeLoverzDetail(post.slug, post.categoryName, post.imgUrl, post.genre, post.rating, post.statusVideo, post.description);
        } else if (requireActivity() instanceof AnimexAll) {
            ((AnimexAll) requireActivity()).openAnimeLoverzDetail(post.slug, post.categoryName, post.imgUrl, post.genre, post.rating, post.statusVideo, post.description);
        }
    }

    protected void showToast(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void showLoading(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void finishLoading() {
        isLoading = false;
        showLoading(false);
    }

}
