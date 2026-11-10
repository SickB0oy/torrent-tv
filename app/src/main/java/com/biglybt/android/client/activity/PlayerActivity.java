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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.biglybt.android.client.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Torrent TV: built-in video and audio player, based on libVLC.
 * <p>
 * Plays any format VLC can (MKV, HEVC, DTS, ASS subtitles...), from a local
 * file, a content:// URI or an HTTP stream (a torrent still downloading).
 * Works with touch and with a TV remote (D-pad, media keys).
 */
public class PlayerActivity
	extends AppCompatActivity
{
	private static final String TAG = "Player";

	public static final String EXTRA_TITLE = "title";

	private static final long CONTROLS_TIMEOUT_MS = 4000;

	private static final long SEEK_STEP_MS = 10_000;

	private LibVLC libVLC;

	private MediaPlayer player;

	private VLCVideoLayout videoLayout;

	private View controls;

	private ImageButton btnPlay;

	private SeekBar seekBar;

	private TextView tvTime;

	private TextView tvLength;

	private ParcelFileDescriptor pfd;

	private Uri uri;

	private boolean userSeeking;

	private boolean resumeDone;

	/** Display mode chosen for this video (or nothing to change) */
	private boolean frameRateDone;

	/** Saved position the video was started at, in ms */
	private long resumeFrom;

	private int scaleMode = 0;

	private final Handler handler = new Handler(Looper.getMainLooper());

	private final Runnable hideControls = this::hideControlsNow;

	public static void play(@NonNull Context context, @NonNull Uri uri,
			@Nullable String title) {
		Intent intent = new Intent(context, PlayerActivity.class);
		intent.setData(uri);
		intent.putExtra(EXTRA_TITLE, title);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		context.startActivity(intent);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_player);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		goImmersive();

		uri = getIntent().getData();
		if (uri == null) {
			finish();
			return;
		}

		videoLayout = findViewById(R.id.player_video);
		controls = findViewById(R.id.player_controls);
		btnPlay = findViewById(R.id.player_play);
		seekBar = findViewById(R.id.player_seek);
		tvTime = findViewById(R.id.player_time);
		tvLength = findViewById(R.id.player_length);
		TextView tvTitle = findViewById(R.id.player_title);
		String title = getIntent().getStringExtra(EXTRA_TITLE);
		tvTitle.setText(title != null ? title : uri.getLastPathSegment());

		findViewById(R.id.player_back).setOnClickListener(v -> finish());
		btnPlay.setOnClickListener(v -> togglePlay());
		findViewById(R.id.player_rewind).setOnClickListener(v -> seekBy(-SEEK_STEP_MS));
		findViewById(R.id.player_forward).setOnClickListener(v -> seekBy(SEEK_STEP_MS));
		findViewById(R.id.player_audio).setOnClickListener(v -> chooseAudio());
		findViewById(R.id.player_subtitles).setOnClickListener(v -> chooseSubtitles());
		findViewById(R.id.player_aspect).setOnClickListener(v -> cycleAspect());
		videoLayout.setOnClickListener(v -> toggleControls());

		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
				if (fromUser) {
					tvTime.setText(formatTime(progress));
					if (!userSeeking && player != null) {
						// D-pad changes on a focused SeekBar
						player.setTime(progress);
					}
					showControls();
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar sb) {
				userSeeking = true;
				handler.removeCallbacks(hideControls);
			}

			@Override
			public void onStopTrackingTouch(SeekBar sb) {
				userSeeking = false;
				if (player != null) {
					player.setTime(sb.getProgress());
				}
				showControls();
			}
		});

		// Shared instance, usually already prepared in the background
		libVLC = com.biglybt.android.client.media.PlayerEngine.get(this);
		player = new MediaPlayer(libVLC);
		player.setEventListener(this::onPlayerEvent);

		Media media;
		try {
			if ("content".equals(uri.getScheme())) {
				pfd = getContentResolver().openFileDescriptor(uri, "r");
				if (pfd == null) {
					throw new IllegalStateException("No file descriptor");
				}
				media = new Media(libVLC, pfd.getFileDescriptor());
			} else {
				media = new Media(libVLC, uri);
			}
		} catch (Throwable t) {
			Log.e(TAG, "Can't open " + uri, t);
			failed();
			return;
		}
		media.setHWDecoderEnabled(true, false);
		// Start directly at the saved position: no second wait to seek there
		long resumeAt = positions().getLong(key(), 0);
		if (resumeAt > 0) {
			media.addOption(":start-time=" + (resumeAt / 1000.0));
			resumeFrom = resumeAt;
		}
		player.setMedia(media);
		media.release();
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (player == null) {
			return;
		}
		player.attachViews(videoLayout, null, true, false);
		player.play();
		showControls();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (player == null) {
			return;
		}
		savePosition();
		player.pause();
		player.detachViews();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		handler.removeCallbacksAndMessages(null);
		if (player != null) {
			player.setEventListener(null);
			player.stop();
			player.release();
			player = null;
		}
		// libVLC is shared (PlayerEngine): kept for the next video
		libVLC = null;
		if (pfd != null) {
			try {
				pfd.close();
			} catch (Throwable ignore) {
			}
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			goImmersive();
		}
	}

	private void goImmersive() {
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
		WindowInsetsControllerCompat c = WindowCompat.getInsetsController(
				getWindow(), getWindow().getDecorView());
		c.hide(WindowInsetsCompat.Type.systemBars());
		c.setSystemBarsBehavior(
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
	}

	private void onPlayerEvent(MediaPlayer.Event event) {
		switch (event.type) {
			case MediaPlayer.Event.LengthChanged:
				seekBar.setMax((int) Math.max(0, event.getLengthChanged()));
				tvLength.setText(formatTime(event.getLengthChanged()));
				break;
			case MediaPlayer.Event.TimeChanged:
				if (!userSeeking) {
					seekBar.setProgress((int) event.getTimeChanged());
					tvTime.setText(formatTime(event.getTimeChanged()));
				}
				break;
			case MediaPlayer.Event.Vout:
				matchFrameRate();
				break;
			case MediaPlayer.Event.Playing:
				matchFrameRate();
				btnPlay.setImageResource(R.drawable.ic_player_pause);
				btnPlay.setContentDescription(getString(R.string.player_pause));
				maybeResume();
				break;
			case MediaPlayer.Event.Paused:
			case MediaPlayer.Event.Stopped:
				btnPlay.setImageResource(R.drawable.ic_player_play);
				btnPlay.setContentDescription(getString(R.string.player_play));
				break;
			case MediaPlayer.Event.EndReached:
				clearPosition();
				markWatched();
				finish();
				break;
			case MediaPlayer.Event.EncounteredError:
				failed();
				break;
			default:
				break;
		}
	}

	private void failed() {
		Toast.makeText(this, R.string.player_error, Toast.LENGTH_LONG).show();
		finish();
	}

	/* ---------- Controls ---------- */

	private void togglePlay() {
		if (player == null) {
			return;
		}
		if (player.isPlaying()) {
			player.pause();
		} else {
			player.play();
		}
		showControls();
	}

	private void seekBy(long deltaMs) {
		if (player == null) {
			return;
		}
		long length = player.getLength();
		long t = Math.max(0, player.getTime() + deltaMs);
		if (length > 0) {
			t = Math.min(t, length - 1000);
		}
		player.setTime(t);
		tvTime.setText(formatTime(t));
		seekBar.setProgress((int) t);
		showControls();
	}

	private void toggleControls() {
		if (controls.getVisibility() == View.VISIBLE) {
			hideControlsNow();
		} else {
			showControls();
		}
	}

	private void showControls() {
		boolean wasHidden = controls.getVisibility() != View.VISIBLE;
		controls.setVisibility(View.VISIBLE);
		if (wasHidden) {
			btnPlay.requestFocus();
		}
		handler.removeCallbacks(hideControls);
		if (player != null && player.isPlaying()) {
			handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
		}
	}

	private void hideControlsNow() {
		if (userSeeking) {
			return;
		}
		controls.setVisibility(View.GONE);
		videoLayout.requestFocus();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return super.dispatchKeyEvent(event);
		}
		int code = event.getKeyCode();
		boolean visible = controls.getVisibility() == View.VISIBLE;
		switch (code) {
			case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
			case KeyEvent.KEYCODE_SPACE:
				togglePlay();
				return true;
			case KeyEvent.KEYCODE_MEDIA_PLAY:
				if (player != null && !player.isPlaying()) {
					togglePlay();
				}
				return true;
			case KeyEvent.KEYCODE_MEDIA_PAUSE:
				if (player != null && player.isPlaying()) {
					togglePlay();
				}
				return true;
			case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
				seekBy(SEEK_STEP_MS * 3);
				return true;
			case KeyEvent.KEYCODE_MEDIA_REWIND:
				seekBy(-SEEK_STEP_MS * 3);
				return true;
			case KeyEvent.KEYCODE_DPAD_LEFT:
				if (!visible) {
					seekBy(-SEEK_STEP_MS);
					return true;
				}
				break;
			case KeyEvent.KEYCODE_DPAD_RIGHT:
				if (!visible) {
					seekBy(SEEK_STEP_MS);
					return true;
				}
				break;
			case KeyEvent.KEYCODE_DPAD_CENTER:
			case KeyEvent.KEYCODE_ENTER:
			case KeyEvent.KEYCODE_DPAD_UP:
			case KeyEvent.KEYCODE_DPAD_DOWN:
				if (!visible) {
					showControls();
					return true;
				}
				break;
			case KeyEvent.KEYCODE_BACK:
				if (visible && player != null && player.isPlaying()) {
					hideControlsNow();
					return true;
				}
				break;
			default:
				break;
		}
		if (visible) {
			// Any interaction keeps the controls on screen
			showControls();
		}
		return super.dispatchKeyEvent(event);
	}

	/* ---------- Tracks and picture ---------- */

	private void chooseAudio() {
		if (player == null) {
			return;
		}
		MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
		showTrackDialog(R.string.player_audio, tracks, player.getAudioTrack(),
				id -> player.setAudioTrack(id));
	}

	private void chooseSubtitles() {
		if (player == null) {
			return;
		}
		MediaPlayer.TrackDescription[] tracks = player.getSpuTracks();
		if (tracks == null || tracks.length == 0) {
			Toast.makeText(this, R.string.player_no_tracks, Toast.LENGTH_SHORT).show();
			return;
		}
		handler.removeCallbacks(hideControls);
		String[] names = new String[tracks.length];
		int checked = -1;
		for (int i = 0; i < tracks.length; i++) {
			names[i] = tracks[i].id == -1 ? getString(R.string.player_off)
					: tracks[i].name;
			if (tracks[i].id == player.getSpuTrack()) {
				checked = i;
			}
		}
		new MaterialAlertDialogBuilder(this).setTitle(
				R.string.player_subtitles).setSingleChoiceItems(names, checked,
						(d, which) -> {
							player.setSpuTrack(tracks[which].id);
							d.dismiss();
							showControls();
						}).setNeutralButton(R.string.player_sub_size,
								(d, w) -> chooseSubtitleSize()).setOnCancelListener(
										d -> showControls()).show();
	}

	/** Torrent TV: subtitle text size, kept for the next videos */
	private void chooseSubtitleSize() {
		String[] sizes = getResources().getStringArray(R.array.player_sub_sizes);
		int current = com.biglybt.android.client.media.PlayerEngine.getSubtitleSize(
				this);
		new MaterialAlertDialogBuilder(this).setTitle(
				R.string.player_sub_size).setSingleChoiceItems(sizes, current,
						(d, which) -> {
							d.dismiss();
							if (which == current) {
								showControls();
								return;
							}
							com.biglybt.android.client.media.PlayerEngine.setSubtitleSize(
									this, which);
							// The player picks the new size when it starts again:
							// restart at the same spot
							savePosition();
							recreate();
						}).setOnCancelListener(d -> showControls()).show();
	}

	/**
	 * Torrent TV: on a TV, switch the screen to a refresh rate that is a whole
	 * multiple of the video's frame rate (24 fps film on 48/120/144 Hz…), so
	 * camera pans don't judder. Same resolution, nothing done on phones.
	 */
	private void matchFrameRate() {
		if (frameRateDone || player == null
				|| android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
				|| !com.biglybt.android.client.AndroidUtils.isTV(this)) {
			return;
		}
		float fps = videoFrameRate();
		if (fps < 10 || fps > 121) {
			return;
		}
		frameRateDone = true;
		android.view.Display display = getWindowManager().getDefaultDisplay();
		android.view.Display.Mode current = display.getMode();
		if (isMultiple(current.getRefreshRate(), fps)) {
			return;
		}
		android.view.Display.Mode best = null;
		for (android.view.Display.Mode m : display.getSupportedModes()) {
			if (m.getPhysicalWidth() != current.getPhysicalWidth()
					|| m.getPhysicalHeight() != current.getPhysicalHeight()
					|| !isMultiple(m.getRefreshRate(), fps)) {
				continue;
			}
			// Lowest suitable rate: 24 Hz rather than 120 Hz for a 24 fps film
			if (best == null || m.getRefreshRate() < best.getRefreshRate()) {
				best = m;
			}
		}
		if (best != null) {
			WindowManager.LayoutParams lp = getWindow().getAttributes();
			lp.preferredDisplayModeId = best.getModeId();
			getWindow().setAttributes(lp);
		}
	}

	private static boolean isMultiple(float refresh, float fps) {
		float ratio = refresh / fps;
		long whole = Math.round(ratio);
		return whole >= 1 && Math.abs(ratio - whole) < 0.003f * whole;
	}

	/** Frame rate of the video track (libVLC), 0 if unknown */
	private float videoFrameRate() {
		try {
			// Looked up by reflection: same code on every libVLC 3.x version
			Object track = player.getClass().getMethod("getCurrentVideoTrack").invoke(
					player);
			if (track == null) {
				return 0;
			}
			int num = track.getClass().getField("frameRateNum").getInt(track);
			int den = track.getClass().getField("frameRateDen").getInt(track);
			return den > 0 ? num / (float) den : 0;
		} catch (Throwable t) {
			return 0;
		}
	}

	private interface TrackChooser
	{
		void choose(int id);
	}

	private void showTrackDialog(int titleRes,
			@Nullable MediaPlayer.TrackDescription[] tracks, int current,
			TrackChooser chooser) {
		handler.removeCallbacks(hideControls);
		if (tracks == null || tracks.length == 0) {
			Toast.makeText(this, R.string.player_no_tracks, Toast.LENGTH_SHORT).show();
			return;
		}
		String[] names = new String[tracks.length];
		int checked = -1;
		for (int i = 0; i < tracks.length; i++) {
			// libVLC names the "off" entry "Disable"
			names[i] = tracks[i].id == -1 ? getString(R.string.player_off)
					: tracks[i].name;
			if (tracks[i].id == current) {
				checked = i;
			}
		}
		new MaterialAlertDialogBuilder(this).setTitle(titleRes).setSingleChoiceItems(
				names, checked, (d, which) -> {
					chooser.choose(tracks[which].id);
					d.dismiss();
					showControls();
				}).setOnCancelListener(d -> showControls()).show();
	}

	private void cycleAspect() {
		if (player == null) {
			return;
		}
		MediaPlayer.ScaleType[] modes = {
			MediaPlayer.ScaleType.SURFACE_BEST_FIT,
			MediaPlayer.ScaleType.SURFACE_FILL,
			MediaPlayer.ScaleType.SURFACE_16_9,
			MediaPlayer.ScaleType.SURFACE_4_3
		};
		int[] labels = {
			R.string.player_aspect_fit,
			R.string.player_aspect_fill,
			R.string.player_aspect_16_9,
			R.string.player_aspect_4_3
		};
		scaleMode = (scaleMode + 1) % modes.length;
		player.setVideoScale(modes[scaleMode]);
		Toast.makeText(this, labels[scaleMode], Toast.LENGTH_SHORT).show();
		showControls();
	}

	/* ---------- Resume where you stopped ---------- */

	private SharedPreferences positions() {
		return getSharedPreferences("player_positions", MODE_PRIVATE);
	}

	private String key() {
		return uri.toString();
	}

	private void savePosition() {
		if (player == null) {
			return;
		}
		long t = player.getTime();
		long length = player.getLength();
		if (t > 30_000 && length > 0 && t < length * 0.95) {
			positions().edit().putLong(key(), t).apply();
		} else if (length > 0 && t >= length * 0.95) {
			clearPosition();
			markWatched();
		}
	}

	/** Read by the media library to show "Watched" */
	private void markWatched() {
		positions().edit().putBoolean("watched:" + key(), true).apply();
	}

	private void clearPosition() {
		positions().edit().remove(key()).apply();
	}

	private void maybeResume() {
		if (resumeDone || player == null) {
			return;
		}
		resumeDone = true;
		if (resumeFrom > 0) {
			// Already started there with ":start-time"
			Toast.makeText(this, getString(R.string.player_resumed, formatTime(resumeFrom)),
					Toast.LENGTH_SHORT).show();
		}
	}

	private static String formatTime(long ms) {
		if (ms < 0) {
			ms = 0;
		}
		long s = ms / 1000;
		long h = s / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
				: String.format(Locale.US, "%d:%02d", m, sec);
	}
}
