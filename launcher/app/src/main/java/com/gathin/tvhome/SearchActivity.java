package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search: installed apps by name, plus movies and shows from TMDB. Picking a title shows which of
 * your apps carry it; picking an app opens it (on its search page where the app supports a link).
 */
public class SearchActivity extends Activity {

    private static final int REQ_VOICE = 1;
    private static final String VOICE_PKG = "com.google.android.katniss";
    private static final long DEBOUNCE_MS = 450L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Tmdb tmdb = new Tmdb(BuildConfig.TMDB_TOKEN);
    /** Bumped on every new query so late answers for old queries are dropped. */
    private int generation;

    private EditText query;
    private TextView message, appsLabel, titlesLabel, detailTitle, detailOverview;
    private View detail;
    private LinearLayout offers;
    private RecyclerView apps, titles;
    private TileAdapter appsAdapter;
    private PosterAdapter postersAdapter;
    private final List<TileAdapter.Tile> allApps = new ArrayList<>();
    private final List<String> allAppLabels = new ArrayList<>();

    private final Runnable runSearch = this::search;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        float d = getResources().getDisplayMetrics().density;

        query = findViewById(R.id.query);
        message = findViewById(R.id.message);
        appsLabel = findViewById(R.id.appsLabel);
        titlesLabel = findViewById(R.id.titlesLabel);
        detail = findViewById(R.id.detail);
        detailTitle = findViewById(R.id.detailTitle);
        detailOverview = findViewById(R.id.detailOverview);
        offers = findViewById(R.id.offers);

        apps = findViewById(R.id.apps);
        apps.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        apps.setItemAnimator(null);
        appsAdapter = new TileAdapter((int) (136 * d), (int) (76 * d), true);
        apps.setAdapter(appsAdapter);

        titles = findViewById(R.id.titles);
        titles.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        titles.setItemAnimator(null);
        postersAdapter = new PosterAdapter();
        titles.setAdapter(postersAdapter);

        loadApps();

        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(runSearch);
                handler.postDelayed(runSearch, DEBOUNCE_MS);
            }
        });
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            handler.removeCallbacks(runSearch);
            search();
            return true;
        });
        findViewById(R.id.mic).setOnClickListener(v -> listen());
        // The box is wide, so Down would land on whichever result sits under its middle; start at the first.
        query.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            RecyclerView first = appsAdapter.getItemCount() > 0 ? apps : titles;
            View tile = first.getChildCount() > 0 ? first.getChildAt(0).findViewById(R.id.card) : null;
            if (tile == null) return false;
            tile.requestFocus();
            return true;
        });

        if (!tmdb.configured()) showMessage("Movie & show search needs a TMDB token (tmdb.token in local.properties).");
        query.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---- apps ------------------------------------------------------------------------------------

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            allApps.add(new TileAdapter.Tile(label, banner, ri.loadIcon(pm), () -> open(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(cn)), null));
            allAppLabels.add(label.toLowerCase(Locale.US));
        }
    }

    // ---- search ----------------------------------------------------------------------------------

    private void search() {
        String q = query.getText().toString().trim();
        int gen = ++generation;
        detail.setVisibility(View.GONE);

        List<TileAdapter.Tile> apps = new ArrayList<>();
        if (!q.isEmpty()) {
            String lq = q.toLowerCase(Locale.US);
            for (int i = 0; i < allApps.size(); i++) if (allAppLabels.get(i).contains(lq)) apps.add(allApps.get(i));
        }
        appsAdapter.setTiles(apps);
        appsLabel.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);

        if (q.length() < 2 || !tmdb.configured()) {
            postersAdapter.set(new ArrayList<>());
            titlesLabel.setVisibility(View.GONE);
            return;
        }
        io.execute(() -> {
            List<Tmdb.Title> found;
            String err = null;
            try {
                found = tmdb.search(q);
            } catch (Exception e) {
                found = new ArrayList<>();
                err = e.getMessage();
            }
            List<Tmdb.Title> result = found;
            String error = err;
            handler.post(() -> {
                if (gen != generation) return;
                postersAdapter.set(result);
                titlesLabel.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
                if (error != null) showMessage("Search failed: " + error);
                else message.setVisibility(View.GONE);
            });
        });
    }

    private void showDetail(Tmdb.Title t) {
        detail.setVisibility(View.VISIBLE);
        detailTitle.setText(t.year.isEmpty() ? t.name : t.name + "  (" + t.year + ")");
        detailOverview.setText(t.overview);
        offers.removeAllViews();
    }

    /** Look up which installed apps carry the title and show them as buttons. */
    private void pick(Tmdb.Title t) {
        showDetail(t);
        int gen = generation;
        io.execute(() -> {
            List<Tmdb.Offer> found;
            try {
                found = tmdb.offers(this, t);
            } catch (Exception e) {
                found = null;
            }
            List<Tmdb.Offer> result = found;
            handler.post(() -> {
                if (gen != generation) return;
                offers.removeAllViews();
                if (result == null) {
                    addOfferButton("Couldn't load where to watch", null);
                } else {
                    for (Tmdb.Offer o : result) {
                        addOfferButton((o.subscription ? "Watch on " : "Rent/buy on ") + o.app.label,
                                () -> open(o.app.intent(this, t.name)));
                    }
                    if (result.isEmpty() || !result.get(0).subscription) {
                        addOfferButton(getString(R.string.search_youtube),
                                () -> open(Tmdb.App.YOUTUBE.intent(this, t.name)));
                    }
                }
                if (offers.getChildCount() > 0) offers.getChildAt(0).requestFocus();
            });
        });
    }

    private void addOfferButton(String text, Runnable action) {
        TextView b = (TextView) LayoutInflater.from(this).inflate(R.layout.item_panel_button, offers, false);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (44 * getResources().getDisplayMetrics().density));
        lp.setMarginEnd((int) (10 * getResources().getDisplayMetrics().density));
        b.setLayoutParams(lp);
        b.setPadding(b.getPaddingLeft(), 0, (int) (20 * getResources().getDisplayMetrics().density), 0);
        if (action != null) b.setOnClickListener(v -> action.run());
        offers.addView(b);
    }

    // ---- voice -----------------------------------------------------------------------------------

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint));
        // Two Google apps offer speech input here; prefer the TV voice UI the remote's mic button uses
        // so there is no "Complete action using" chooser.
        Intent tv = new Intent(i).setPackage(VOICE_PKG);
        if (tv.resolveActivity(getPackageManager()) != null) i = tv;
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            showMessage("Voice input isn't available on this TV.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        query.setText(heard.get(0));
        query.setSelection(query.length());
        handler.removeCallbacks(runSearch);
        search();
    }

    // ---- misc ------------------------------------------------------------------------------------

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void open(Intent i) {
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Can't open that app", Toast.LENGTH_SHORT).show();
        }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
        private final List<Tmdb.Title> items = new ArrayList<>();

        void set(List<Tmdb.Title> fresh) {
            items.clear();
            items.addAll(fresh);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Tmdb.Title t = items.get(position);
            h.fallback.setText(t.name);
            ImageLoader.load(t.poster, h.poster);
            h.card.setOnClickListener(v -> pick(t));
            h.card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? TileAdapter.FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(180).start();
                if (focused) showDetail(t);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View card;
            final ImageView poster;
            final TextView fallback;

            Holder(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                poster = v.findViewById(R.id.poster);
                fallback = v.findViewById(R.id.fallback);
                card.setClipToOutline(true);
            }
        }
    }
}
