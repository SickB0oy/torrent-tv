/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package com.biglybt.android.client.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.HtmlCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.biglybt.android.client.R;
import com.biglybt.android.client.media.MediaLibrary;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Torrent TV media library: downloaded videos shown as posters, with synopsis,
 * rating and a Play / Resume button that opens the built-in player.
 */
public class LibraryActivity
	extends AppCompatActivity
{
	public static final String EXTRA_DIRS = "dirs";

	private MediaLibrary library;

	private final ArrayList<MediaLibrary.Item> items = new ArrayList<>();

	private Adapter adapter;

	private TextView tvStatus;

	private View emptyView;

	private TextView tvEmpty;

	private Button btnEmptyAction;

	private ProgressBar progress;

	private boolean scanning;

	private boolean focusedOnce;

	private RecyclerView grid;

	public static void open(@NonNull Context context,
			@Nullable ArrayList<String> dirs) {
		Intent i = new Intent(context, LibraryActivity.class);
		if (dirs != null) {
			i.putStringArrayListExtra(EXTRA_DIRS, dirs);
		}
		context.startActivity(i);
	}

	/**
	 * On phones that draw the app behind the status and navigation bars
	 * (Android 15+), keep the header and the TMDB credit out from under them.
	 */
	private void applySystemBarInsets() {
		final View header = findViewById(R.id.library_header);
		final View credit = findViewById(R.id.library_credit);
		final View root = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
		final int hTop = header.getPaddingTop(), cBottom = credit.getPaddingBottom();
		final int rLeft = root.getPaddingLeft(), rRight = root.getPaddingRight();
		androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
			androidx.core.graphics.Insets bars = insets.getInsets(
					androidx.core.view.WindowInsetsCompat.Type.systemBars()
							| androidx.core.view.WindowInsetsCompat.Type.displayCutout());
			v.setPadding(rLeft + bars.left, v.getPaddingTop(), rRight + bars.right,
					v.getPaddingBottom());
			header.setPadding(header.getPaddingLeft(), hTop + bars.top,
					header.getPaddingRight(), header.getPaddingBottom());
			credit.setPadding(credit.getPaddingLeft(), credit.getPaddingTop(),
					credit.getPaddingRight(), cBottom + bars.bottom);
			return insets;
		});
		androidx.core.view.ViewCompat.requestApplyInsets(root);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_library);
		com.biglybt.android.client.media.PlayerEngine.warmUp(this);
		applySystemBarInsets();
		library = new MediaLibrary(this);

		tvStatus = findViewById(R.id.library_status);
		emptyView = findViewById(R.id.library_empty);
		tvEmpty = findViewById(R.id.library_empty_text);
		btnEmptyAction = findViewById(R.id.library_empty_action);
		progress = findViewById(R.id.library_progress);
		findViewById(R.id.library_back).setOnClickListener(v -> finish());
		findViewById(R.id.library_key).setOnClickListener(v -> askToken());
		findViewById(R.id.library_refresh).setOnClickListener(v -> scan());
		TextView credit = findViewById(R.id.library_credit);
		credit.setText(HtmlCompat.fromHtml(getString(R.string.library_tmdb_credit),
				HtmlCompat.FROM_HTML_MODE_LEGACY));
		credit.setMovementMethod(LinkMovementMethod.getInstance());

		RecyclerView list = findViewById(R.id.library_grid);
		grid = list;
		int widthDp = (int) (getResources().getDisplayMetrics().widthPixels
				/ getResources().getDisplayMetrics().density);
		int span = Math.max(2, widthDp / 150);
		list.setLayoutManager(new GridLayoutManager(this, span));
		adapter = new Adapter();
		list.setAdapter(adapter);

		// Show what we know right away, then rescan
		new Thread(() -> {
			List<MediaLibrary.Item> cached = library.loadCache();
			runOnUiThread(() -> {
				setItems(cached);
				scan();
			});
		}, "LibraryLoad").start();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Watched / resume markers may have changed in the player
		if (adapter != null) {
			adapter.notifyDataSetChanged();
		}
	}

	private void setItems(List<MediaLibrary.Item> list) {
		items.clear();
		items.addAll(list);
		adapter.notifyDataSetChanged();
		updateEmpty();
		// TV remote: start on the first poster rather than the Back button
		if (!focusedOnce && !items.isEmpty()) {
			focusedOnce = true;
			grid.post(() -> {
				RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
				View current = getCurrentFocus();
				if (first != null && (current == null
						|| current.getId() == R.id.library_back)) {
					first.itemView.requestFocus();
				}
			});
		}
	}

	private void updateEmpty() {
		boolean noToken = library.getToken() == null;
		if (items.isEmpty() && !scanning) {
			emptyView.setVisibility(View.VISIBLE);
			tvEmpty.setText(R.string.library_empty);
			btnEmptyAction.setVisibility(View.GONE);
		} else if (noToken) {
			emptyView.setVisibility(View.VISIBLE);
			tvEmpty.setText(HtmlCompat.fromHtml(getString(R.string.library_need_key),
					HtmlCompat.FROM_HTML_MODE_LEGACY));
			tvEmpty.setMovementMethod(LinkMovementMethod.getInstance());
			btnEmptyAction.setVisibility(View.VISIBLE);
			btnEmptyAction.setText(R.string.library_enter_key);
			btnEmptyAction.setOnClickListener(v -> askToken());
		} else {
			emptyView.setVisibility(View.GONE);
		}
	}

	private void scan() {
		if (scanning) {
			return;
		}
		scanning = true;
		progress.setVisibility(View.VISIBLE);
		tvStatus.setText(R.string.library_scanning);
		ArrayList<String> dirs = getIntent().getStringArrayListExtra(EXTRA_DIRS);
		new Thread(() -> {
			List<MediaLibrary.Item> result = library.refresh(dirs,
					update -> runOnUiThread(() -> setItems(update)));
			runOnUiThread(() -> {
				scanning = false;
				progress.setVisibility(View.GONE);
				setItems(result);
				int n = result.size();
				tvStatus.setText(getResources().getQuantityString(
						R.plurals.library_count, n, n));
			});
		}, "LibraryScan").start();
	}

	/* ---------- TMDB key ---------- */

	private void askToken() {
		final String current = library.getToken();
		EditText input = new EditText(this);
		// Torrent TV: the key is never shown again once saved, and is hidden
		// while typed or pasted
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD
				| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		input.setHint(current != null ? R.string.library_key_saved_hint
				: R.string.library_key_hint);
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		if (current != null) {
			// Green confirmation that the saved key is in use
			android.widget.TextView active = new android.widget.TextView(this);
			active.setText(R.string.library_key_active);
			active.setTextColor(0xFF1E9E70);
			active.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
			active.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
			active.setPadding(0, 0, 0, pad / 2);
			box.addView(active);
		}
		box.addView(input, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				this).setTitle(R.string.library_key_title).setMessage(
						current != null ? R.string.library_key_saved_message
								: R.string.library_key_message).setView(
										box).setPositiveButton(R.string.library_key_save,
												(d, w) -> {
													String token = input.getText().toString().trim();
													if (token.isEmpty()) {
														// Nothing typed: keep the saved key
														return;
													}
													verifyAndSave(token);
												}).setNegativeButton(R.string.library_close,
														null);
		if (current != null) {
			builder.setNeutralButton(R.string.library_key_remove, (d, w) -> {
				library.setToken(null);
				updateEmpty();
			});
		}
		builder.show();
	}

	private void verifyAndSave(String token) {
		tvStatus.setText(R.string.library_key_checking);
		new Thread(() -> {
			String error = library.checkToken(token);
			runOnUiThread(() -> {
				if (error == null) {
					library.setToken(token);
					Toast.makeText(this, R.string.library_key_ok, Toast.LENGTH_SHORT).show();
					// Force a lookup of everything not found yet
					scan();
				} else {
					Toast.makeText(this, getString(R.string.library_key_bad, error),
							Toast.LENGTH_LONG).show();
					tvStatus.setText("");
				}
				updateEmpty();
			});
		}, "TmdbCheck").start();
	}

	/* ---------- Watch state, shared with the player ---------- */

	private SharedPreferences playerPrefs() {
		return getSharedPreferences("player_positions", MODE_PRIVATE);
	}

	private long resumeAt(MediaLibrary.Item it) {
		return playerPrefs().getLong(it.uri().toString(), 0);
	}

	private boolean watched(MediaLibrary.Item it) {
		return playerPrefs().getBoolean("watched:" + it.uri().toString(), false);
	}

	/* ---------- Details ---------- */

	private void showDetails(MediaLibrary.Item it) {
		View v = LayoutInflater.from(this).inflate(R.layout.dialog_library_detail,
				null);
		ImageView backdrop = v.findViewById(R.id.detail_backdrop);
		TextView title = v.findViewById(R.id.detail_title);
		TextView meta = v.findViewById(R.id.detail_meta);
		TextView overview = v.findViewById(R.id.detail_overview);
		TextView file = v.findViewById(R.id.detail_file);

		String url = it.backdropUrl() != null ? it.backdropUrl() : it.posterUrl();
		if (url != null) {
			Picasso.with(this).load(url).fit().centerCrop().into(backdrop);
		} else {
			backdrop.setVisibility(View.GONE);
		}
		title.setText(it.title);

		ArrayList<String> parts = new ArrayList<>();
		if (!TextUtils.isEmpty(it.year)) {
			parts.add(it.year);
		}
		if (it.isEpisode()) {
			parts.add(it.episodeLabel()
					+ (it.episodeTitle != null ? " : " + it.episodeTitle : ""));
		}
		if (it.rating > 0) {
			parts.add(String.format(Locale.getDefault(), "★ %.1f/10", it.rating));
		}
		parts.add(sizeText(it.size));
		meta.setText(TextUtils.join("    ", parts));
		overview.setText(TextUtils.isEmpty(it.overview)
				? getString(it.hasMetadata() ? R.string.library_no_overview
						: R.string.library_not_found)
				: it.overview);
		file.setText(new File(it.path).getName());

		long resume = resumeAt(it);
		AlertDialog dlg = new MaterialAlertDialogBuilder(this).setView(v).setPositiveButton(
				resume > 0 ? R.string.library_resume : R.string.library_play,
				(d, w) -> PlayerActivity.play(this, it.uri(), it.title)).setNeutralButton(
						R.string.library_fix, (d, w) -> askFix(it)).setNegativeButton(
								R.string.library_close, null).create();
		dlg.show();
		Button play = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
		if (play != null) {
			play.requestFocus();
		}
	}

	/** Let the user type the right title when the guess was wrong */
	private void askFix(MediaLibrary.Item it) {
		if (library.getToken() == null) {
			askToken();
			return;
		}
		EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_TEXT);
		input.setText(it.guessedTitle);
		input.setSelectAllOnFocus(true);
		LinearLayout box = new LinearLayout(this);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		box.addView(input, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		new MaterialAlertDialogBuilder(this).setTitle(R.string.library_fix_title).setMessage(
				R.string.library_fix_message).setView(box).setPositiveButton(
						R.string.library_fix_search, (d, w) -> {
							String q = input.getText().toString().trim();
							if (q.isEmpty()) {
								return;
							}
							// "Title 2021" -> title + year
							String year = null;
							java.util.regex.Matcher m = java.util.regex.Pattern.compile(
									"^(.*?)\\s*\\(?((?:19|20)\\d{2})\\)?$").matcher(q);
							if (m.matches()) {
								q = m.group(1);
								year = m.group(2);
							}
							final String fq = q, fy = year;
							new Thread(() -> {
								boolean ok;
								try {
									ok = library.research(it, fq, fy);
								} catch (Exception e) {
									ok = false;
								}
								boolean found = ok;
								runOnUiThread(() -> {
									Toast.makeText(this,
											found ? R.string.library_fix_ok : R.string.library_not_found,
											Toast.LENGTH_SHORT).show();
									adapter.notifyDataSetChanged();
								});
							}, "TmdbFix").start();
						}).setNegativeButton(android.R.string.cancel, null).show();
	}

	private static String sizeText(long b) {
		double gb = b / (1024.0 * 1024 * 1024);
		return gb >= 1 ? String.format(Locale.getDefault(), "%.1f Go", gb)
				: String.format(Locale.getDefault(), "%d Mo", b / (1024 * 1024));
	}

	/* ---------- Grid ---------- */

	private class Adapter
		extends RecyclerView.Adapter<Holder>
	{
		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new Holder(LayoutInflater.from(parent.getContext()).inflate(
					R.layout.item_library, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull Holder h, int position) {
			MediaLibrary.Item it = items.get(position);
			h.title.setText(it.title);
			h.subtitle.setText(it.isEpisode() ? it.episodeLabel()
					: TextUtils.isEmpty(it.year) ? "" : it.year);
			String poster = it.posterUrl();
			if (poster != null) {
				h.fallback.setVisibility(View.GONE);
				Picasso.with(h.poster.getContext()).load(poster).fit().centerCrop().into(
						h.poster);
			} else {
				Picasso.with(h.poster.getContext()).cancelRequest(h.poster);
				h.poster.setImageDrawable(null);
				h.fallback.setVisibility(View.VISIBLE);
				h.fallback.setText(it.title);
			}
			long resume = resumeAt(it);
			boolean seen = watched(it);
			h.seen.setVisibility(seen ? View.VISIBLE : View.GONE);
			h.resume.setVisibility(!seen && resume > 0 ? View.VISIBLE : View.GONE);
			h.itemView.setOnClickListener(v -> showDetails(it));
			h.itemView.setOnFocusChangeListener((v, focused) -> {
				float s = focused ? 1.06f : 1f;
				v.animate().scaleX(s).scaleY(s).setDuration(120).start();
			});
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static class Holder
		extends RecyclerView.ViewHolder
	{
		final ImageView poster;

		final TextView fallback;

		final TextView title;

		final TextView subtitle;

		final View seen;

		final View resume;

		Holder(View v) {
			super(v);
			poster = v.findViewById(R.id.item_poster);
			fallback = v.findViewById(R.id.item_fallback);
			title = v.findViewById(R.id.item_title);
			subtitle = v.findViewById(R.id.item_subtitle);
			seen = v.findViewById(R.id.item_seen);
			resume = v.findViewById(R.id.item_resume);
		}
	}
}
