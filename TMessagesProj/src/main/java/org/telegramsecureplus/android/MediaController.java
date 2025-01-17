/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2014.
 */

package org.telegramsecureplus.android;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.View;

import org.telegramsecureplus.android.video.InputSurface;
import org.telegramsecureplus.android.video.MP4Builder;
import org.telegramsecureplus.android.video.Mp4Movie;
import org.telegramsecureplus.android.video.OutputSurface;
import org.telegramsecureplus.messenger.ConnectionsManager;
import org.telegramsecureplus.messenger.DispatchQueue;
import org.telegramsecureplus.messenger.FileLoader;
import org.telegramsecureplus.messenger.FileLog;
import org.telegramsecureplus.messenger.R;
import org.telegramsecureplus.messenger.TLRPC;
import org.telegramsecureplus.messenger.UserConfig;
import org.telegramsecureplus.messenger.Utilities;
import org.telegramsecureplus.messenger.ApplicationLoader;
import org.telegramsecureplus.ui.Cells.ChatMediaCell;
import org.telegramsecureplus.ui.Components.GifDrawable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

public class MediaController implements NotificationCenter.NotificationCenterDelegate, SensorEventListener {

    private native int startRecord(String path);
    private native int writeFrame(ByteBuffer frame, int len);
    private native void stopRecord();
    private native int openOpusFile(String path);
    private native int seekOpusFile(float position);
    private native int isOpusFile(String path);
    private native void closeOpusFile();
    private native void readOpusFile(ByteBuffer buffer, int capacity, int[] args);
    private native long getTotalPcmDuration();

    public static int[] readArgs = new int[3];

    public interface FileDownloadProgressListener {
        void onFailedDownload(String fileName);
        void onSuccessDownload(String fileName);
        void onProgressDownload(String fileName, float progress);
        void onProgressUpload(String fileName, float progress, boolean isEncrypted);
        int getObserverTag();
    }

    private class AudioBuffer {
        public AudioBuffer(int capacity) {
            buffer = ByteBuffer.allocateDirect(capacity);
            bufferBytes = new byte[capacity];
        }

        ByteBuffer buffer;
        byte[] bufferBytes;
        int size;
        int finished;
        long pcmOffset;
    }

