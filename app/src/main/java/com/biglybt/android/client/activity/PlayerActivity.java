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
		setupNextEpisode();
		// TV remote: focus stays on the buttons, never on the picture
		videoLayout.setFocusable(false);

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
		if ("smb".equals(uri.getScheme())) {
			// Torrent TV: the computer's shared folder, signed in
			for (String o : com.biglybt.android.client.media.NetworkShare.playerOptions(
					this)) {
				media.addOption(o);
			}
		}
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
				maybeOfferNext(event.getTimeChanged());
				break;
			case MediaPlayer.Event.Vout:
				matchFrameRate();
				break;
			case MediaPlayer.Event.Playing:
				matchFrameRate();
				btnPlay.setImageResource(R.drawable.ic_player_pause);
				btnPlay.setContentDescription(getString(R.string.player_pause));
				maybeResume();
				scheduleAudioCheck();
				// Tracks are known by now: VF and French subtitles by themselves
				handler.postDelayed(this::autoPickTracks, 400);
				break;
			case MediaPlayer.Event.Paused:
			case MediaPlayer.Event.Stopped:
				btnPlay.setImageResource(R.drawable.ic_player_play);
				btnPlay.setContentDescription(getString(R.string.player_play));
				break;
			case MediaPlayer.Event.EndReached:
				clearPosition();
				markWatched();
				if (nextItem != null && !nextCancelled) {
					playNext();
				} else {
					finish();
				}
				break;
			case MediaPlayer.Event.EncounteredError:
				failed();
				break;
			default:
				break;
		}
	}

	/* ---------- Languages ---------- */

	private boolean tracksPicked;

	/**
	 * Torrent TV: MULTi files start in French (VFF before VFQ). French
	 * subtitles: the full ones for a film in original version, only the
	 * "forced" ones (signs, foreign dialogue) for a film in French.
	 * Once per start; the Audio and Subtitles buttons still change it.
	 */
	private void autoPickTracks() {
		if (tracksPicked || player == null) {
			return;
		}
		tracksPicked = true;
		try {
			MediaPlayer.TrackDescription[] audio = player.getAudioTracks();
			int bestAudio = -1, bestScore = 0;
			for (int i = 0; audio != null && i < audio.length; i++) {
				int sc = frenchScore(audio[i].name);
				if (audio[i].id != -1 && sc > bestScore) {
					bestScore = sc;
					bestAudio = audio[i].id;
				}
			}
			boolean frenchAudio = bestScore > 0;
			if (frenchAudio && player.getAudioTrack() != bestAudio) {
				player.setAudioTrack(bestAudio);
			}
			MediaPlayer.TrackDescription[] subs = player.getSpuTracks();
			int full = -1, forced = -1;
			for (int i = 0; subs != null && i < subs.length; i++) {
				if (subs[i].id == -1 || frenchScore(subs[i].name) == 0) {
					continue;
				}
				if (isForced(subs[i].name)) {
					if (forced == -1) {
						forced = subs[i].id;
					}
				} else if (full == -1) {
					full = subs[i].id;
				}
			}
			int wantSub = frenchAudio ? forced : (full != -1 ? full : forced);
			boolean anyAudio = audio != null && audio.length > 1;
			if (wantSub != -1) {
				player.setSpuTrack(wantSub);
			} else if (frenchAudio && anyAudio) {
				// French sound, no forced French subtitles: no subtitles at all
				player.setSpuTrack(-1);
			}
		} catch (Throwable t) {
			Log.w(TAG, "autoPickTracks", t);
		}
	}

	/** 0 = not French; higher = better French track */
	private static int frenchScore(@Nullable String name) {
		if (name == null) {
			return 0;
		}
		String n = " " + name.toLowerCase(Locale.ROOT) + " ";
		boolean fr = n.contains("fran") || n.contains("french") || n.contains("[fr")
				|| n.contains("(fr") || n.contains(" fr ") || n.contains(" fre")
				|| n.contains("vff") || n.contains("vfq") || n.contains("vfi")
				|| n.contains(" vf ") || n.contains(" vf]") || n.contains("[vf");
		if (!fr) {
			return 0;
		}
		if (n.contains("vfq") || n.contains("qu\u00e9b") || n.contains("quebec")
				|| n.contains("canad")) {
			return 1;
		}
		if (n.contains("audiodesc") || n.contains("audio desc") || n.contains(" ad ")
				|| n.contains("malvoyant")) {
			return 1;
		}
		if (n.contains("vff") || n.contains("france") || n.contains("truefrench")) {
			return 3;
		}
		return 2;
	}

	private static boolean isForced(@Nullable String name) {
		if (name == null) {
			return false;
		}
		String n = name.toLowerCase(Locale.ROOT);
		return n.contains("forc") || n.contains("signs");
	}

	/* ---------- Next episode ---------- */

	@Nullable
	private com.biglybt.android.client.media.MediaLibrary.Item nextItem;

	private boolean nextCancelled;

	private boolean nextShown;

	private int nextCount;

	private final Runnable nextTick = this::nextTick;

	/** Looks up the next episode (if this is one) in the background */
	private void setupNextEpisode() {
		findViewById(R.id.player_next_play).setOnClickListener(v -> playNext());
		findViewById(R.id.player_next_cancel).setOnClickListener(v -> {
			nextCancelled = true;
			handler.removeCallbacks(nextTick);
			findViewById(R.id.player_next).setVisibility(View.GONE);
			showControls();
		});
		final String path = "smb".equals(uri.getScheme())
				? Uri.decode(uri.toString())
				: "file".equals(uri.getScheme()) ? uri.getPath() : null;
		if (path == null) {
			return;
		}
		new Thread(() -> {
			com.biglybt.android.client.media.MediaLibrary.Item n = new com.biglybt.android.client.media.MediaLibrary(
					this).nextEpisode(path);
			handler.post(() -> nextItem = n);
		}, "NextEpisode").start();
	}

	/** 20 s before the end (credits): offer the next episode */
	private void maybeOfferNext(long time) {
		if (nextItem == null || nextCancelled || nextShown || player == null) {
			return;
		}
		long length = player.getLength();
		if (length <= 0 || length - time > 20_000) {
			return;
		}
		nextShown = true;
		com.biglybt.android.client.media.MediaLibrary.Item n = nextItem;
		((TextView) findViewById(R.id.player_next_label)).setText(getString(
				R.string.player_next_label, n.episodeLabel()
						+ (n.episodeTitle != null ? " · " + n.episodeTitle : "")));
		hideControlsNow();
		findViewById(R.id.player_next).setVisibility(View.VISIBLE);
		findViewById(R.id.player_next_play).requestFocus();
		nextCount = 10;
		nextTick();
	}

	private void nextTick() {
		if (nextCancelled) {
			return;
		}
		if (nextCount <= 0) {
			playNext();
			return;
		}
		((TextView) findViewById(R.id.player_next_count)).setText(
				getString(R.string.player_next_count, nextCount));
		nextCount--;
		handler.postDelayed(nextTick, 1000);
	}

	private void playNext() {
		com.biglybt.android.client.media.MediaLibrary.Item n = nextItem;
		if (n == null || isFinishing()) {
			return;
		}
		handler.removeCallbacks(nextTick);
		clearPosition();
		markWatched();
		String title = (n.title != null ? n.title : "") + " — " + n.episodeLabel();
		// singleTop activity: a new start would land in this one, so it is
		// rebuilt with the next episode instead
		Intent i = new Intent(this, PlayerActivity.class);
		i.setData(n.uri());
		i.putExtra(EXTRA_TITLE, title);
		setIntent(i);
		nextItem = null;
		recreate();
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
		controls.setVisibility(View.VISIBLE);
		// TV remote: always a highlighted button to start from
		if (controls.findFocus() == null) {
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
	}

	/**
	 * TV remote. Controls hidden: OK = pause/play, left/right = -/+ 10 s,
	 * up/down = show the buttons. Controls shown: arrows move between the
	 * buttons, OK presses one; on the timeline, left/right = -/+ 10 s.
	 * Back hides the buttons, then leaves.
	 */
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		int code = event.getKeyCode();
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return isOurKey(code) || super.dispatchKeyEvent(event);
		}
		View nextBox = findViewById(R.id.player_next);
		if (nextBox != null && nextBox.getVisibility() == View.VISIBLE) {
			if (code == KeyEvent.KEYCODE_BACK) {
				findViewById(R.id.player_next_cancel).performClick();
				return true;
			}
			return super.dispatchKeyEvent(event);
		}
		boolean visible = controls.getVisibility() == View.VISIBLE;
		// Held down: bigger steps
		long step = event.getRepeatCount() > 4 ? SEEK_STEP_MS * 3 : SEEK_STEP_MS;
		switch (code) {
			case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
			case KeyEvent.KEYCODE_SPACE:
			case KeyEvent.KEYCODE_HEADSETHOOK:
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
			case KeyEvent.KEYCODE_MEDIA_STOP:
				finish();
				return true;
			case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
			case KeyEvent.KEYCODE_MEDIA_NEXT:
				seekBy(SEEK_STEP_MS * 3);
				return true;
			case KeyEvent.KEYCODE_MEDIA_REWIND:
			case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
				seekBy(-SEEK_STEP_MS * 3);
				return true;
			case KeyEvent.KEYCODE_DPAD_LEFT:
				if (!visible || seekBar.hasFocus()) {
					seekBy(-step);
					return true;
				}
				break;
			case KeyEvent.KEYCODE_DPAD_RIGHT:
				if (!visible || seekBar.hasFocus()) {
					seekBy(step);
					return true;
				}
				break;
			case KeyEvent.KEYCODE_DPAD_CENTER:
			case KeyEvent.KEYCODE_ENTER:
			case KeyEvent.KEYCODE_NUMPAD_ENTER:
				if (!visible || seekBar.hasFocus()) {
					togglePlay();
					return true;
				}
				break;
			case KeyEvent.KEYCODE_DPAD_UP:
			case KeyEvent.KEYCODE_DPAD_DOWN:
				if (!visible) {
					showControls();
					return true;
				}
				break;
			case KeyEvent.KEYCODE_BACK:
				if (visible) {
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

	/** Key-up of the keys handled entirely on key-down above */
	private static boolean isOurKey(int code) {
		switch (code) {
			case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
			case KeyEvent.KEYCODE_MEDIA_PLAY:
			case KeyEvent.KEYCODE_MEDIA_PAUSE:
			case KeyEvent.KEYCODE_MEDIA_STOP:
			case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
			case KeyEvent.KEYCODE_MEDIA_REWIND:
			case KeyEvent.KEYCODE_MEDIA_NEXT:
			case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
			case KeyEvent.KEYCODE_HEADSETHOOK:
				return true;
			default:
				return false;
		}
	}

	/* ---------- Sound check ---------- */

	private boolean audioChecked;

	/**
	 * Some TVs play nothing with 7.1 sound sent as is. A few seconds in, if
	 * sound was decoded but none came out, start again at the same spot in
	 * "compatible sound" (stereo) mode.
	 */
	private void scheduleAudioCheck() {
		if (audioChecked || player == null
				|| com.biglybt.android.client.media.PlayerEngine.isCompatAudio(this)) {
			return;
		}
		audioChecked = true;
		handler.postDelayed(() -> {
			if (player == null || !player.isPlaying()
					|| player.getAudioTrack() == -1) {
				return;
			}
			long[] st = audioStats();
			if (st != null && st[0] > 20 && st[1] == 0) {
				Log.w(TAG, "Audio decoded (" + st[0] + ") but not played: compat mode");
				switchCompatAudio(true);
			}
		}, 5000);
	}

	/** {decoded audio blocks, played audio buffers}, or null if unknown */
	@Nullable
	private long[] audioStats() {
		Object media = null;
		try {
			// Looked up by reflection: the stats API moved between libVLC versions
			media = player.getClass().getMethod("getMedia").invoke(player);
			if (media == null) {
				return null;
			}
			Object stats = media.getClass().getMethod("getStats").invoke(media);
			if (stats == null) {
				return null;
			}
			long decoded = ((Number) stats.getClass().getField("decodedAudio").get(
					stats)).longValue();
			long played = ((Number) stats.getClass().getField("playedAbuffers").get(
					stats)).longValue();
			return new long[] {
				decoded,
				played
			};
		} catch (Throwable t) {
			return null;
		} finally {
			if (media != null) {
				try {
					media.getClass().getMethod("release").invoke(media);
				} catch (Throwable ignore) {
				}
			}
		}
	}

	private void switchCompatAudio(boolean on) {
		com.biglybt.android.client.media.PlayerEngine.setCompatAudio(this, on);
		Toast.makeText(this, on ? R.string.player_audio_compat_toast
				: R.string.player_audio_normal_toast, Toast.LENGTH_LONG).show();
		// The new sound output is picked when the player starts again
		savePosition();
		recreate();
	}

	/* ---------- Tracks and picture ---------- */

	private void chooseAudio() {
		if (player == null) {
			return;
		}
		MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
		final boolean compat = com.biglybt.android.client.media.PlayerEngine.isCompatAudio(
				this);
		showTrackDialog(R.string.player_audio, tracks, player.getAudioTrack(),
				id -> player.setAudioTrack(id),
				compat ? R.string.player_audio_normal : R.string.player_audio_compat,
				() -> switchCompatAudio(!compat));
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
	 * Torrent TV: on a TV, the screen follows the film's frame rate, so camera
	 * pans don't judder (a 24 fps film on a 60 Hz screen shows frames for
	 * uneven times). "Maximum smoothness" (default): the screen switches to a
	 * matching mode even if it goes black for 1-2 s. Otherwise only switches
	 * the TV can do without a black screen.
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
		if (com.biglybt.android.client.media.PlayerEngine.isSmooth(this)) {
			frameRateDone = true;
			switchDisplayMode(fps);
			return;
		}
		if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
			return;
		}
		android.view.SurfaceView sv = findSurface(videoLayout);
		if (sv == null || !sv.getHolder().getSurface().isValid()) {
			return;
		}
		frameRateDone = true;
		try {
			android.view.Surface surface = sv.getHolder().getSurface();
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
				surface.setFrameRate(fps,
						android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
						android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
			} else {
				surface.setFrameRate(fps,
						android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
			}
		} catch (Throwable t) {
			Log.w(TAG, "setFrameRate", t);
		}
	}

	/**
	 * Picks the display mode (same resolution) whose refresh rate is the
	 * closest whole multiple of the film's frame rate: 23.976 Hz rather than
	 * 24 Hz for a 23.976 fps film, the lowest such rate when several fit.
	 */
	private void switchDisplayMode(float fps) {
		android.view.Display display = getWindowManager().getDefaultDisplay();
		android.view.Display.Mode current = display.getMode();
		if (multipleError(current.getRefreshRate(), fps) < 0.0005f) {
			return;
		}
		android.view.Display.Mode best = null;
		float bestErr = 1;
		for (android.view.Display.Mode m : display.getSupportedModes()) {
			if (m.getPhysicalWidth() != current.getPhysicalWidth()
					|| m.getPhysicalHeight() != current.getPhysicalHeight()) {
				continue;
			}
			float err = multipleError(m.getRefreshRate(), fps);
			if (err > 0.003f) {
				continue;
			}
			if (best == null || err < bestErr - 0.0002f
					|| (Math.abs(err - bestErr) <= 0.0002f
							&& m.getRefreshRate() < best.getRefreshRate())) {
				best = m;
				bestErr = err;
			}
		}
		if (best == null || best.getModeId() == current.getModeId()) {
			return;
		}
		WindowManager.LayoutParams lp = getWindow().getAttributes();
		lp.preferredDisplayModeId = best.getModeId();
		getWindow().setAttributes(lp);
	}

	/** How far refresh/fps is from a whole number, relative (0 = exact) */
	private static float multipleError(float refresh, float fps) {
		float ratio = refresh / fps;
		long whole = Math.round(ratio);
		if (whole < 1) {
			return 1;
		}
		return Math.abs(ratio - whole) / whole;
	}

	/** The video surface inside libVLC's layout (not the subtitles one) */
	@Nullable
	private static android.view.SurfaceView findSurface(@NonNull View v) {
		int id = v.getResources().getIdentifier("surface_video", "id",
				v.getContext().getPackageName());
		if (id != 0) {
			View found = v.findViewById(id);
			if (found instanceof android.view.SurfaceView) {
				return (android.view.SurfaceView) found;
			}
		}
		if (v instanceof android.view.SurfaceView) {
			return (android.view.SurfaceView) v;
		}
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				android.view.SurfaceView sv = findSurface(g.getChildAt(i));
				if (sv != null) {
					return sv;
				}
			}
		}
		return null;
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
		showTrackDialog(titleRes, tracks, current, chooser, 0, null);
	}

	private void showTrackDialog(int titleRes,
			@Nullable MediaPlayer.TrackDescription[] tracksIn, int current,
			TrackChooser chooser, int extraLabel, @Nullable Runnable extra) {
		handler.removeCallbacks(hideControls);
		if ((tracksIn == null || tracksIn.length == 0) && extra == null) {
			Toast.makeText(this, R.string.player_no_tracks, Toast.LENGTH_SHORT).show();
			return;
		}
		final MediaPlayer.TrackDescription[] tracks = tracksIn != null ? tracksIn
				: new MediaPlayer.TrackDescription[0];
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
		MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this).setTitle(
				titleRes).setSingleChoiceItems(names, checked, (d, which) -> {
					chooser.choose(tracks[which].id);
					d.dismiss();
					showControls();
				}).setOnCancelListener(d -> showControls());
		if (extra != null) {
			b.setNeutralButton(extraLabel, (d, w) -> extra.run());
		}
		b.show();
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
			com.biglybt.android.client.media.WatchNext.continueWatching(this, uri, t,
					length);
		} else if (length > 0 && t >= length * 0.95) {
			clearPosition();
			markWatched();
		}
	}

	/** Read by the media library to show "Watched" */
	private void markWatched() {
		positions().edit().putBoolean("watched:" + key(), true).apply();
		com.biglybt.android.client.media.WatchNext.remove(this, uri);
		if (nextItem != null) {
			com.biglybt.android.client.media.WatchNext.nextUp(this, nextItem.uri());
		}
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
