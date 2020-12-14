package com.dkanada.gramophone.service;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.dkanada.gramophone.R;
import com.dkanada.gramophone.model.Song;
import com.dkanada.gramophone.service.playback.Playback;
import com.dkanada.gramophone.util.MusicUtil;
import com.dkanada.gramophone.util.PreferenceUtil;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.annotations.EverythingIsNonNull;

public class MultiPlayer implements Playback {
    public static final String TAG = MultiPlayer.class.getSimpleName();

    private final Context context;
    private final OkHttpClient httpClient;

    private SimpleExoPlayer exoPlayer;
    private ConcatenatingMediaSource mediaSource;

    private final SimpleCache simpleCache;
    private final DataSource.Factory dataSource;

    private PlaybackCallbacks callbacks;

    private boolean isReady = false;
    private boolean isPlaying = false;

    private boolean requestPlay = false;
    private int requestProgress = 0;

    private final ExoPlayer.EventListener eventListener = new ExoPlayer.EventListener() {
        @Override
        public void onIsLoadingChanged(boolean isLoading) {
            Log.i(TAG, String.format("onIsLoadingChanged: %b", isLoading));
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            Log.i(TAG, String.format("onPlayWhenReadyChanged: %b %d", playWhenReady, reason));
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            Log.i(TAG, String.format("onPlaybackStateChanged: %d", playbackState));

            if (callbacks == null) return;
            if (requestProgress != 0 && playbackState == Player.STATE_READY) {
                exoPlayer.seekTo(requestProgress);

                requestProgress = 0;
            }

            if (exoPlayer.isPlaying() || requestPlay && playbackState == ExoPlayer.STATE_READY) {
                requestPlay = false;
                isPlaying = true;

                exoPlayer.setPlayWhenReady(true);
                callbacks.onTrackStarted();
            }
        }

        @Override
        public void onPositionDiscontinuity(int reason) {
            Log.i(TAG, String.format("onPositionDiscontinuity: %d", reason));
            int windowIndex = exoPlayer.getCurrentWindowIndex();

            if (windowIndex == 1) {
                mediaSource.removeMediaSource(0);
                if (exoPlayer.isPlaying()) {
                    // there are still songs left in the queue
                    callbacks.onTrackWentToNext();
                } else {
                    callbacks.onTrackEnded();
                }
            }
        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            Log.i(TAG, String.format("onPlayerError: %s", error.getMessage()));
            if (context == null) {
                return;
            }

            Toast.makeText(context, context.getResources().getString(R.string.unplayable_file), Toast.LENGTH_SHORT).show();
            exoPlayer.release();

            exoPlayer = new SimpleExoPlayer.Builder(context).build();
            isReady = false;
        }
    };

    public MultiPlayer(Context context) {
        this.context = context;

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(1);

        httpClient = new OkHttpClient.Builder().dispatcher(dispatcher).build();

        exoPlayer = new SimpleExoPlayer.Builder(context).build();
        mediaSource = new ConcatenatingMediaSource();

        exoPlayer.addListener(eventListener);
        exoPlayer.prepare(mediaSource);
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

        long cacheSize = PreferenceUtil.getInstance(context).getMediaCacheSize();
        LeastRecentlyUsedCacheEvictor recentlyUsedCache = new LeastRecentlyUsedCacheEvictor(cacheSize);
        ExoDatabaseProvider databaseProvider = new ExoDatabaseProvider(context);

        File cacheDirectory = new File(context.getCacheDir(), "exoplayer");
        simpleCache = new SimpleCache(cacheDirectory, recentlyUsedCache, databaseProvider);
        dataSource = buildDataSourceFactory();
    }

    @Override
    public void setDataSource(Song song) {
        isReady = false;
        mediaSource = new ConcatenatingMediaSource();

        exoPlayer.addListener(eventListener);
        exoPlayer.prepare(mediaSource);

        appendDataSource(MusicUtil.getSongFileUri(song));
    }

    @Override
    public void queueDataSource(Song song) {
        String path = MusicUtil.getSongFileUri(song);
        if (mediaSource.getSize() == 2 && mediaSource.getMediaSource(1).getTag() != path) {
            mediaSource.removeMediaSource(1);
        }

        if (mediaSource.getSize() != 2) {
            appendDataSource(path);
        }
    }

    private void appendDataSource(String path) {
        Uri uri = Uri.parse(path);

        httpClient.newCall(new Request.Builder().url(path).head().build()).enqueue(new Callback() {
            @Override
            @EverythingIsNonNull
            public void onFailure(Call call, IOException e) {
                Toast.makeText(context, context.getResources().getString(R.string.unplayable_file), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }

            @Override
            @EverythingIsNonNull
            public void onResponse(Call call, Response response) {
                String type = response.header("Content-Type");
                if (type == null) return;

                MediaSource source;
                if (type.equals("application/x-mpegURL")) {
                    source = new HlsMediaSource.Factory(dataSource)
                            .setTag(path)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(uri);
                } else {
                    source = new ProgressiveMediaSource.Factory(dataSource)
                            .setTag(path)
                            .createMediaSource(uri);
                }

                mediaSource.addMediaSource(source);
                isReady = true;
            }
        });
    }

    private DataSource.Factory buildDataSourceFactory() {
        return () -> new CacheDataSource(
                simpleCache,
                new DefaultDataSourceFactory(context, context.getPackageName(), null).createDataSource(),
                new FileDataSource(),
                new CacheDataSink(simpleCache, 10 * 1024 * 1024),
                CacheDataSource.FLAG_BLOCK_ON_CACHE,
                null
        );
    }

    @Override
    public void setCallbacks(Playback.PlaybackCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public boolean isReady() {
        return isReady;
    }

    @Override
    public boolean isPlaying() {
        return isReady && isPlaying;
    }

    @Override
    public void start() {
        if (!isReady) {
            requestPlay = true;
            return;
        }

        isPlaying = true;
        exoPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        isPlaying = false;
        exoPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        simpleCache.release();
        exoPlayer.release();
    }

    @Override
    public int getProgress() {
        if (!isReady) return -1;
        return (int) exoPlayer.getCurrentPosition();
    }

    @Override
    public int getDuration() {
        if (!isReady) return -1;
        return (int) exoPlayer.getDuration();
    }

    @Override
    public void setProgress(int progress) {
        if (!isReady) {
            requestProgress = progress;
            return;
        }

        exoPlayer.seekTo(progress);
    }

    @Override
    public void setVolume(int volume) {
        exoPlayer.setVolume(volume / 100f);
    }

    @Override
    public int getVolume() {
        return (int) (exoPlayer.getVolume() * 100);
    }
}