    private static final String[] projectionPhotos = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION
    };

    private static final String[] projectionVideo = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_TAKEN
    };

    public static class AlbumEntry {
        public int bucketId;
        public String bucketName;
        public PhotoEntry coverPhoto;
        public ArrayList<PhotoEntry> photos = new ArrayList<>();
        public HashMap<Integer, PhotoEntry> photosByIds = new HashMap<>();
        public boolean isVideo;

        public AlbumEntry(int bucketId, String bucketName, PhotoEntry coverPhoto, boolean isVideo) {
            this.bucketId = bucketId;
            this.bucketName = bucketName;
            this.coverPhoto = coverPhoto;
            this.isVideo = isVideo;
        }

        public void addPhoto(PhotoEntry photoEntry) {
            photos.add(photoEntry);
            photosByIds.put(photoEntry.imageId, photoEntry);
        }
    }

    public static class PhotoEntry {
        public int bucketId;
        public int imageId;
        public long dateTaken;
        public String path;
        public int orientation;
        public String thumbPath;
        public String imagePath;
        public boolean isVideo;
        public CharSequence caption;

        public PhotoEntry(int bucketId, int imageId, long dateTaken, String path, int orientation, boolean isVideo) {
            this.bucketId = bucketId;
            this.imageId = imageId;
            this.dateTaken = dateTaken;
            this.path = path;
            this.orientation = orientation;
            this.isVideo = isVideo;
        }
    }

    public static class SearchImage {
        public int uid;
        public String id;
        public String imageUrl;
        public String thumbUrl;
        public String localUrl;
        public int width;
        public int height;
        public int size;
        public int type;
        public int date;
        public String thumbPath;
        public String imagePath;
        public CharSequence caption;
    }

    public final static String MIME_TYPE = "video/avc";
    private final static int PROCESSOR_TYPE_OTHER = 0;
    private final static int PROCESSOR_TYPE_QCOM = 1;
    private final static int PROCESSOR_TYPE_INTEL = 2;
    private final static int PROCESSOR_TYPE_MTK = 3;
    private final static int PROCESSOR_TYPE_SEC = 4;
    private final static int PROCESSOR_TYPE_TI = 5;
    private final Object videoConvertSync = new Object();

    private HashMap<Long, Long> typingTimes = new HashMap<>();

    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private boolean ignoreProximity;
    private PowerManager.WakeLock proximityWakeLock;

    private ArrayList<MessageObject> videoConvertQueue = new ArrayList<>();
    private final Object videoQueueSync = new Object();
    private boolean cancelCurrentVideoConversion = false;
    private boolean videoConvertFirstWrite = true;

    public static final int AUTODOWNLOAD_MASK_PHOTO = 1;
    public static final int AUTODOWNLOAD_MASK_AUDIO = 2;
    public static final int AUTODOWNLOAD_MASK_VIDEO = 4;
    public static final int AUTODOWNLOAD_MASK_DOCUMENT = 8;
    public int mobileDataDownloadMask = 0;
    public int wifiDownloadMask = 0;
    public int roamingDownloadMask = 0;
    private int lastCheckMask = 0;
    private ArrayList<DownloadObject> photoDownloadQueue = new ArrayList<>();
    private ArrayList<DownloadObject> audioDownloadQueue = new ArrayList<>();
    private ArrayList<DownloadObject> documentDownloadQueue = new ArrayList<>();
    private ArrayList<DownloadObject> videoDownloadQueue = new ArrayList<>();
    private HashMap<String, DownloadObject> downloadQueueKeys = new HashMap<>();

    private boolean saveToGallery = true;

    public static AlbumEntry allPhotosAlbumEntry;

    private HashMap<String, ArrayList<WeakReference<FileDownloadProgressListener>>> loadingFileObservers = new HashMap<>();
    private HashMap<Integer, String> observersByTag = new HashMap<>();
    private boolean listenerInProgress = false;
    private HashMap<String, FileDownloadProgressListener> addLaterArray = new HashMap<>();
    private ArrayList<FileDownloadProgressListener> deleteLaterArray = new ArrayList<>();
    private int lastTag = 0;

    private GifDrawable currentGifDrawable;
    private MessageObject currentGifMessageObject;
    private ChatMediaCell currentMediaCell;

    private boolean isPaused = false;
    private MediaPlayer audioPlayer = null;
    private AudioTrack audioTrackPlayer = null;
    private int lastProgress = 0;
    private MessageObject playingMessageObject;
    private int playerBufferSize = 0;
    private boolean decodingFinished = false;
    private long currentTotalPcmDuration;
    private long lastPlayPcm;
    private int ignoreFirstProgress = 0;
    private Timer progressTimer = null;
    private final Object progressTimerSync = new Object();
    private boolean useFrontSpeaker;
    private int buffersWrited;

    private AudioRecord audioRecorder = null;
    private TLRPC.TL_audio recordingAudio = null;
    private File recordingAudioFile = null;
    private long recordStartTime;
    private long recordTimeCount;
    private long recordDialogId;
    private MessageObject recordReplyingMessageObject;
    private DispatchQueue fileDecodingQueue;
    private DispatchQueue playerQueue;
    private ArrayList<AudioBuffer> usedPlayerBuffers = new ArrayList<>();
    private ArrayList<AudioBuffer> freePlayerBuffers = new ArrayList<>();
    private final Object playerSync = new Object();
    private final Object playerObjectSync = new Object();

    private final Object sync = new Object();

    private ArrayList<ByteBuffer> recordBuffers = new ArrayList<>();
    private ByteBuffer fileBuffer;
    private int recordBufferSize;
    private boolean sendAfterDone;

    private DispatchQueue recordQueue;
    private DispatchQueue fileEncodingQueue;
    private Runnable recordRunnable = new Runnable() {
        @Override
        public void run() {
            if (audioRecorder != null) {
                ByteBuffer buffer;
                if (!recordBuffers.isEmpty()) {
                    buffer = recordBuffers.get(0);
                    recordBuffers.remove(0);
                } else {
                    buffer = ByteBuffer.allocateDirect(recordBufferSize);
                }
                buffer.rewind();
                int len = audioRecorder.read(buffer, buffer.capacity());
                if (len > 0) {
                    buffer.limit(len);
                    final ByteBuffer finalBuffer = buffer;
                    final boolean flush = len != buffer.capacity();
                    if (len != 0) {
                        fileEncodingQueue.postRunnable(new Runnable() {
                            @Override
                            public void run() {
                                while (finalBuffer.hasRemaining()) {
                                    int oldLimit = -1;
                                    if (finalBuffer.remaining() > fileBuffer.remaining()) {
                                        oldLimit = finalBuffer.limit();
                                        finalBuffer.limit(fileBuffer.remaining() + finalBuffer.position());
                                    }
                                    fileBuffer.put(finalBuffer);
                                    if (fileBuffer.position() == fileBuffer.limit() || flush) {
                                        if (writeFrame(fileBuffer, !flush ? fileBuffer.limit() : finalBuffer.position()) != 0) {
                                            fileBuffer.rewind();
                                            recordTimeCount += fileBuffer.limit() / 2 / 16;
                                        }
                                    }
                                    if (oldLimit != -1) {
                                        finalBuffer.limit(oldLimit);
                                    }
                                }
                                recordQueue.postRunnable(new Runnable() {
                                    @Override
                                    public void run() {
                                        recordBuffers.add(finalBuffer);
                                    }
                                });
                            }
                        });
                    }
                    recordQueue.postRunnable(recordRunnable);
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.recordProgressChanged, System.currentTimeMillis() - recordStartTime);
                        }
                    });
                } else {
                    recordBuffers.add(buffer);
                    stopRecordingInternal(sendAfterDone);
                }
            }
        }
    };

    private class InternalObserver extends ContentObserver {
        public InternalObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            processMediaObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        }
    }

    private class ExternalObserver extends ContentObserver {
        public ExternalObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            processMediaObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }
    }

    /*private class GalleryObserverInternal extends ContentObserver {
        public GalleryObserverInternal() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    loadGalleryPhotosAlbums(0);
                }
            }, 2000);
        }
    }

    private class GalleryObserverExternal extends ContentObserver {
        public GalleryObserverExternal() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    loadGalleryPhotosAlbums(0);
                }
            }, 2000);
        }
    }*/

    private ExternalObserver externalObserver = null;
    private InternalObserver internalObserver = null;
    private long lastSecretChatEnterTime = 0;
    private long lastSecretChatLeaveTime = 0;
    private long lastMediaCheckTime = 0;
    private TLRPC.EncryptedChat lastSecretChat = null;
    private ArrayList<Long> lastSecretChatVisibleMessages = null;
    private int startObserverToken = 0;
    private StopMediaObserverRunnable stopMediaObserverRunnable = null;
    private final class StopMediaObserverRunnable implements Runnable {
        public int currentObserverToken = 0;

        @Override
        public void run() {
            if (currentObserverToken == startObserverToken) {
                try {
                    if (internalObserver != null) {
                        ApplicationLoader.applicationContext.getContentResolver().unregisterContentObserver(internalObserver);
                        internalObserver = null;
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
                try {
                    if (externalObserver != null) {
                        ApplicationLoader.applicationContext.getContentResolver().unregisterContentObserver(externalObserver);
                        externalObserver = null;
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        }
    }
    private String[] mediaProjections = null;

    private static volatile MediaController Instance = null;
    public static MediaController getInstance() {
        MediaController localInstance = Instance;
        if (localInstance == null) {
            synchronized (MediaController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MediaController();
                }
            }
        }
        return localInstance;
    }

    public MediaController() {
        try {
            recordBufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (recordBufferSize <= 0) {
                recordBufferSize = 1280;
            }
            playerBufferSize = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (playerBufferSize <= 0) {
                playerBufferSize = 3840;
            }
            for (int a = 0; a < 5; a++) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
                recordBuffers.add(buffer);
            }
            for (int a = 0; a < 3; a++) {
                freePlayerBuffers.add(new AudioBuffer(playerBufferSize));
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        try {
            sensorManager = (SensorManager) ApplicationLoader.applicationContext.getSystemService(Context.SENSOR_SERVICE);
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            PowerManager powerManager = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            proximityWakeLock = powerManager.newWakeLock(0x00000020, "proximity");
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        fileBuffer = ByteBuffer.allocateDirect(1920);
        recordQueue = new DispatchQueue("recordQueue");
        recordQueue.setPriority(Thread.MAX_PRIORITY);
        fileEncodingQueue = new DispatchQueue("fileEncodingQueue");
        fileEncodingQueue.setPriority(Thread.MAX_PRIORITY);
        playerQueue = new DispatchQueue("playerQueue");
        fileDecodingQueue = new DispatchQueue("fileDecodingQueue");

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        mobileDataDownloadMask = preferences.getInt("mobileDataDownloadMask", AUTODOWNLOAD_MASK_PHOTO | AUTODOWNLOAD_MASK_AUDIO);
        wifiDownloadMask = preferences.getInt("wifiDownloadMask", AUTODOWNLOAD_MASK_PHOTO | AUTODOWNLOAD_MASK_AUDIO);
        roamingDownloadMask = preferences.getInt("roamingDownloadMask", 0);
        saveToGallery = preferences.getBoolean("save_gallery", false);

        NotificationCenter.getInstance().addObserver(this, NotificationCenter.FileDidFailedLoad);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.FileDidLoaded);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.FileLoadProgressChanged);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.FileUploadProgressChanged);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.messagesDeleted);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.removeAllMessagesFromDialog);

        BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkAutodownloadSettings();
            }
        };
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);

        if (UserConfig.isClientActivated()) {
            checkAutodownloadSettings();
        }

        if (Build.VERSION.SDK_INT >= 16) {
            mediaProjections = new String[] {
                    MediaStore.Images.ImageColumns.DATA,
                    MediaStore.Images.ImageColumns.DISPLAY_NAME,
                    MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.ImageColumns.DATE_TAKEN,
                    MediaStore.Images.ImageColumns.TITLE,
                    MediaStore.Images.ImageColumns.WIDTH,
                    MediaStore.Images.ImageColumns.HEIGHT
            };
        } else {
            mediaProjections = new String[] {
                    MediaStore.Images.ImageColumns.DATA,
                    MediaStore.Images.ImageColumns.DISPLAY_NAME,
                    MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.ImageColumns.DATE_TAKEN,
                    MediaStore.Images.ImageColumns.TITLE
            };
        }

        /*try {
            ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, new GalleryObserverExternal());
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        try {
            ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, false, new GalleryObserverInternal());
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }*/
    }

    private void startProgressTimer() {
        synchronized (progressTimerSync) {
            if (progressTimer != null) {
                try {
                    progressTimer.cancel();
                    progressTimer = null;
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
            progressTimer = new Timer();
            progressTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    synchronized (sync) {
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                if (playingMessageObject != null && (audioPlayer != null || audioTrackPlayer != null) && !isPaused) {
                                    try {
                                        if (ignoreFirstProgress != 0) {
                                            ignoreFirstProgress--;
                                            return;
                                        }
                                        int progress = 0;
                                        float value = 0;
                                        if (audioPlayer != null) {
                                            progress = audioPlayer.getCurrentPosition();
                                            value = (float) lastProgress / (float) audioPlayer.getDuration();
                                            if (progress <= lastProgress) {
                                                return;
                                            }
                                        } else if (audioTrackPlayer != null) {
                                            progress = (int) (lastPlayPcm / 48.0f);
                                            value = (float) lastPlayPcm / (float) currentTotalPcmDuration;
                                            if (progress == lastProgress) {
                                                return;
                                            }
                                        }
                                        lastProgress = progress;
                                        playingMessageObject.audioProgress = value;
                                        playingMessageObject.audioProgressSec = lastProgress / 1000;
                                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.audioProgressDidChanged, playingMessageObject.getId(), value);
                                    } catch (Exception e) {
                                        FileLog.e("tmessages", e);
                                    }
                                }
                            }
                        });
                    }
                }
            }, 0, 17);
        }
    }

    private void stopProgressTimer() {
        synchronized (progressTimerSync) {
            if (progressTimer != null) {
                try {
                    progressTimer.cancel();
                    progressTimer = null;
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        }
    }

    public void cleanup() {
        clenupPlayer(false);
        if (currentGifDrawable != null) {
            currentGifDrawable.recycle();
            currentGifDrawable = null;
        }
        currentMediaCell = null;
        currentGifMessageObject = null;
        photoDownloadQueue.clear();
        audioDownloadQueue.clear();
        documentDownloadQueue.clear();
        videoDownloadQueue.clear();
        downloadQueueKeys.clear();
        videoConvertQueue.clear();
        typingTimes.clear();
        cancelVideoConvert(null);
    }

    protected int getAutodownloadMask() {
        int mask = 0;
        if ((mobileDataDownloadMask & AUTODOWNLOAD_MASK_PHOTO) != 0 || (wifiDownloadMask & AUTODOWNLOAD_MASK_PHOTO) != 0 || (roamingDownloadMask & AUTODOWNLOAD_MASK_PHOTO) != 0) {
            mask |= AUTODOWNLOAD_MASK_PHOTO;
        }
        if ((mobileDataDownloadMask & AUTODOWNLOAD_MASK_AUDIO) != 0 || (wifiDownloadMask & AUTODOWNLOAD_MASK_AUDIO) != 0 || (roamingDownloadMask & AUTODOWNLOAD_MASK_AUDIO) != 0) {
            mask |= AUTODOWNLOAD_MASK_AUDIO;
        }
        if ((mobileDataDownloadMask & AUTODOWNLOAD_MASK_VIDEO) != 0 || (wifiDownloadMask & AUTODOWNLOAD_MASK_VIDEO) != 0 || (roamingDownloadMask & AUTODOWNLOAD_MASK_VIDEO) != 0) {
            mask |= AUTODOWNLOAD_MASK_VIDEO;
        }
        if ((mobileDataDownloadMask & AUTODOWNLOAD_MASK_DOCUMENT) != 0 || (wifiDownloadMask & AUTODOWNLOAD_MASK_DOCUMENT) != 0 || (roamingDownloadMask & AUTODOWNLOAD_MASK_DOCUMENT) != 0) {
            mask |= AUTODOWNLOAD_MASK_DOCUMENT;
        }
        return mask;
    }

    public void checkAutodownloadSettings() {
        int currentMask = getCurrentDownloadMask();
        if (currentMask == lastCheckMask) {
            return;
        }
        lastCheckMask = currentMask;
        if ((currentMask & AUTODOWNLOAD_MASK_PHOTO) != 0) {
            if (photoDownloadQueue.isEmpty()) {
                newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_PHOTO);
            }
        } else {
            for (DownloadObject downloadObject : photoDownloadQueue) {
                FileLoader.getInstance().cancelLoadFile((TLRPC.PhotoSize)downloadObject.object);
            }
            photoDownloadQueue.clear();
        }
        if ((currentMask & AUTODOWNLOAD_MASK_AUDIO) != 0) {
            if (audioDownloadQueue.isEmpty()) {
                newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_AUDIO);
            }
        } else {
            for (DownloadObject downloadObject : audioDownloadQueue) {
                FileLoader.getInstance().cancelLoadFile((TLRPC.Audio)downloadObject.object);
            }
            audioDownloadQueue.clear();
        }
        if ((currentMask & AUTODOWNLOAD_MASK_DOCUMENT) != 0) {
            if (documentDownloadQueue.isEmpty()) {
                newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_DOCUMENT);
            }
        } else {
            for (DownloadObject downloadObject : documentDownloadQueue) {
                FileLoader.getInstance().cancelLoadFile((TLRPC.Document)downloadObject.object);
            }
            documentDownloadQueue.clear();
        }
        if ((currentMask & AUTODOWNLOAD_MASK_VIDEO) != 0) {
            if (videoDownloadQueue.isEmpty()) {
                newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_VIDEO);
            }
        } else {
            for (DownloadObject downloadObject : videoDownloadQueue) {
                FileLoader.getInstance().cancelLoadFile((TLRPC.Video)downloadObject.object);
            }
            videoDownloadQueue.clear();
        }

        int mask = getAutodownloadMask();
        if (mask == 0) {
            MessagesStorage.getInstance().clearDownloadQueue(0);
        } else {
            if ((mask & AUTODOWNLOAD_MASK_PHOTO) == 0) {
                MessagesStorage.getInstance().clearDownloadQueue(AUTODOWNLOAD_MASK_PHOTO);
            }
            if ((mask & AUTODOWNLOAD_MASK_AUDIO) == 0) {
                MessagesStorage.getInstance().clearDownloadQueue(AUTODOWNLOAD_MASK_AUDIO);
            }
            if ((mask & AUTODOWNLOAD_MASK_VIDEO) == 0) {
                MessagesStorage.getInstance().clearDownloadQueue(AUTODOWNLOAD_MASK_VIDEO);
            }
            if ((mask & AUTODOWNLOAD_MASK_DOCUMENT) == 0) {
                MessagesStorage.getInstance().clearDownloadQueue(AUTODOWNLOAD_MASK_DOCUMENT);
            }
        }
    }

    public boolean canDownloadMedia(int type) {
        return (getCurrentDownloadMask() & type) != 0;
    }

    private int getCurrentDownloadMask() {
        if (ConnectionsManager.isConnectedToWiFi()) {
            return wifiDownloadMask;
        } else if(ConnectionsManager.isRoaming()) {
            return roamingDownloadMask;
        } else {
            return mobileDataDownloadMask;
        }
    }

    protected void processDownloadObjects(int type, ArrayList<DownloadObject> objects) {
        if (objects.isEmpty()) {
            return;
        }
        ArrayList<DownloadObject> queue = null;
        if (type == AUTODOWNLOAD_MASK_PHOTO) {
            queue = photoDownloadQueue;
        } else if (type == AUTODOWNLOAD_MASK_AUDIO) {
            queue = audioDownloadQueue;
        } else if (type == AUTODOWNLOAD_MASK_VIDEO) {
            queue = videoDownloadQueue;
        } else if (type == AUTODOWNLOAD_MASK_DOCUMENT) {
            queue = documentDownloadQueue;
        }
        for (DownloadObject downloadObject : objects) {
            String path = FileLoader.getAttachFileName(downloadObject.object);
            if (downloadQueueKeys.containsKey(path)) {
                continue;
            }

            boolean added = true;
            if (downloadObject.object instanceof TLRPC.Audio) {
                FileLoader.getInstance().loadFile((TLRPC.Audio)downloadObject.object, false);
            } else if (downloadObject.object instanceof TLRPC.PhotoSize) {
                FileLoader.getInstance().loadFile((TLRPC.PhotoSize)downloadObject.object, null, false);
            } else if (downloadObject.object instanceof TLRPC.Video) {
                FileLoader.getInstance().loadFile((TLRPC.Video)downloadObject.object, false);
            } else if (downloadObject.object instanceof TLRPC.Document) {
                FileLoader.getInstance().loadFile((TLRPC.Document)downloadObject.object, false, false);
            } else {
                added = false;
            }
            if (added) {
                queue.add(downloadObject);
                downloadQueueKeys.put(path, downloadObject);
            }
        }
    }

    protected void newDownloadObjectsAvailable(int downloadMask) {
        int mask = getCurrentDownloadMask();
        if ((mask & AUTODOWNLOAD_MASK_PHOTO) != 0 && (downloadMask & AUTODOWNLOAD_MASK_PHOTO) != 0 && photoDownloadQueue.isEmpty()) {
            MessagesStorage.getInstance().getDownloadQueue(AUTODOWNLOAD_MASK_PHOTO);
        }
        if ((mask & AUTODOWNLOAD_MASK_AUDIO) != 0 && (downloadMask & AUTODOWNLOAD_MASK_AUDIO) != 0 && audioDownloadQueue.isEmpty()) {
            MessagesStorage.getInstance().getDownloadQueue(AUTODOWNLOAD_MASK_AUDIO);
        }
        if ((mask & AUTODOWNLOAD_MASK_VIDEO) != 0 && (downloadMask & AUTODOWNLOAD_MASK_VIDEO) != 0 && videoDownloadQueue.isEmpty()) {
            MessagesStorage.getInstance().getDownloadQueue(AUTODOWNLOAD_MASK_VIDEO);
        }
        if ((mask & AUTODOWNLOAD_MASK_DOCUMENT) != 0 && (downloadMask & AUTODOWNLOAD_MASK_DOCUMENT) != 0 && documentDownloadQueue.isEmpty()) {
            MessagesStorage.getInstance().getDownloadQueue(AUTODOWNLOAD_MASK_DOCUMENT);
        }
    }

    private void checkDownloadFinished(String fileName, int state) {
        DownloadObject downloadObject = downloadQueueKeys.get(fileName);
        if (downloadObject != null) {
            downloadQueueKeys.remove(fileName);
            if (state == 0 || state == 2) {
                MessagesStorage.getInstance().removeFromDownloadQueue(downloadObject.id, downloadObject.type, false /*state != 0*/);
            }
            if (downloadObject.type == AUTODOWNLOAD_MASK_PHOTO) {
                photoDownloadQueue.remove(downloadObject);
                if (photoDownloadQueue.isEmpty()) {
                    newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_PHOTO);
                }
            } else if (downloadObject.type == AUTODOWNLOAD_MASK_AUDIO) {
                audioDownloadQueue.remove(downloadObject);
                if (audioDownloadQueue.isEmpty()) {
                    newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_AUDIO);
                }
            } else if (downloadObject.type == AUTODOWNLOAD_MASK_VIDEO) {
                videoDownloadQueue.remove(downloadObject);
                if (videoDownloadQueue.isEmpty()) {
                    newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_VIDEO);
                }
            } else if (downloadObject.type == AUTODOWNLOAD_MASK_DOCUMENT) {
                documentDownloadQueue.remove(downloadObject);
                if (documentDownloadQueue.isEmpty()) {
                    newDownloadObjectsAvailable(AUTODOWNLOAD_MASK_DOCUMENT);
                }
            }
        }
    }

    public void startMediaObserver() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            return;
        }
        ApplicationLoader.applicationHandler.removeCallbacks(stopMediaObserverRunnable);
        startObserverToken++;
        try {
            if (internalObserver == null) {
                ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, externalObserver = new ExternalObserver());
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        try {
            if (externalObserver == null) {
                ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, false, internalObserver = new InternalObserver());
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void stopMediaObserver() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            return;
        }
        if (stopMediaObserverRunnable == null) {
            stopMediaObserverRunnable = new StopMediaObserverRunnable();
        }
        stopMediaObserverRunnable.currentObserverToken = startObserverToken;
        ApplicationLoader.applicationHandler.postDelayed(stopMediaObserverRunnable, 5000);
    }

    public void processMediaObserver(Uri uri) {
        try {
            Point size = AndroidUtilities.getRealScreenSize();

            Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, mediaProjections, null, null, "date_added DESC LIMIT 1");
            final ArrayList<Long> screenshotDates = new ArrayList<>();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String val = "";
                    String data = cursor.getString(0);
                    String display_name = cursor.getString(1);
                    String album_name = cursor.getString(2);
                    long date = cursor.getLong(3);
                    String title = cursor.getString(4);
                    int photoW = 0;
                    int photoH = 0;
                    if (Build.VERSION.SDK_INT >= 16) {
                        photoW = cursor.getInt(5);
                        photoH = cursor.getInt(6);
                    }
                    if (data != null && data.toLowerCase().contains("screenshot") ||
                            display_name != null && display_name.toLowerCase().contains("screenshot") ||
                            album_name != null && album_name.toLowerCase().contains("screenshot") ||
                            title != null && title.toLowerCase().contains("screenshot")) {
                        try {
                            if (photoW == 0 || photoH == 0) {
                                BitmapFactory.Options bmOptions = new BitmapFactory.Options();
                                bmOptions.inJustDecodeBounds = true;
                                BitmapFactory.decodeFile(data, bmOptions);
                                photoW = bmOptions.outWidth;
                                photoH = bmOptions.outHeight;
                            }
                            if (photoW <= 0 || photoH <= 0 || (photoW == size.x && photoH == size.y || photoH == size.x && photoW == size.y)) {
                                screenshotDates.add(date);
                            }
                        } catch (Exception e) {
                            screenshotDates.add(date);
                        }
                    }
                }
                cursor.close();
            }
            if (!screenshotDates.isEmpty()) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.screenshotTook);
                        checkScreenshots(screenshotDates);
                    }
                });
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void checkScreenshots(ArrayList<Long> dates) {
        if (dates == null || dates.isEmpty() || lastSecretChatEnterTime == 0 || lastSecretChat == null || !(lastSecretChat instanceof TLRPC.TL_encryptedChat)) {
            return;
        }
        long dt = 2000;
        boolean send = false;
        for (Long date : dates) {
            if (lastMediaCheckTime != 0 && date <= lastMediaCheckTime) {
                continue;
            }

            if (date >= lastSecretChatEnterTime) {
                if (lastSecretChatLeaveTime == 0 || date <= lastSecretChatLeaveTime + dt) {
                    lastMediaCheckTime = Math.max(lastMediaCheckTime, date);
                    send = true;
                }
            }
        }
        if (send) {
            SecretChatHelper.getInstance().sendScreenshotMessage(lastSecretChat, lastSecretChatVisibleMessages, null);
        }
    }

    public void setLastEncryptedChatParams(long enterTime, long leaveTime, TLRPC.EncryptedChat encryptedChat, ArrayList<Long> visibleMessages) {
        lastSecretChatEnterTime = enterTime;
        lastSecretChatLeaveTime = leaveTime;
        lastSecretChat = encryptedChat;
        lastSecretChatVisibleMessages = visibleMessages;
    }

    public int generateObserverTag() {
        return lastTag++;
    }

    public void addLoadingFileObserver(String fileName, FileDownloadProgressListener observer) {
        if (listenerInProgress) {
            addLaterArray.put(fileName, observer);
            return;
        }
        removeLoadingFileObserver(observer);

        ArrayList<WeakReference<FileDownloadProgressListener>> arrayList = loadingFileObservers.get(fileName);
        if (arrayList == null) {
            arrayList = new ArrayList<>();
            loadingFileObservers.put(fileName, arrayList);
        }
        arrayList.add(new WeakReference<>(observer));

        observersByTag.put(observer.getObserverTag(), fileName);
    }

    public void removeLoadingFileObserver(FileDownloadProgressListener observer) {
        if (listenerInProgress) {
            deleteLaterArray.add(observer);
            return;
        }
        String fileName = observersByTag.get(observer.getObserverTag());
        if (fileName != null) {
            ArrayList<WeakReference<FileDownloadProgressListener>> arrayList = loadingFileObservers.get(fileName);
            if (arrayList != null) {
                for (int a = 0; a < arrayList.size(); a++) {
                    WeakReference<FileDownloadProgressListener> reference = arrayList.get(a);
                    if (reference.get() == null || reference.get() == observer) {
                        arrayList.remove(a);
                        a--;
                    }
                }
                if (arrayList.isEmpty()) {
                    loadingFileObservers.remove(fileName);
                }
            }
            observersByTag.remove(observer.getObserverTag());
        }
    }

    private void processLaterArrays() {
        for (HashMap.Entry<String, FileDownloadProgressListener> listener : addLaterArray.entrySet()) {
            addLoadingFileObserver(listener.getKey(), listener.getValue());
        }
        addLaterArray.clear();
        for (FileDownloadProgressListener listener : deleteLaterArray) {
            removeLoadingFileObserver(listener);
        }
        deleteLaterArray.clear();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.FileDidFailedLoad) {
            listenerInProgress = true;
            String fileName = (String)args[0];
            ArrayList<WeakReference<FileDownloadProgressListener>> arrayList = loadingFileObservers.get(fileName);
            if (arrayList != null) {
                for (WeakReference<FileDownloadProgressListener> reference : arrayList) {
                    if (reference.get() != null) {
                        reference.get().onFailedDownload(fileName);
                        observersByTag.remove(reference.get().getObserverTag());
                    }
                }
                loadingFileObservers.remove(fileName);
            }
            listenerInProgress = false;
            processLaterArrays();
            checkDownloadFinished(fileName, (Integer)args[1]);
        } else if (id == NotificationCenter.FileDidLoaded) {
            listenerInProgress = true;
            String fileName = (String)args[0];
            ArrayList<WeakReference<FileDownloadProgressListener>> arrayList = loadingFileObservers.get(fileName);
            if (arrayList != null) {
                for (WeakReference<FileDownloadProgressListener> reference : arrayList) {
                    if (reference.get() != null) {
                        reference.get().onSuccessDownload(fileName);
                        observersByTag.remove(reference.get().getObserverTag());
                    }
                }
                loadingFileObservers.remove(fileName);
            }
            listenerInProgress = false;
            processLaterArrays();
            checkDownloadFinished(fileName, 0);
        } else if (id == NotificationCenter.FileLoadProgressChanged) {
            listenerInProgress = true;
            String fileName = (String)args[0];
            ArrayList<WeakReference<FileDownloadProgressListener>> arrayList = loadingFileObservers.get(fileName);
            if (arrayList != null) {
                Float progress = (Float)args[1];
                for (WeakReference<FileDownloadProgressListener> reference : arrayList) {
                    if (reference.get() != null) {
                        reference.get().onProgressDownload(fileName, progress);
                    }
                }
            }
            listenerInProgress = false;
            processLaterArrays();
        } else if (id == NotificationCenter.FileUploadProgressChanged) {
            listenerInProgress = true;
            String fileName = (String)args[0];
            ArrayList<WeakReference<FileDownloadProgressListener>> arrayList = loadingFileObservers.get(fileName);
            if (arrayList != null) {
                Float progress = (Float)args[1];
                Boolean enc = (Boolean)args[2];
                for (WeakReference<FileDownloadProgressListener> reference : arrayList) {
                    if (reference.get() != null) {
                        reference.get().onProgressUpload(fileName, progress, enc);
                    }
                }
            }
            listenerInProgress = false;
            processLaterArrays();
            try {
                ArrayList<SendMessagesHelper.DelayedMessage> delayedMessages = SendMessagesHelper.getInstance().getDelayedMessages(fileName);
                if (delayedMessages != null) {
                    for (SendMessagesHelper.DelayedMessage delayedMessage : delayedMessages) {
                        if (delayedMessage.encryptedChat == null) {
                            long dialog_id = delayedMessage.obj.getDialogId();
                            Long lastTime = typingTimes.get(dialog_id);
                            if (lastTime == null || lastTime + 4000 < System.currentTimeMillis()) {
                                if (delayedMessage.videoLocation != null) {
                                    MessagesController.getInstance().sendTyping(dialog_id, 5, 0);
                                } else if (delayedMessage.documentLocation != null) {
                                    MessagesController.getInstance().sendTyping(dialog_id, 3, 0);
                                } else if (delayedMessage.location != null) {
                                    MessagesController.getInstance().sendTyping(dialog_id, 4, 0);
                                }
                                typingTimes.put(dialog_id, System.currentTimeMillis());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            if (playingMessageObject != null) {
                ArrayList<Integer> markAsDeletedMessages = (ArrayList<Integer>)args[0];
                if (markAsDeletedMessages.contains(playingMessageObject.getId())) {
                    clenupPlayer(false);
                }
            }
        } else if (id == NotificationCenter.removeAllMessagesFromDialog) {
            long did = (Long)args[0];
            if (playingMessageObject != null && playingMessageObject.getDialogId() == did) {
                clenupPlayer(false);
            }
        }
    }

    private void checkDecoderQueue() {
        fileDecodingQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (decodingFinished) {
                    checkPlayerQueue();
                    return;
                }
                boolean was = false;
                while (true) {
                    AudioBuffer buffer = null;
                    synchronized (playerSync) {
                        if (!freePlayerBuffers.isEmpty()) {
                            buffer = freePlayerBuffers.get(0);
                            freePlayerBuffers.remove(0);
                        }
                        if (!usedPlayerBuffers.isEmpty()) {
                            was = true;
                        }
                    }
                    if (buffer != null) {
                        readOpusFile(buffer.buffer, playerBufferSize, readArgs);
                        buffer.size = readArgs[0];
                        buffer.pcmOffset = readArgs[1];
                        buffer.finished = readArgs[2];
                        if (buffer.finished == 1) {
                            decodingFinished = true;
                        }
                        if (buffer.size != 0) {
                            buffer.buffer.rewind();
                            buffer.buffer.get(buffer.bufferBytes);
                            synchronized (playerSync) {
                                usedPlayerBuffers.add(buffer);
                            }
                        } else {
                            synchronized (playerSync) {
                                freePlayerBuffers.add(buffer);
                                break;
                            }
                        }
                        was = true;
                    } else {
                        break;
                    }
                }
                if (was) {
                    checkPlayerQueue();
                }
            }
        });
    }

    private void checkPlayerQueue() {
        playerQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                synchronized (playerObjectSync) {
                    if (audioTrackPlayer == null || audioTrackPlayer.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        return;
                    }
                }
                AudioBuffer buffer = null;
                synchronized (playerSync) {
                    if (!usedPlayerBuffers.isEmpty()) {
                        buffer = usedPlayerBuffers.get(0);
                        usedPlayerBuffers.remove(0);
                    }
                }

                if (buffer != null) {
                    int count = 0;
                    try {
                        count = audioTrackPlayer.write(buffer.bufferBytes, 0, buffer.size);
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                    buffersWrited++;

                    if (count > 0) {
                        final long pcm = buffer.pcmOffset;
                        final int marker = buffer.finished == 1 ? count : -1;
                        final int finalBuffersWrited = buffersWrited;
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                lastPlayPcm = pcm;
                                if (marker != -1) {
                                    if (audioTrackPlayer != null) {
                                        audioTrackPlayer.setNotificationMarkerPosition(1);
                                    }
                                    if (finalBuffersWrited == 1) {
                                        clenupPlayer(true);
                                    }
                                }
                            }
                        });
                    }

                    if (buffer.finished != 1) {
                        checkPlayerQueue();
                    }
                }
                if (buffer == null || buffer != null && buffer.finished != 1) {
                    checkDecoderQueue();
                }

                if (buffer != null) {
                    synchronized (playerSync) {
                        freePlayerBuffers.add(buffer);
                    }
                }
            }
        });
    }

    private boolean isNearToSensor(float value) {
        return value < 5.0f && value != proximitySensor.getMaximumRange();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        FileLog.e("tmessages", "proximity changed to " + event.values[0]);
        if (proximitySensor != null && audioTrackPlayer == null && audioPlayer == null || isPaused || (useFrontSpeaker == isNearToSensor(event.values[0]))) {
            return;
        }
        boolean newValue = isNearToSensor(event.values[0]);
        try {
            if (newValue && NotificationsController.getInstance().audioManager.isWiredHeadsetOn()) {
                return;
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        ignoreProximity = true;
        useFrontSpeaker = newValue;
        NotificationCenter.getInstance().postNotificationName(NotificationCenter.audioRouteChanged, useFrontSpeaker);
        MessageObject currentMessageObject = playingMessageObject;
        float progress = playingMessageObject.audioProgress;
        clenupPlayer(false);
        currentMessageObject.audioProgress = progress;
        playAudio(currentMessageObject);
        ignoreProximity = false;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private void stopProximitySensor() {
        if (ignoreProximity) {
            return;
        }
        try {
            useFrontSpeaker = false;
            NotificationCenter.getInstance().postNotificationName(NotificationCenter.audioRouteChanged, useFrontSpeaker);
            if (sensorManager != null && proximitySensor != null) {
                sensorManager.unregisterListener(this);
            }
            if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
                proximityWakeLock.release();
            }
        } catch (Throwable e) {
            FileLog.e("tmessages", e);
        }
    }

    private void startProximitySensor() {
        if (ignoreProximity) {
            return;
        }
        try {
            if (sensorManager != null && proximitySensor != null) {
                sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (!NotificationsController.getInstance().audioManager.isWiredHeadsetOn() && proximityWakeLock != null && !proximityWakeLock.isHeld()) {
                proximityWakeLock.acquire();
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void clenupPlayer(boolean notify) {
        stopProximitySensor();
        if (audioPlayer != null || audioTrackPlayer != null) {
            if (audioPlayer != null) {
                try {
                    audioPlayer.stop();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
                try {
                    audioPlayer.release();
                    audioPlayer = null;
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            } else if (audioTrackPlayer != null) {
                synchronized (playerObjectSync) {
                    try {
                        audioTrackPlayer.pause();
                        audioTrackPlayer.flush();
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                    try {
                        audioTrackPlayer.release();
                        audioTrackPlayer = null;
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
            }
            stopProgressTimer();
            lastProgress = 0;
            buffersWrited = 0;
            isPaused = false;
            MessageObject lastFile = playingMessageObject;
            playingMessageObject.audioProgress = 0.0f;
            playingMessageObject.audioProgressSec = 0;
            playingMessageObject = null;
            if (notify) {
                NotificationCenter.getInstance().postNotificationName(NotificationCenter.audioDidReset, lastFile.getId());
            }
        }
    }

    private void seekOpusPlayer(final float progress) {
        if (currentTotalPcmDuration * progress == currentTotalPcmDuration) {
            return;
        }
        if (!isPaused) {
            audioTrackPlayer.pause();
        }
        audioTrackPlayer.flush();
        fileDecodingQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                seekOpusFile(progress);
                synchronized (playerSync) {
                    freePlayerBuffers.addAll(usedPlayerBuffers);
                    usedPlayerBuffers.clear();
                }
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isPaused) {
                            ignoreFirstProgress = 3;
                            lastPlayPcm = (long) (currentTotalPcmDuration * progress);
                            if (audioTrackPlayer != null) {
                                audioTrackPlayer.play();
                            }
                            lastProgress = (int) (currentTotalPcmDuration / 48.0f * progress);
                            checkPlayerQueue();
                        }
                    }
                });
            }
        });
    }

    public boolean seekToProgress(MessageObject messageObject, float progress) {
        if (audioTrackPlayer == null && audioPlayer == null || messageObject == null || playingMessageObject == null || playingMessageObject != null && playingMessageObject.getId() != messageObject.getId()) {
            return false;
        }
        try {
            if (audioPlayer != null) {
                int seekTo = (int) (audioPlayer.getDuration() * progress);
                audioPlayer.seekTo(seekTo);
                lastProgress = seekTo;
            } else if (audioTrackPlayer != null) {
                seekOpusPlayer(progress);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
            return false;
        }
        return true;
    }

    public boolean playAudio(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        if ((audioTrackPlayer != null || audioPlayer != null) && playingMessageObject != null && messageObject.getId() == playingMessageObject.getId()) {
            if (isPaused) {
                resumeAudio(messageObject);
            }
            return true;
        }
        NotificationCenter.getInstance().postNotificationName(NotificationCenter.audioDidStarted, messageObject);
        clenupPlayer(true);
        File file = null;
        if (messageObject.messageOwner.attachPath != null && messageObject.messageOwner.attachPath.length() > 0) {
            file = new File(messageObject.messageOwner.attachPath);
            if (!file.exists()) {
                file = null;
            }
        }
        final File cacheFile = file != null ? file : FileLoader.getPathToMessage(messageObject.messageOwner);

        if (isOpusFile(cacheFile.getAbsolutePath()) == 1) {
            synchronized (playerObjectSync) {
                try {
                    ignoreFirstProgress = 3;
                    final Semaphore semaphore = new Semaphore(0);
                    final Boolean[] result = new Boolean[1];
                    fileDecodingQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            result[0] = openOpusFile(cacheFile.getAbsolutePath()) != 0;
                            semaphore.release();
                        }
                    });
                    semaphore.acquire();

                    if (!result[0]) {
                        return false;
                    }
                    currentTotalPcmDuration = getTotalPcmDuration();

                    audioTrackPlayer = new AudioTrack(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, playerBufferSize, AudioTrack.MODE_STREAM);
                    audioTrackPlayer.setStereoVolume(1.0f, 1.0f);
                    audioTrackPlayer.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                        @Override
                        public void onMarkerReached(AudioTrack audioTrack) {
                            clenupPlayer(true);
                        }

                        @Override
                        public void onPeriodicNotification(AudioTrack audioTrack) {

                        }
                    });
                    audioTrackPlayer.play();
                    startProgressTimer();
                    startProximitySensor();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                    if (audioTrackPlayer != null) {
                        audioTrackPlayer.release();
                        audioTrackPlayer = null;
                        isPaused = false;
                        playingMessageObject = null;
                    }
                    return false;
                }
            }
        } else {
            try {
                audioPlayer = new MediaPlayer();
                audioPlayer.setAudioStreamType(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                audioPlayer.setDataSource(cacheFile.getAbsolutePath());
                audioPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mediaPlayer) {
                        clenupPlayer(true);
                    }
                });
                audioPlayer.prepare();
                audioPlayer.start();
                startProgressTimer();
                startProximitySensor();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
                if (audioPlayer != null) {
                    audioPlayer.release();
                    audioPlayer = null;
                    isPaused = false;
                    playingMessageObject = null;
                }
                return false;
            }
        }

        isPaused = false;
        lastProgress = 0;
        lastPlayPcm = 0;
        playingMessageObject = messageObject;

        if (audioPlayer != null) {
            try {
                if (playingMessageObject.audioProgress != 0) {
                    int seekTo = (int) (audioPlayer.getDuration() * playingMessageObject.audioProgress);
                    audioPlayer.seekTo(seekTo);
                }
            } catch (Exception e2) {
                playingMessageObject.audioProgress = 0;
                playingMessageObject.audioProgressSec = 0;
                FileLog.e("tmessages", e2);
            }
        } else if (audioTrackPlayer != null) {
            if (playingMessageObject.audioProgress == 1) {
                playingMessageObject.audioProgress = 0;
            }
            fileDecodingQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (playingMessageObject != null && playingMessageObject.audioProgress != 0) {
                            lastPlayPcm = (long)(currentTotalPcmDuration * playingMessageObject.audioProgress);
                            seekOpusFile(playingMessageObject.audioProgress);
                        }
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                    synchronized (playerSync) {
                        freePlayerBuffers.addAll(usedPlayerBuffers);
                        usedPlayerBuffers.clear();
                    }
                    decodingFinished = false;
                    checkPlayerQueue();
                }
            });
        }

        return true;
    }

    public void stopAudio() {
        stopProximitySensor();
        if (audioTrackPlayer == null && audioPlayer == null || playingMessageObject == null) {
            return;
        }
        try {
            if (audioPlayer != null) {
                audioPlayer.stop();
            } else if (audioTrackPlayer != null) {
                audioTrackPlayer.pause();
                audioTrackPlayer.flush();
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        try {
            if (audioPlayer != null) {
                audioPlayer.release();
                audioPlayer = null;
            } else if (audioTrackPlayer != null) {
                synchronized (playerObjectSync) {
                    audioTrackPlayer.release();
                    audioTrackPlayer = null;
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        stopProgressTimer();
        playingMessageObject = null;
        isPaused = false;
    }

    public boolean pauseAudio(MessageObject messageObject) {
        stopProximitySensor();
        if (audioTrackPlayer == null && audioPlayer == null || messageObject == null || playingMessageObject == null || playingMessageObject != null && playingMessageObject.getId() != messageObject.getId()) {
            return false;
        }
        stopProgressTimer();
        try {
            if (audioPlayer != null) {
                audioPlayer.pause();
            } else if (audioTrackPlayer != null) {
                audioTrackPlayer.pause();
            }
            isPaused = true;
        } catch (Exception e) {
            FileLog.e("tmessages", e);
            isPaused = false;
            return false;
        }
        return true;
    }

    public boolean resumeAudio(MessageObject messageObject) {
        if (audioTrackPlayer == null && audioPlayer == null || messageObject == null || playingMessageObject == null || playingMessageObject != null && playingMessageObject.getId() != messageObject.getId()) {
            return false;
        }
        startProximitySensor();
        try {
            startProgressTimer();
            if (audioPlayer != null) {
                audioPlayer.start();
            } else if (audioTrackPlayer != null) {
                audioTrackPlayer.play();
                checkPlayerQueue();
            }
            isPaused = false;
        } catch (Exception e) {
            FileLog.e("tmessages", e);
            return false;
        }
        return true;
    }

    public boolean isPlayingAudio(MessageObject messageObject) {
        return !(audioTrackPlayer == null && audioPlayer == null || messageObject == null || playingMessageObject == null || playingMessageObject != null && playingMessageObject.getId() != messageObject.getId());
    }

    public boolean isAudioPaused() {
        return isPaused;
    }

    public void startRecording(final long dialog_id, final MessageObject reply_to_msg) {
        clenupPlayer(true);

        try {
            Vibrator v = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(20);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }

        recordQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (audioRecorder != null) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.recordStartError);
                        }
                    });
                    return;
                }

                recordingAudio = new TLRPC.TL_audio();
                recordingAudio.dc_id = Integer.MIN_VALUE;
                recordingAudio.id = UserConfig.lastLocalId;
                recordingAudio.user_id = UserConfig.getClientUserId();
                recordingAudio.mime_type = "audio/ogg";
                UserConfig.lastLocalId--;
                UserConfig.saveConfig(false);

                recordingAudioFile = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), FileLoader.getAttachFileName(recordingAudio));

                try {
                    if (startRecord(recordingAudioFile.getAbsolutePath()) == 0) {
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.recordStartError);
                            }
                        });
                        return;
                    }
                    audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferSize * 10);
                    recordStartTime = System.currentTimeMillis();
                    recordTimeCount = 0;
                    recordDialogId = dialog_id;
                    recordReplyingMessageObject = reply_to_msg;
                    fileBuffer.rewind();

                    audioRecorder.startRecording();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                    recordingAudio = null;
                    stopRecord();
                    recordingAudioFile.delete();
                    recordingAudioFile = null;
                    try {
                        audioRecorder.release();
                        audioRecorder = null;
                    } catch (Exception e2) {
                        FileLog.e("tmessages", e2);
                    }

                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.recordStartError);
                        }
                    });
                    return;
                }

                recordQueue.postRunnable(recordRunnable);
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.recordStarted);
                    }
                });
            }
        });
    }

    private void stopRecordingInternal(final boolean send) {
        if (send) {
            final TLRPC.TL_audio audioToSend = recordingAudio;
            final File recordingAudioFileToSend = recordingAudioFile;
            fileEncodingQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    stopRecord();
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            audioToSend.date = ConnectionsManager.getInstance().getCurrentTime();
                            audioToSend.size = (int) recordingAudioFileToSend.length();
                            long duration = recordTimeCount;
                            audioToSend.duration = (int) (duration / 1000);
                            if (duration > 700) {
                                SendMessagesHelper.getInstance().sendMessage(audioToSend, recordingAudioFileToSend.getAbsolutePath(), recordDialogId, recordReplyingMessageObject);
                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.audioDidSent);
                            } else {
                                recordingAudioFileToSend.delete();
                            }
                        }
                    });
                }
            });
        }
        try {
            if (audioRecorder != null) {
                audioRecorder.release();
                audioRecorder = null;
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        recordingAudio = null;
        recordingAudioFile = null;
    }

    public void stopRecording(final boolean send) {
        recordQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (audioRecorder == null) {
                    return;
                }
                try {
                    sendAfterDone = send;
                    audioRecorder.stop();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                    if (recordingAudioFile != null) {
                        recordingAudioFile.delete();
                    }
                }
                if (!send) {
                    stopRecordingInternal(false);
                }
                try {
                    Vibrator v = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
                    v.vibrate(20);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.recordStopped);
                    }
                });
            }
        });
    }

    public static void saveFile(String fullPath, Context context, final int type, final String name) {
        if (fullPath == null) {
            return;
        }

        File file = null;
        if (fullPath != null && fullPath.length() != 0) {
            file = new File(fullPath);
            if (!file.exists()) {
                file = null;
            }
        }

        if (file == null) {
            return;
        }

        final File sourceFile = file;
        if (sourceFile.exists()) {
            ProgressDialog progressDialog = null;
            if (context != null) {
                try {
                    progressDialog = new ProgressDialog(context);
                    progressDialog.setMessage(LocaleController.getString("Loading", R.string.Loading));
                    progressDialog.setCanceledOnTouchOutside(false);
                    progressDialog.setCancelable(false);
                    progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                    progressDialog.setMax(100);
                    progressDialog.show();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }

            final ProgressDialog finalProgress = progressDialog;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File destFile = null;
                        if (type == 0) {
                            destFile = AndroidUtilities.generatePicturePath();
                        } else if (type == 1) {
                            destFile = AndroidUtilities.generateVideoPath();
                        } else if (type == 2) {
                            File f = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            destFile = new File(f, name);
                        }

                        if(!destFile.exists()) {
                            destFile.createNewFile();
                        }
                        FileChannel source = null;
                        FileChannel destination = null;
                        boolean result = true;
                        long lastProgress = System.currentTimeMillis() - 500;
                        try {
                            source = new FileInputStream(sourceFile).getChannel();
                            destination = new FileOutputStream(destFile).getChannel();
                            long size = source.size();
                            for (long a = 0; a < size; a += 4096) {
                                destination.transferFrom(source, a, Math.min(4096, size - a));
                                if (finalProgress != null) {
                                    if (lastProgress <= System.currentTimeMillis() - 500) {
                                        lastProgress = System.currentTimeMillis();
                                        final int progress = (int) ((float) a / (float) size * 100);
                                        AndroidUtilities.runOnUIThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    finalProgress.setProgress(progress);
                                                } catch (Exception e) {
                                                    FileLog.e("tmessages", e);
                                                }
                                            }
                                        });
                                    }
                                }
                            }
                        } catch (Exception e) {
                            FileLog.e("tmessages", e);
                            result = false;
                        } finally {
                            if (source != null) {
                                source.close();
                            }
                            if (destination != null) {
                                destination.close();
                            }
                        }

                        if (result && (type == 0 || type == 1)) {
                            AndroidUtilities.addMediaToGallery(Uri.fromFile(destFile));
                        }
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                    if (finalProgress != null) {
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    finalProgress.dismiss();
                                } catch (Exception e) {
                                    FileLog.e("tmessages", e);
                                }
                            }
                        });
                    }
                }
            }).start();
        }
    }

    public GifDrawable getGifDrawable(ChatMediaCell cell, boolean create) {
        if (cell == null) {
            return null;
        }

        MessageObject messageObject = cell.getMessageObject();
        if (messageObject == null) {
            return null;
        }

        if (currentGifDrawable != null && currentGifMessageObject != null && messageObject.getId() == currentGifMessageObject.getId()) {
            currentMediaCell = cell;
            currentGifDrawable.parentView = new WeakReference<View>(cell);
            return currentGifDrawable;
        }

        if (create) {
            if (currentMediaCell != null) {
                if (currentGifDrawable != null) {
                    currentGifDrawable.stop();
                    currentGifDrawable.recycle();
                }
                currentMediaCell.clearGifImage();
            }
            currentGifMessageObject = cell.getMessageObject();
            currentMediaCell = cell;

            File cacheFile = null;
            if (currentGifMessageObject.messageOwner.attachPath != null && currentGifMessageObject.messageOwner.attachPath.length() != 0) {
                File f = new File(currentGifMessageObject.messageOwner.attachPath);
                if (f.length() > 0) {
                    cacheFile = f;
                }
            }
            if (cacheFile == null) {
                cacheFile = FileLoader.getPathToMessage(messageObject.messageOwner);
            }
            try {
                currentGifDrawable = new GifDrawable(cacheFile);
                currentGifDrawable.parentView = new WeakReference<View>(cell);
                return currentGifDrawable;
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }

        return null;
    }

    public void clearGifDrawable(ChatMediaCell cell) {
        if (cell == null) {
            return;
        }

        MessageObject messageObject = cell.getMessageObject();
        if (messageObject == null) {
            return;
        }

        if (currentGifMessageObject != null && messageObject.getId() == currentGifMessageObject.getId()) {
            if (currentGifDrawable != null) {
                currentGifDrawable.stop();
                currentGifDrawable.recycle();
                currentGifDrawable = null;
            }
            currentMediaCell = null;
            currentGifMessageObject = null;
        }
    }

    public static boolean isWebp(Uri uri) {
        ParcelFileDescriptor parcelFD = null;
        FileInputStream input = null;
        try {
            parcelFD = ApplicationLoader.applicationContext.getContentResolver().openFileDescriptor(uri, "r");
            input = new FileInputStream(parcelFD.getFileDescriptor());
            if (input.getChannel().size() > 12) {
                byte[] header = new byte[12];
                input.read(header, 0, 12);
                String str = new String(header);
                if (str != null) {
                    str = str.toLowerCase();
                    if (str.startsWith("riff") && str.endsWith("webp")){
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        } finally {
            try {
                if (parcelFD != null) {
                    parcelFD.close();
                }
            } catch (Exception e2) {
                FileLog.e("tmessages", e2);
            }
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception e2) {
                FileLog.e("tmessages", e2);
            }
        }
        return false;
    }

    public static boolean isGif(Uri uri) {
        ParcelFileDescriptor parcelFD = null;
        FileInputStream input = null;
        try {
            parcelFD = ApplicationLoader.applicationContext.getContentResolver().openFileDescriptor(uri, "r");
            input = new FileInputStream(parcelFD.getFileDescriptor());
            if (input.getChannel().size() > 3) {
                byte[] header = new byte[3];
                input.read(header, 0, 3);
                String str = new String(header);
                if (str != null && str.equalsIgnoreCase("gif")) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        } finally {
            try {
                if (parcelFD != null) {
                    parcelFD.close();
                }
            } catch (Exception e2) {
                FileLog.e("tmessages", e2);
            }
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception e2) {
                FileLog.e("tmessages", e2);
            }
        }
        return false;
    }

    public static String copyDocumentToCache(Uri uri, String ext) {
        ParcelFileDescriptor parcelFD = null;
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            int id = UserConfig.lastLocalId;
            UserConfig.lastLocalId--;
            parcelFD = ApplicationLoader.applicationContext.getContentResolver().openFileDescriptor(uri, "r");
            input = new FileInputStream(parcelFD.getFileDescriptor());
            File f = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), String.format(Locale.US, "%d.%s", id, ext));
            output = new FileOutputStream(f);
            input.getChannel().transferTo(0, input.getChannel().size(), output.getChannel());
            UserConfig.saveConfig(false);
            return f.getAbsolutePath();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        } finally {
            try {
                if (parcelFD != null) {
                    parcelFD.close();
                }
            } catch (Exception e2) {
                FileLog.e("tmessages", e2);
            }
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception e2) {
                FileLog.e("tmessages", e2);
            }
            try {
                if (output != null) {
                    output.close();
                }
            } catch (Exception e2) {
                FileLog.e("tmessages", e2);
            }
        }
        return null;
    }

    public void toggleSaveToGallery() {
        saveToGallery = !saveToGallery;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("save_gallery", saveToGallery);
        editor.commit();
        checkSaveToGalleryFiles();
    }

    public void checkSaveToGalleryFiles() {
        try {
            File telegramPath = new File(Environment.getExternalStorageDirectory(), "Telegram");
            File imagePath = new File(telegramPath, "Telegram Images");
            imagePath.mkdir();
            File videoPath = new File(telegramPath, "Telegram Video");
            videoPath.mkdir();

            if (saveToGallery) {
                if (imagePath.isDirectory()) {
                    new File(imagePath, ".nomedia").delete();
                }
                if (videoPath.isDirectory()) {
                    new File(videoPath, ".nomedia").delete();
                }
            } else {
                if (imagePath.isDirectory()) {
                    new File(imagePath, ".nomedia").createNewFile();
                }
                if (videoPath.isDirectory()) {
                    new File(videoPath, ".nomedia").createNewFile();
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public boolean canSaveToGallery() {
        return saveToGallery;
    }

    public static void loadGalleryPhotosAlbums(final int guid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<AlbumEntry> albumsSorted = new ArrayList<>();
                final ArrayList<AlbumEntry> videoAlbumsSorted = new ArrayList<>();
                HashMap<Integer, AlbumEntry> albums = new HashMap<>();
                AlbumEntry allPhotosAlbum = null;
                String cameraFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath() + "/" + "Camera/";
                Integer cameraAlbumId = null;
                Integer cameraAlbumVideoId = null;

                Cursor cursor = null;
                try {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projectionPhotos, "", null, MediaStore.Images.Media.DATE_TAKEN + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
                        int orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);

                        while (cursor.moveToNext()) {
                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            String path = cursor.getString(dataColumn);
                            long dateTaken = cursor.getLong(dateColumn);
                            int orientation = cursor.getInt(orientationColumn);

                            if (path == null || path.length() == 0) {
                                continue;
                            }

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, orientation, false);

                            if (allPhotosAlbum == null) {
                                allPhotosAlbum = new AlbumEntry(0, LocaleController.getString("AllPhotos", R.string.AllPhotos), photoEntry, false);
                                albumsSorted.add(0, allPhotosAlbum);
                            }
                            if (allPhotosAlbum != null) {
                                allPhotosAlbum.addPhoto(photoEntry);
                            }

                            AlbumEntry albumEntry = albums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry, false);
                                albums.put(bucketId, albumEntry);
                                if (cameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    albumsSorted.add(0, albumEntry);
                                    cameraAlbumId = bucketId;
                                } else {
                                    albumsSorted.add(albumEntry);
                                }
                            }

                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (cursor != null) {
                        try {
                            cursor.close();
                        } catch (Exception e) {
                            FileLog.e("tmessages", e);
                        }
                    }
                }

                try {
                    albums.clear();
                    AlbumEntry allVideosAlbum = null;
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projectionVideo, "", null, MediaStore.Video.Media.DATE_TAKEN + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN);

                        while (cursor.moveToNext()) {
                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            String path = cursor.getString(dataColumn);
                            long dateTaken = cursor.getLong(dateColumn);

                            if (path == null || path.length() == 0) {
                                continue;
                            }

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, 0, true);

                            if (allVideosAlbum == null) {
                                allVideosAlbum = new AlbumEntry(0, LocaleController.getString("AllVideo", R.string.AllVideo), photoEntry, true);
                                videoAlbumsSorted.add(0, allVideosAlbum);
                            }
                            if (allVideosAlbum != null) {
                                allVideosAlbum.addPhoto(photoEntry);
                            }

                            AlbumEntry albumEntry = albums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry, true);
                                albums.put(bucketId, albumEntry);
                                if (cameraAlbumVideoId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    videoAlbumsSorted.add(0, albumEntry);
                                    cameraAlbumVideoId = bucketId;
                                } else {
                                    videoAlbumsSorted.add(albumEntry);
                                }
                            }

                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (cursor != null) {
                        try {
                            cursor.close();
                        } catch (Exception e) {
                            FileLog.e("tmessages", e);
                        }
                    }
                }

                final Integer cameraAlbumIdFinal = cameraAlbumId;
                final Integer cameraAlbumVideoIdFinal = cameraAlbumVideoId;
                final AlbumEntry allPhotosAlbumFinal = allPhotosAlbum;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        allPhotosAlbumEntry = allPhotosAlbumFinal;
                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.albumsDidLoaded, guid, albumsSorted, cameraAlbumIdFinal, videoAlbumsSorted, cameraAlbumVideoIdFinal);
                    }
                });
            }
        }).start();
    }

    public void scheduleVideoConvert(MessageObject messageObject) {
        videoConvertQueue.add(messageObject);
        if (videoConvertQueue.size() == 1) {
            startVideoConvertFromQueue();
        }
    }

    public void cancelVideoConvert(MessageObject messageObject) {
        if (messageObject == null) {
            synchronized (videoConvertSync) {
                cancelCurrentVideoConversion = true;
            }
        } else {
            if (!videoConvertQueue.isEmpty()) {
                if (videoConvertQueue.get(0) == messageObject) {
                    synchronized (videoConvertSync) {
                        cancelCurrentVideoConversion = true;
                    }
                }
                videoConvertQueue.remove(messageObject);
            }
        }
    }

    private void startVideoConvertFromQueue() {
        if (!videoConvertQueue.isEmpty()) {
            synchronized (videoConvertSync) {
                cancelCurrentVideoConversion = false;
            }
            MessageObject messageObject = videoConvertQueue.get(0);
            Intent intent = new Intent(ApplicationLoader.applicationContext, VideoEncodingService.class);
            intent.putExtra("path", messageObject.messageOwner.attachPath);
            ApplicationLoader.applicationContext.startService(intent);
            VideoConvertRunnable.runConversion(messageObject);
        }
    }

    @SuppressLint("NewApi")
    public static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecInfo lastCodecInfo = null;
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    lastCodecInfo = codecInfo;
                    if (!lastCodecInfo.getName().equals("OMX.SEC.avc.enc")) {
                        return lastCodecInfo;
                    } else if (lastCodecInfo.getName().equals("OMX.SEC.AVC.Encoder")) {
                        return lastCodecInfo;
                    }
                }
            }
        }
        return lastCodecInfo;
    }

    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    @SuppressLint("NewApi")
    public static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        int lastColorFormat = 0;
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                lastColorFormat = colorFormat;
                if (!(codecInfo.getName().equals("OMX.SEC.AVC.Encoder") && colorFormat == 19)) {
                    return colorFormat;
                }
            }
        }
        return lastColorFormat;
    }

    @TargetApi(16)
    private int selectTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }

    private void didWriteData(final MessageObject messageObject, final File file, final boolean last, final boolean error) {
        final boolean firstWrite = videoConvertFirstWrite;
        if (firstWrite) {
            videoConvertFirstWrite = false;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                if (error) {
                    NotificationCenter.getInstance().postNotificationName(NotificationCenter.FilePreparingFailed, messageObject, file.toString());
                } else {
                    if (firstWrite) {
                        NotificationCenter.getInstance().postNotificationName(NotificationCenter.FilePreparingStarted, messageObject, file.toString());
                    }
                    NotificationCenter.getInstance().postNotificationName(NotificationCenter.FileNewChunkAvailable, messageObject, file.toString(), last ? file.length() : 0);
                }
                if (error || last) {
                    synchronized (videoConvertSync) {
                        cancelCurrentVideoConversion = false;
                    }
                    videoConvertQueue.remove(messageObject);
                    startVideoConvertFromQueue();
                }
            }
        });
    }

    @TargetApi(16)
    private long readAndWriteTrack(final MessageObject messageObject, MediaExtractor extractor, MP4Builder mediaMuxer, MediaCodec.BufferInfo info, long start, long end, File file, boolean isAudio) throws Exception {
        int trackIndex = selectTrack(extractor, isAudio);
        if (trackIndex >= 0) {
            extractor.selectTrack(trackIndex);
            MediaFormat trackFormat = extractor.getTrackFormat(trackIndex);
            int muxerTrackIndex = mediaMuxer.addTrack(trackFormat, isAudio);
            int maxBufferSize = trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            boolean inputDone = false;
            if (start > 0) {
                extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            } else {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
            long startTime = -1;

            checkConversionCanceled();

            while (!inputDone) {
                checkConversionCanceled();

                boolean eof = false;
                int index = extractor.getSampleTrackIndex();
                if (index == trackIndex) {
                    info.size = extractor.readSampleData(buffer, 0);

                    if (info.size < 0) {
                        info.size = 0;
                        eof = true;
                    } else {
                        info.presentationTimeUs = extractor.getSampleTime();
                        if (start > 0 && startTime == -1) {
                            startTime = info.presentationTimeUs;
                        }
                        if (end < 0 || info.presentationTimeUs < end) {
                            info.offset = 0;
                            info.flags = extractor.getSampleFlags();
                            if (mediaMuxer.writeSampleData(muxerTrackIndex, buffer, info, isAudio)) {
                                didWriteData(messageObject, file, false, false);
                            }
                            extractor.advance();
                        } else {
                            eof = true;
                        }
                    }
                } else if (index == -1) {
                    eof = true;
                }
                if (eof) {
                    inputDone = true;
                }
            }

            extractor.unselectTrack(trackIndex);
            return startTime;
        }
        return -1;
    }

    private static class VideoConvertRunnable implements Runnable {

        private MessageObject messageObject;

        private VideoConvertRunnable(MessageObject message) {
            messageObject = message;
        }

        @Override
        public void run() {
            MediaController.getInstance().convertVideo(messageObject);
        }

        public static void runConversion(final MessageObject obj) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        VideoConvertRunnable wrapper = new VideoConvertRunnable(obj);
                        Thread th = new Thread(wrapper, "VideoConvertRunnable");
                        th.start();
                        th.join();
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
            }).start();
        }
    }

    private void checkConversionCanceled() throws Exception {
        boolean cancelConversion;
        synchronized (videoConvertSync) {
            cancelConversion = cancelCurrentVideoConversion;
        }
        if (cancelConversion) {
            throw new RuntimeException("canceled conversion");
        }
    }

    @TargetApi(16)
    private boolean convertVideo(final MessageObject messageObject) {
        String videoPath = messageObject.videoEditedInfo.originalPath;
        long startTime = messageObject.videoEditedInfo.startTime;
        long endTime = messageObject.videoEditedInfo.endTime;
        int resultWidth = messageObject.videoEditedInfo.resultWidth;
        int resultHeight = messageObject.videoEditedInfo.resultHeight;
        int rotationValue = messageObject.videoEditedInfo.rotationValue;
        int originalWidth = messageObject.videoEditedInfo.originalWidth;
        int originalHeight = messageObject.videoEditedInfo.originalHeight;
        int bitrate = messageObject.videoEditedInfo.bitrate;
        int rotateRender = 0;
        File cacheFile = new File(messageObject.messageOwner.attachPath);

        if (Build.VERSION.SDK_INT < 18 && resultHeight > resultWidth && resultWidth != originalWidth && resultHeight != originalHeight) {
            int temp = resultHeight;
            resultHeight = resultWidth;
            resultWidth = temp;
            rotationValue = 90;
            rotateRender = 270;
        } else if (Build.VERSION.SDK_INT > 20) {
            if (rotationValue == 90) {
                int temp = resultHeight;
                resultHeight = resultWidth;
                resultWidth = temp;
                rotationValue = 0;
                rotateRender = 270;
            } else if (rotationValue == 180) {
                rotateRender = 180;
                rotationValue = 0;
            } else if (rotationValue == 270) {
                int temp = resultHeight;
                resultHeight = resultWidth;
                resultWidth = temp;
                rotationValue = 0;
                rotateRender = 90;
            }
        }

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("videoconvert", Activity.MODE_PRIVATE);
        boolean isPreviousOk = preferences.getBoolean("isPreviousOk", true);
        preferences.edit().putBoolean("isPreviousOk", false).commit();

        File inputFile = new File(videoPath);
        if (!inputFile.canRead() || !isPreviousOk) {
            didWriteData(messageObject, cacheFile, true, true);
            preferences.edit().putBoolean("isPreviousOk", true).commit();
            return false;
        }

        videoConvertFirstWrite = true;
        boolean error = false;
        long videoStartTime = startTime;

        long time = System.currentTimeMillis();

        if (resultWidth != 0 && resultHeight != 0) {
            MP4Builder mediaMuxer = null;
            MediaExtractor extractor = null;

            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                Mp4Movie movie = new Mp4Movie();
                movie.setCacheFile(cacheFile);
                movie.setRotation(rotationValue);
                movie.setSize(resultWidth, resultHeight);
                mediaMuxer = new MP4Builder().createMovie(movie);
                extractor = new MediaExtractor();
                extractor.setDataSource(inputFile.toString());

                checkConversionCanceled();

                if (resultWidth != originalWidth || resultHeight != originalHeight) {
                    int videoIndex;
                    videoIndex = selectTrack(extractor, false);
                    if (videoIndex >= 0) {
                        MediaCodec decoder = null;
                        MediaCodec encoder = null;
                        InputSurface inputSurface = null;
                        OutputSurface outputSurface = null;

                        try {
                            long videoTime = -1;
                            boolean outputDone = false;
                            boolean inputDone = false;
                            boolean decoderDone = false;
                            int swapUV = 0;
                            int videoTrackIndex = -5;

                            int colorFormat;
                            int processorType = PROCESSOR_TYPE_OTHER;
                            String manufacturer = Build.MANUFACTURER.toLowerCase();
                            if (Build.VERSION.SDK_INT < 18) {
                                MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
                                colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
                                if (colorFormat == 0) {
                                    throw new RuntimeException("no supported color format");
                                }
                                String codecName = codecInfo.getName();
                                if (codecName.contains("OMX.qcom.")) {
                                    processorType = PROCESSOR_TYPE_QCOM;
                                    if (Build.VERSION.SDK_INT == 16) {
                                        if (manufacturer.equals("lge") || manufacturer.equals("nokia")) {
                                            swapUV = 1;
                                        }
                                    }
                                } else if (codecName.contains("OMX.Intel.")) {
                                    processorType = PROCESSOR_TYPE_INTEL;
                                } else if (codecName.equals("OMX.MTK.VIDEO.ENCODER.AVC")) {
                                    processorType = PROCESSOR_TYPE_MTK;
                                } else if (codecName.equals("OMX.SEC.AVC.Encoder")) {
                                    processorType = PROCESSOR_TYPE_SEC;
                                    swapUV = 1;
                                } else if (codecName.equals("OMX.TI.DUCATI1.VIDEO.H264E")) {
                                    processorType = PROCESSOR_TYPE_TI;
                                }
                                FileLog.e("tmessages", "codec = " + codecInfo.getName() + " manufacturer = " + manufacturer + "device = " + Build.MODEL);
                            } else {
                                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                            }
                            FileLog.e("tmessages", "colorFormat = " + colorFormat);

                            int resultHeightAligned = resultHeight;
                            int padding = 0;
                            int bufferSize = resultWidth * resultHeight * 3 / 2;
                            if (processorType == PROCESSOR_TYPE_OTHER) {
                                if (resultHeight % 16 != 0) {
                                    resultHeightAligned += (16 - (resultHeight % 16));
                                    padding = resultWidth * (resultHeightAligned - resultHeight);
                                    bufferSize += padding * 5 / 4;
                                }
                            } else if (processorType == PROCESSOR_TYPE_QCOM) {
                                if (!manufacturer.toLowerCase().equals("lge")) {
                                    int uvoffset = (resultWidth * resultHeight + 2047) & ~2047;
                                    padding = uvoffset - (resultWidth * resultHeight);
                                    bufferSize += padding;
                                }
                            } else if (processorType == PROCESSOR_TYPE_TI) {
                                //resultHeightAligned = 368;
                                //bufferSize = resultWidth * resultHeightAligned * 3 / 2;
                                //resultHeightAligned += (16 - (resultHeight % 16));
                                //padding = resultWidth * (resultHeightAligned - resultHeight);
                                //bufferSize += padding * 5 / 4;
                            } else if (processorType == PROCESSOR_TYPE_MTK) {
                                if (manufacturer.equals("baidu")) {
                                    resultHeightAligned += (16 - (resultHeight % 16));
                                    padding = resultWidth * (resultHeightAligned - resultHeight);
                                    bufferSize += padding * 5 / 4;
                                }
                            }

                            extractor.selectTrack(videoIndex);
                            if (startTime > 0) {
                                extractor.seekTo(startTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            } else {
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            }
                            MediaFormat inputFormat = extractor.getTrackFormat(videoIndex);

                            MediaFormat outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, resultWidth, resultHeight);
                            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate != 0 ? bitrate : 921600);
                            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
                            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
                            if (Build.VERSION.SDK_INT < 18) {
                                outputFormat.setInteger("stride", resultWidth + 32);
                                outputFormat.setInteger("slice-height", resultHeight);
                            }

                            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
                            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            if (Build.VERSION.SDK_INT >= 18) {
                                inputSurface = new InputSurface(encoder.createInputSurface());
                                inputSurface.makeCurrent();
                            }
                            encoder.start();

                            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
                            if (Build.VERSION.SDK_INT >= 18) {
                                outputSurface = new OutputSurface();
                            } else {
                                outputSurface = new OutputSurface(resultWidth, resultHeight, rotateRender);
                            }
                            decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);
                            decoder.start();

                            final int TIMEOUT_USEC = 2500;
                            ByteBuffer[] decoderInputBuffers = null;
                            ByteBuffer[] encoderOutputBuffers = null;
                            ByteBuffer[] encoderInputBuffers = null;
                            if (Build.VERSION.SDK_INT < 21) {
                                decoderInputBuffers = decoder.getInputBuffers();
                                encoderOutputBuffers = encoder.getOutputBuffers();
                                if (Build.VERSION.SDK_INT < 18) {
                                    encoderInputBuffers = encoder.getInputBuffers();
                                }
                            }

                            checkConversionCanceled();

                            while (!outputDone) {
                                checkConversionCanceled();
                                if (!inputDone) {
                                    boolean eof = false;
                                    int index = extractor.getSampleTrackIndex();
                                    if (index == videoIndex) {
                                        int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                                        if (inputBufIndex >= 0) {
                                            ByteBuffer inputBuf;
                                            if (Build.VERSION.SDK_INT < 21) {
                                                inputBuf = decoderInputBuffers[inputBufIndex];
                                            } else {
                                                inputBuf = decoder.getInputBuffer(inputBufIndex);
                                            }
                                            int chunkSize = extractor.readSampleData(inputBuf, 0);
                                            if (chunkSize < 0) {
                                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                                inputDone = true;
                                            } else {
                                                decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, extractor.getSampleTime(), 0);
                                                extractor.advance();
                                            }
                                        }
                                    } else if (index == -1) {
                                        eof = true;
                                    }
                                    if (eof) {
                                        int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                                        if (inputBufIndex >= 0) {
                                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                            inputDone = true;
                                        }
                                    }
                                }

                                boolean decoderOutputAvailable = !decoderDone;
                                boolean encoderOutputAvailable = true;
                                while (decoderOutputAvailable || encoderOutputAvailable) {
                                    checkConversionCanceled();
                                    int encoderStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                        encoderOutputAvailable = false;
                                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                        if (Build.VERSION.SDK_INT < 21) {
                                            encoderOutputBuffers = encoder.getOutputBuffers();
                                        }
                                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                        MediaFormat newFormat = encoder.getOutputFormat();
                                        if (videoTrackIndex == -5) {
                                            videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                                        }
                                    } else if (encoderStatus < 0) {
                                        throw new RuntimeException("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                                    } else {
                                        ByteBuffer encodedData;
                                        if (Build.VERSION.SDK_INT < 21) {
                                            encodedData = encoderOutputBuffers[encoderStatus];
                                        } else {
                                            encodedData = encoder.getOutputBuffer(encoderStatus);
                                        }
                                        if (encodedData == null) {
                                            throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                                        }
                                        if (info.size > 1) {
                                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                                if (mediaMuxer.writeSampleData(videoTrackIndex, encodedData, info, false)) {
                                                    didWriteData(messageObject, cacheFile, false, false);
                                                }
                                            } else if (videoTrackIndex == -5) {
                                                byte[] csd = new byte[info.size];
                                                encodedData.limit(info.offset + info.size);
                                                encodedData.position(info.offset);
                                                encodedData.get(csd);
                                                ByteBuffer sps = null;
                                                ByteBuffer pps = null;
                                                for (int a = info.size - 1; a >= 0; a--) {
                                                    if (a > 3) {
                                                        if (csd[a] == 1 && csd[a - 1] == 0 && csd[a - 2] == 0 && csd[a - 3] == 0) {
                                                            sps = ByteBuffer.allocate(a - 3);
                                                            pps = ByteBuffer.allocate(info.size - (a - 3));
                                                            sps.put(csd, 0, a - 3).position(0);
                                                            pps.put(csd, a - 3, info.size - (a - 3)).position(0);
                                                            break;
                                                        }
                                                    } else {
                                                        break;
                                                    }
                                                }

                                                MediaFormat newFormat = MediaFormat.createVideoFormat(MIME_TYPE, resultWidth, resultHeight);
                                                if (sps != null && pps != null) {
                                                    newFormat.setByteBuffer("csd-0", sps);
                                                    newFormat.setByteBuffer("csd-1", pps);
                                                }
                                                videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                                            }
                                        }
                                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                                        encoder.releaseOutputBuffer(encoderStatus, false);
                                    }
                                    if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                                        continue;
                                    }

                                    if (!decoderDone) {
                                        int decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                                        if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                            decoderOutputAvailable = false;
                                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                            MediaFormat newFormat = decoder.getOutputFormat();
                                            FileLog.e("tmessages", "newFormat = " + newFormat);
                                        } else if (decoderStatus < 0) {
                                            throw new RuntimeException("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                                        } else {
                                            boolean doRender;
                                            if (Build.VERSION.SDK_INT >= 18) {
                                                doRender = info.size != 0;
                                            } else {
                                                doRender = info.size != 0 || info.presentationTimeUs != 0;
                                            }
                                            if (endTime > 0 && info.presentationTimeUs >= endTime) {
                                                inputDone = true;
                                                decoderDone = true;
                                                doRender = false;
                                                info.flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                                            }
                                            if (startTime > 0 && videoTime == -1) {
                                                if (info.presentationTimeUs < startTime) {
                                                    doRender = false;
                                                    FileLog.e("tmessages", "drop frame startTime = " + startTime + " present time = " + info.presentationTimeUs);
                                                } else {
                                                    videoTime = info.presentationTimeUs;
                                                }
                                            }
                                            decoder.releaseOutputBuffer(decoderStatus, doRender);
                                            if (doRender) {
                                                boolean errorWait = false;
                                                try {
                                                    outputSurface.awaitNewImage();
                                                } catch (Exception e) {
                                                    errorWait = true;
                                                    FileLog.e("tmessages", e);
                                                }
                                                if (!errorWait) {
                                                    if (Build.VERSION.SDK_INT >= 18) {
                                                        outputSurface.drawImage(false);
                                                        inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                                                        inputSurface.swapBuffers();
                                                    } else {
                                                        int inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                                                        if (inputBufIndex >= 0) {
                                                            outputSurface.drawImage(true);
                                                            ByteBuffer rgbBuf = outputSurface.getFrame();
                                                            ByteBuffer yuvBuf = encoderInputBuffers[inputBufIndex];
                                                            yuvBuf.clear();
                                                            Utilities.convertVideoFrame(rgbBuf, yuvBuf, colorFormat, resultWidth, resultHeight, padding, swapUV);
                                                            encoder.queueInputBuffer(inputBufIndex, 0, bufferSize, info.presentationTimeUs, 0);
                                                        } else {
                                                            FileLog.e("tmessages", "input buffer not available");
                                                        }
                                                    }
                                                }
                                            }
                                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                                decoderOutputAvailable = false;
                                                FileLog.e("tmessages", "decoder stream end");
                                                if (Build.VERSION.SDK_INT >= 18) {
                                                    encoder.signalEndOfInputStream();
                                                } else {
                                                    int inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                                                    if (inputBufIndex >= 0) {
                                                        encoder.queueInputBuffer(inputBufIndex, 0, 1, info.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (videoTime != -1) {
                                videoStartTime = videoTime;
                            }
                        } catch (Exception e) {
                            FileLog.e("tmessages", e);
                            error = true;
                        }

                        extractor.unselectTrack(videoIndex);

                        if (outputSurface != null) {
                            outputSurface.release();
                        }
                        if (inputSurface != null) {
                            inputSurface.release();
                        }
                        if (decoder != null) {
                            decoder.stop();
                            decoder.release();
                        }
                        if (encoder != null) {
                            encoder.stop();
                            encoder.release();
                        }

                        checkConversionCanceled();
                    }
                } else {
                    long videoTime = readAndWriteTrack(messageObject, extractor, mediaMuxer, info, startTime, endTime, cacheFile, false);
                    if (videoTime != -1) {
                        videoStartTime = videoTime;
                    }
                }
                if (!error) {
                    readAndWriteTrack(messageObject, extractor, mediaMuxer, info, videoStartTime, endTime, cacheFile, true);
                }
            } catch (Exception e) {
                error = true;
                FileLog.e("tmessages", e);
            } finally {
                if (extractor != null) {
                    extractor.release();
                }
                if (mediaMuxer != null) {
                    try {
                        mediaMuxer.finishMovie(false);
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
                FileLog.e("tmessages", "time = " + (System.currentTimeMillis() - time));
            }
        } else {
            preferences.edit().putBoolean("isPreviousOk", true).commit();
            didWriteData(messageObject, cacheFile, true, true);
            return false;
        }
        preferences.edit().putBoolean("isPreviousOk", true).commit();
        didWriteData(messageObject, cacheFile, true, error);
        return true;
    }
}
