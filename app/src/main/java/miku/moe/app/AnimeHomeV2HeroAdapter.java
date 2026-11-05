package miku.moe.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;

public class AnimeHomeV2HeroAdapter extends RecyclerView.Adapter<AnimeHomeV2HeroAdapter.Holder> {
    public interface Listener {
        void onClick(AnimePost post);
    }

    public static final int MAX_ITEMS = 5;
    private final Context context;
    private final ArrayList<AnimePost> data;
    private final Listener listener;
    private boolean skeleton;

    public AnimeHomeV2HeroAdapter(Context context, ArrayList<AnimePost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setSkeleton(boolean skeleton) {
        this.skeleton = skeleton;
    }

    private boolean isSkeletonPosition(int position) {
        return position >= Math.min(data.size(), MAX_ITEMS);
    }

    @Override public long getItemId(int position) {
        if (isSkeletonPosition(position)) return Long.MIN_VALUE + 2000L + position;
        return AnimeHomeV2Labels.itemKey(data.get(position)).hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_home_v3_hero_item, parent, false);
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(240), screenWidth - dp(42));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(dp(5), 0, dp(5), 0);
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (isSkeletonPosition(position)) {
            bindSkeleton(holder);
            return;
        }
        AnimePost post = data.get(position);
        ShimmerUtil.hide(holder.title);
        holder.read.setVisibility(View.VISIBLE);
        holder.read.setText("Tonton Sekarang");
        holder.read.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        holder.title.setText(clean(post.categoryName));
        MangaTitleStyle.apply(holder.title, context);
        bindGenre(holder, post);
        bindEpisode(holder, post);
        bindCover(holder, post);
        bindSynopsis(holder, post);
        bindStatus(holder, post);
        bindBookmark(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
    }

    private void bindSkeleton(Holder holder) {
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
        holder.read.setOnClickListener(null);
        holder.read.setVisibility(View.GONE);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.progress.setVisibility(View.GONE);
        holder.bookmark.setOnClickListener(null);
        holder.bookmark.setVisibility(View.GONE);
        holder.statusGroup.setVisibility(View.GONE);
        AnimeImageLoader.clear(holder.image);
        ShimmerUtil.show(holder.synopsis, 0, 30, 6f, 0.9f);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 26, 8f, 0.7f);
        ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.45f);
        ShimmerUtil.show(holder.chapter, 96, 32, 12f, 1f);
    }

    private void bindEpisode(Holder holder, AnimePost post) {
        ShimmerUtil.hide(holder.chapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        String episode = AnimeHomeV2Labels.episodeText(context, post);
        MangaLabelUtils.bindChapter(holder.chapter, episode, context, false);
        if (holder.chapter.getVisibility() == View.VISIBLE && listener != null) {
            holder.chapter.setClickable(true);
            holder.chapter.setFocusable(true);
            holder.chapter.setOnClickListener(v -> listener.onClick(post));
        }
    }

    private void bindGenre(Holder holder, AnimePost post) {
        ShimmerUtil.hide(holder.genre);
        AnimeHomeV2Resolver.applyCached(post);
        boolean needs = AnimeHomeV2Resolver.needsResolve(post);
        if (needs) {
            AnimeHomeV2Resolver.request(post, resolved -> {
                int index = data.indexOf(resolved);
                if (index >= 0) notifyItemChanged(index);
            });
        }
        String text = AnimeHomeV2Labels.genreText(post);
        if (!text.isEmpty()) {
            holder.genre.setText(text);
            holder.genre.setVisibility(View.VISIBLE);
        } else if (needs && AnimeHomeV2Resolver.isPending(post)) {
            ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.45f);
        } else {
            holder.genre.setText("");
            holder.genre.setVisibility(View.GONE);
        }
    }

    private void bindSynopsis(Holder holder, AnimePost post) {
        ShimmerUtil.hide(holder.synopsis);
        String text = post.description == null ? "" : post.description.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("null")) {
            holder.synopsis.setText("");
            holder.synopsis.setVisibility(View.GONE);
        } else {
            holder.synopsis.setText(text);
            holder.synopsis.setVisibility(View.VISIBLE);
        }
    }

    private void bindStatus(Holder holder, AnimePost post) {
        String status = AnimeHomeV2Labels.statusText(post);
        if (status.isEmpty()) {
            holder.statusGroup.setVisibility(View.GONE);
            return;
        }
        holder.status.setText(status);
        holder.statusDot.setBackgroundTintList(ColorStateList.valueOf(MangaHomeV3Labels.statusColor(status)));
        holder.statusGroup.setVisibility(View.VISIBLE);
    }

    private void bindBookmark(Holder holder, AnimePost post) {
        holder.bookmark.setVisibility(View.VISIBLE);
        applyBookmarkIcon(holder, post);
        holder.bookmark.setOnClickListener(v -> {
            FavoriteManager.toggle(context, post);
            applyBookmarkIcon(holder, post);
        });
    }

    private void applyBookmarkIcon(Holder holder, AnimePost post) {
        boolean fav = FavoriteManager.isFavorite(context, post.sourceId, post.categoryId, post.slug);
        holder.bookmark.setImageResource(fav ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark_border);
        int attr = fav
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant;
        holder.bookmark.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(holder.bookmark, attr)));
    }

    private void bindCover(Holder holder, AnimePost post) {
        holder.progress.setVisibility(View.GONE);
        AnimeImageLoader.loadShimmerCover(holder.image, post.imgUrl);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    @Override public int getItemCount() {
        int real = Math.min(data.size(), MAX_ITEMS);
        return real == 0 && skeleton ? 1 : real;
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.genre);
        ShimmerUtil.hide(holder.chapter);
        ShimmerUtil.hide(holder.synopsis);
        holder.bookmark.setOnClickListener(null);
        holder.statusGroup.setVisibility(View.GONE);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        holder.progress.setVisibility(View.GONE);
        holder.image.animate().cancel();
        AnimeImageLoader.clear(holder.image);
        super.onViewRecycled(holder);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final ProgressBar progress;
        final TextView title;
        final TextView genre;
        final TextView chapter;
        final TextView read;
        final TextView synopsis;
        final ImageView bookmark;
        final View statusGroup;
        final View statusDot;
        final TextView status;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.homeV3HeroImage);
            progress = itemView.findViewById(R.id.homeV3HeroImageProgress);
            title = itemView.findViewById(R.id.homeV3HeroTitle);
            genre = itemView.findViewById(R.id.homeV3HeroGenre);
            chapter = itemView.findViewById(R.id.homeV3HeroChapter);
            read = itemView.findViewById(R.id.homeV3HeroRead);
            synopsis = itemView.findViewById(R.id.homeV3HeroSynopsis);
            bookmark = itemView.findViewById(R.id.homeV3HeroBookmark);
            statusGroup = itemView.findViewById(R.id.homeV3HeroStatusGroup);
            statusDot = itemView.findViewById(R.id.homeV3HeroStatusDot);
            status = itemView.findViewById(R.id.homeV3HeroStatus);
        }
    }
}
