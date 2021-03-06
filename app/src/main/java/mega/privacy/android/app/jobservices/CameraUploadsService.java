package mega.privacy.android.app.jobservices;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.media.ExifInterface;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mega.privacy.android.app.AndroidCompletedTransfer;
import mega.privacy.android.app.DatabaseHandler;
import mega.privacy.android.app.MegaApplication;
import mega.privacy.android.app.MegaPreferences;
import mega.privacy.android.app.MimeTypeList;
import mega.privacy.android.app.R;
import mega.privacy.android.app.UserCredentials;
import mega.privacy.android.app.VideoCompressor;
import mega.privacy.android.app.lollipop.ManagerActivityLollipop;
import mega.privacy.android.app.lollipop.megachat.ChatSettings;
import mega.privacy.android.app.receivers.NetworkTypeChangeReceiver;
import mega.privacy.android.app.utils.conversion.VideoCompressionCallback;
import nz.mega.sdk.MegaApiAndroid;
import nz.mega.sdk.MegaApiJava;
import nz.mega.sdk.MegaChatApi;
import nz.mega.sdk.MegaChatApiAndroid;
import nz.mega.sdk.MegaChatApiJava;
import nz.mega.sdk.MegaChatError;
import nz.mega.sdk.MegaChatRequest;
import nz.mega.sdk.MegaChatRequestListenerInterface;
import nz.mega.sdk.MegaError;
import nz.mega.sdk.MegaNode;
import nz.mega.sdk.MegaNodeList;
import nz.mega.sdk.MegaRequest;
import nz.mega.sdk.MegaRequestListenerInterface;
import nz.mega.sdk.MegaTransfer;
import nz.mega.sdk.MegaTransferListenerInterface;

import static mega.privacy.android.app.utils.Constants.*;
import static mega.privacy.android.app.utils.FileUtil.getFullPathFromTreeUri;
import static mega.privacy.android.app.utils.FileUtils.*;
import static mega.privacy.android.app.jobservices.SyncRecord.*;
import static mega.privacy.android.app.lollipop.managerSections.SettingsFragmentLollipop.VIDEO_QUALITY_MEDIUM;
import static mega.privacy.android.app.receivers.NetworkTypeChangeReceiver.MOBILE;
import static mega.privacy.android.app.utils.ImageProcessor.*;
import static mega.privacy.android.app.utils.JobUtil.*;
import static mega.privacy.android.app.utils.LogUtil.*;
import static mega.privacy.android.app.utils.PreviewUtils.*;
import static mega.privacy.android.app.utils.SDCardUtils.*;
import static mega.privacy.android.app.utils.ThumbnailUtils.*;
import static mega.privacy.android.app.utils.Util.*;

public class CameraUploadsService extends Service implements NetworkTypeChangeReceiver.OnNetworkTypeChangeCallback, MegaChatRequestListenerInterface, MegaRequestListenerInterface, MegaTransferListenerInterface, VideoCompressionCallback {

    private static final String OVER_QUOTA_NOTIFICATION_CHANNEL_ID = "overquotanotification";
    private static final String ERROR_NOT_ENOUGH_SPACE = "ERROR_NOT_ENOUGH_SPACE";
    private static final String ERROR_CREATE_FILE_IO_ERROR = "ERROR_CREATE_FILE_IO_ERROR";
    private static final String ERROR_SOURCE_FILE_NOT_EXIST = "SOURCE_FILE_NOT_EXIST";
    private static final int BATTERY_STATE_LOW = 20;
    private static final int LOW_BATTERY_LEVEL = 20;
    public static String PHOTO_SYNC = "PhotoSync";
    public static String CAMERA_UPLOADS = "Camera Uploads";
    public static String SECONDARY_UPLOADS = "Media Uploads";
    public static String ACTION_CANCEL = "CANCEL_SYNC";
    public static String ACTION_STOP = "STOP_SYNC";
    public static String ACTION_CANCEL_ALL = "CANCEL_ALL";
    public static String ACTION_LOGOUT = "LOGOUT_SYNC";
    public static String ACTION_LIST_PHOTOS_VIDEOS_NEW_FOLDER = "PHOTOS_VIDEOS_NEW_FOLDER";
    public static String CU_CACHE_FOLDER = "cu";
    public static int PAGE_SIZE = 200;
    public static int PAGE_SIZE_VIDEO = 10;
    public static boolean isServiceRunning = false;
    
    private NotificationCompat.Builder mBuilder;
    private NotificationManager mNotificationManager;
    
    private int notificationId = NOTIFICATION_CAMERA_UPLOADS;
    private String notificationChannelId = NOTIFICATION_CHANNEL_CAMERA_UPLOADS_ID;
    private String notificationChannelName = NOTIFICATION_CHANNEL_CAMERA_UPLOADS_NAME;
    
    private Thread task;
    
    public static boolean running = false;
    private Handler handler;
    
    private ExecutorService threadPool = Executors.newCachedThreadPool();
    
    private WifiManager.WifiLock lock;
    private PowerManager.WakeLock wl;
    
    private boolean isOverQuota = false;
    private boolean canceled;
    private boolean stopByNetworkStateChange;
    
    private DatabaseHandler dbH;
    
    private MegaPreferences prefs;
    private String localPath = "";
    private boolean removeGPS = true;
    private ChatSettings chatSettings;
    private long cameraUploadHandle = -1;
    private boolean secondaryEnabled = false;
    private String localPathSecondary = "";
    private long secondaryUploadHandle = -1;
    private MegaNode secondaryUploadNode = null;
    
    private boolean isLoggingIn = false;
    
    private MegaApiAndroid megaApi;
    private MegaChatApiAndroid megaChatApi;
    private MegaApplication app;
    
    private int LOGIN_IN = 12;
    
    private long lastUpdated = 0;
    private static String gSession;
    private boolean isSec;
    private boolean stopped = false;
    private NetworkTypeChangeReceiver receiver;
    
    public class Media {
        public String filePath;
        public long timestamp;
    }
    
    private Queue<Media> cameraFiles = new LinkedList<>();
    private Queue<Media> primaryVideos = new LinkedList<>();
    private Queue<Media> secondaryVideos = new LinkedList<>();
    private ArrayList<SyncRecord> pendingUploadsList = new ArrayList<>();
    private ArrayList<SyncRecord> pendingUploadsListSecondary = new ArrayList<>();
    private ArrayList<SyncRecord> pendingVideoUploadsList = new ArrayList<>();
    private ArrayList<SyncRecord> pendingVideoUploadsListSecondary = new ArrayList<>();
    private Queue<Media> mediaFilesSecondary = new LinkedList<>();
    private MegaNode cameraUploadNode = null;
    private int totalUploaded;
    private int totalToUpload;
    private List<MegaTransfer> cuTransfers = new ArrayList<>();
    
    private long currentTimeStamp = 0;
    private long secondaryTimeStamp = 0;
    private long currentVideoTimeStamp = 0;
    private long secondaryVideoTimeStamp = 0;
    private Notification mNotification;
    private Intent mIntent, batteryIntent;
    private PendingIntent mPendingIntent;
    private String tempRoot;
    private Context mContext;
    private VideoCompressor mVideoCompressor;

    private BroadcastReceiver chargingStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context,Intent intent) {
            if(mVideoCompressor != null && isChargingRequired(mVideoCompressor.getTotalInputSize() / (1024 * 1024))){
                logDebug("Detected device stops charging");
                mVideoCompressor.stop();
            }
        }
    };

    private BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context context, Intent intent) {
            batteryIntent = intent;
            if(isDeviceLowOnBattery(batteryIntent)){
                stopped = true;
                if(megaApi != null){
                    for(MegaTransfer transfer : cuTransfers) {
                        megaApi.cancelTransfer(transfer);
                    }
                }
                finish();
            }
        }
    };
    
    @Override
    public void onCreate() {
        registerReceiver(chargingStopReceiver,new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
        registerReceiver(batteryInfoReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    public void onDestroy() {
        logDebug("onDestroy()");
        super.onDestroy();
        isServiceRunning = false;
        if(receiver != null) {
            unregisterReceiver(receiver);
        }
        if(chargingStopReceiver != null) {
            unregisterReceiver(chargingStopReceiver);
        }
        if(batteryInfoReceiver != null){
            unregisterReceiver(batteryInfoReceiver);
        }
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onTypeChanges(int type) {
        logDebug("onTypeChanges: " + type);
        DatabaseHandler handler = DatabaseHandler.getDbHandler(this);
        MegaPreferences prefs = handler.getPreferences();
        if (prefs != null) {
            stopByNetworkStateChange = type == MOBILE && Boolean.valueOf(prefs.getCamSyncWifi());
            if (stopByNetworkStateChange) {
                for (MegaTransfer transfer : cuTransfers) {
                    megaApi.cancelTransfer(transfer, this);
                }
                stopped = true;
                finish();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent,int flags,int startId) {
        logDebug("Starting CU service (flags: " + flags + ", startId: " + startId + ")");
        isServiceRunning = true;
        mContext = getApplicationContext();
        mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        Notification notification = createNotification(getString(R.string.section_photo_sync),getString(R.string.settings_camera_notif_initializing_title),null,false);
        startForeground(notificationId,notification);
        initService();

        if (megaApi == null) {
            logError("megaApi is null");
            finish();
            return START_NOT_STICKY;
        }
        
        if (intent != null && intent.getAction() != null) {
            if (intent.getAction().equals(ACTION_CANCEL) ||
                    intent.getAction().equals(ACTION_STOP) ||
                    intent.getAction().equals(ACTION_LIST_PHOTOS_VIDEOS_NEW_FOLDER) ) {
                logDebug("Intent action is " + intent.getAction());
                for(MegaTransfer transfer : cuTransfers) {
                    megaApi.cancelTransfer(transfer,this);
                }
            } else if(ACTION_CANCEL_ALL.equals(intent.getAction()) || intent.getAction().equals(ACTION_LOGOUT)) {
                logDebug("Intent action is " + intent.getAction());
                megaApi.cancelTransfers(MegaTransfer.TYPE_UPLOAD,this);
            }
            stopped = true;
            finish();
            return START_NOT_STICKY;
        }
        
        try {
            logDebug("Start service here, creating new thread");
            task = createWorkerThread();
            task.start();
        } catch (Exception e) {
            logError("Exception", e);
            finish();
        }
        logDebug("STARTS NOW");
        return START_NOT_STICKY;
    }

    private void registerNetworkTypeChangeReceiver() {
        if(receiver != null) {
            unregisterReceiver(receiver);
        }
        receiver = new NetworkTypeChangeReceiver();
        receiver.setCallback(this);
        registerReceiver(receiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
    }
    
    private Thread createWorkerThread() {
        return new Thread() {
            @Override
            public void run() {
                try {
                    int result = shouldRun();
                    logDebug("onStartJob should run result: " + result + "");
                    if (result == 0) {
                        startCameraUploads();
                    } else if (result == LOGIN_IN) {
                        logDebug("Waiting for login");
                    } else {
                        finish();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    handleException(e);
                }
            }
        };
    }
    
    private void startCameraUploads() {
        logDebug("startCameraUploads");
        showNotification(getString(R.string.section_photo_sync),getString(R.string.settings_camera_notif_checking_title),mPendingIntent,false);
        getFilesFromMediaStore();
    }
    
    private boolean shouldCompressVideo() {
        String qualitySetting = prefs.getUploadVideoQuality();
        if (qualitySetting != null && Integer.parseInt(qualitySetting) == VIDEO_QUALITY_MEDIUM) {
            return true;
        }
        return false;
    }
    
    private void extractMedia(Cursor cursorCamera,boolean isSecondary,boolean isVideo) {
        try {

            logDebug("if (cursorCamera != null)");
            String path = isSecondary ? localPathSecondary : localPath;
            
            int dataColumn = cursorCamera.getColumnIndex(MediaStore.MediaColumns.DATA);
            int modifiedColumn = 0, addedColumn = 0;
            if (cursorCamera.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED) != -1) {
                modifiedColumn = cursorCamera.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            }
            if (cursorCamera.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED) != -1) {
                addedColumn = cursorCamera.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
            }

            while (cursorCamera.moveToNext()) {
                
                Media media = new Media();
                media.filePath = cursorCamera.getString(dataColumn);
                long addedTime = cursorCamera.getLong(addedColumn) * 1000;
                long modifiedTime = cursorCamera.getLong(modifiedColumn) * 1000;
                media.timestamp = addedTime > modifiedTime ? addedTime : modifiedTime;

                logDebug("while(cursorCamera.moveToNext()) - media.filePath: " + media.filePath + "_localPath: " + path);
                
                //Check files of the Camera Uploads
                if (checkFile(media,path)) {
                    if (isSecondary) {
                        if (isVideo) {
                            secondaryVideos.add(media);
                        } else {
                            mediaFilesSecondary.add(media);
                        }
                    } else {
                        if (isVideo) {
                            primaryVideos.add(media);
                        } else {
                            cameraFiles.add(media);
                        }
                    }
                    logDebug("Camera Files added: " + media.filePath);
                }
            }
        } catch (Exception e) {
            logError("Exception cursorSecondary", e);
        }
    }
    
    private void getFilesFromMediaStore() {
        logDebug("getFilesFromMediaStore()");
        cameraUploadNode = megaApi.getNodeByHandle(cameraUploadHandle);
        if (cameraUploadNode == null) {
            logError("ERROR: cameraUploadNode == null");
            finish();
            return;
        }
        
        if (!wl.isHeld()) {
            wl.acquire();
        }
        if (!lock.isHeld()) {
            lock.acquire();
        }
        
        String projection[] = {
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        
        String selectionCamera = null;
        String selectionCameraVideo = null;
        String selectionSecondary = null;
        String selectionSecondaryVideo = null;
        String[] selectionArgs = null;
        prefs = dbH.getPreferences();
        
        if (prefs != null) {
            logDebug("if (prefs != null)");
            if (prefs.getCamSyncTimeStamp() != null) {
                currentTimeStamp = Long.parseLong(prefs.getCamSyncTimeStamp());
            } else {
                currentTimeStamp = 0;
            }
            selectionCamera = "((" + MediaStore.MediaColumns.DATE_MODIFIED + "*1000) > " + currentTimeStamp + " OR " + "(" + MediaStore.MediaColumns.DATE_ADDED + "*1000) > " + currentTimeStamp + ") AND " + MediaStore.MediaColumns.DATA + " LIKE '" + localPath + "%'";
            logDebug("SELECTION photo: " + selectionCamera);

            if (prefs.getCamVideoSyncTimeStamp() != null) {
                currentVideoTimeStamp = Long.parseLong(prefs.getCamVideoSyncTimeStamp());
            } else {
                currentVideoTimeStamp = 0;
            }
            selectionCameraVideo = "((" + MediaStore.MediaColumns.DATE_MODIFIED + "*1000) > " + currentVideoTimeStamp + " OR " + "(" + MediaStore.MediaColumns.DATE_ADDED + "*1000) > " + currentVideoTimeStamp + ") AND " + MediaStore.MediaColumns.DATA + " LIKE '" + localPath + "%'";
            logDebug("SELECTION video: " + selectionCameraVideo);

            if (secondaryEnabled) {
                logDebug("if(secondaryEnabled)");
                if (prefs.getSecSyncTimeStamp() != null) {
                    secondaryTimeStamp = Long.parseLong(prefs.getSecSyncTimeStamp());
                    selectionSecondary = "((" + MediaStore.MediaColumns.DATE_MODIFIED + "*1000) > " + secondaryTimeStamp + " OR " + "(" + MediaStore.MediaColumns.DATE_ADDED + "*1000) > " + secondaryTimeStamp + ") AND " + MediaStore.MediaColumns.DATA + " LIKE '" + localPathSecondary + "%'";
                    logDebug("SELECTION SECONDARY photo: " + selectionSecondary);
                }
                if (prefs.getSecVideoSyncTimeStamp() != null) {
                    secondaryVideoTimeStamp = Long.parseLong(prefs.getSecVideoSyncTimeStamp());
                    selectionSecondaryVideo = "((" + MediaStore.MediaColumns.DATE_MODIFIED + "*1000) > " + secondaryVideoTimeStamp + " OR " + "(" + MediaStore.MediaColumns.DATE_ADDED + "*1000) > " + secondaryVideoTimeStamp + ") AND " + MediaStore.MediaColumns.DATA + " LIKE '" + localPathSecondary + "%'";
                    logDebug("SELECTION SECONDARY video: " + selectionSecondaryVideo);
                }
            }
        }
        
        ArrayList<Uri> uris = new ArrayList<>();
        if (prefs.getCamSyncFileUpload() == null) {
            logDebug("if (prefs.getCamSyncFileUpload() == null)");
            dbH.setCamSyncFileUpload(MegaPreferences.ONLY_PHOTOS);
            uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            uris.add(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        } else {
            logDebug("if (prefs.getCamSyncFileUpload() != null)");
            switch (Integer.parseInt(prefs.getCamSyncFileUpload())) {
                case MegaPreferences.ONLY_PHOTOS: {
                    logDebug("case MegaPreferences.ONLY_PHOTOS:");
                    uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                    break;
                }
                case MegaPreferences.ONLY_VIDEOS: {
                    logDebug("case MegaPreferences.ONLY_VIDEOS:");
                    uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Video.Media.INTERNAL_CONTENT_URI);
                    break;
                }
                case MegaPreferences.PHOTOS_AND_VIDEOS: {
                    logDebug("case MegaPreferences.PHOTOS_AND_VIDEOS:");
                    uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                    uris.add(MediaStore.Video.Media.INTERNAL_CONTENT_URI);
                    break;
                }
            }
        }
        
        for (int i = 0;i < uris.size();i++) {
            Uri uri = uris.get(i);
            boolean isVideo = uri.equals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) || uri.equals(MediaStore.Video.Media.INTERNAL_CONTENT_URI);
            
            //Primary Media Folder
            Cursor cursorCamera;
            String orderVideo = MediaStore.MediaColumns.DATE_MODIFIED;
            String orderImage = MediaStore.MediaColumns.DATE_MODIFIED;
            if(!isLocalFolderOnSDCard(this,localPath)) {
                orderVideo += " ASC LIMIT 0," + PAGE_SIZE_VIDEO;
                orderImage += " ASC LIMIT 0," + PAGE_SIZE;
            }
            if (isVideo) {
                cursorCamera = app.getContentResolver().query(uri,projection,selectionCameraVideo,selectionArgs,orderVideo);
            } else {
                cursorCamera = app.getContentResolver().query(uri,projection,selectionCamera,selectionArgs,orderImage);
            }
            if (cursorCamera != null) {
                extractMedia(cursorCamera,false,isVideo);
            }
            
            //Secondary Media Folder
            if (secondaryEnabled) {
                logDebug("if(secondaryEnabled)");
                Cursor cursorSecondary;
                String orderVideoSecondary = MediaStore.MediaColumns.DATE_MODIFIED;
                String orderImageSecondary = MediaStore.MediaColumns.DATE_MODIFIED;
                if(!isLocalFolderOnSDCard(this,localPathSecondary)) {
                    orderVideoSecondary += " ASC LIMIT 0," + PAGE_SIZE_VIDEO;
                    orderImageSecondary += " ASC LIMIT 0," + PAGE_SIZE;
                }
                if (isVideo) {
                    cursorSecondary = app.getContentResolver().query(uri,projection,selectionSecondaryVideo,selectionArgs,orderVideoSecondary);
                } else {
                    cursorSecondary = app.getContentResolver().query(uri,projection,selectionSecondary,selectionArgs,orderImageSecondary);
                }
                
                if (cursorSecondary != null) {
                    extractMedia(cursorSecondary,true,isVideo);
                }
            }
        }
        
        totalUploaded = 0;
        prepareUpload(cameraFiles,mediaFilesSecondary,primaryVideos,secondaryVideos);
    }
    
    private void prepareUpload(Queue<Media> primaryList,Queue<Media> secondaryList,Queue<Media> primaryVideoList,Queue<Media> secondaryVideoList) {
        logDebug("primaryList " + primaryList.size() + " secondaryList " + secondaryList.size() + " primaryVideoList " + primaryVideoList.size() + " secondaryVideoList " + secondaryVideoList.size());
        pendingUploadsList = getPendingList(primaryList,false,false);
        saveDataToDB(pendingUploadsList);
        prefs = dbH.getPreferences();
        pendingVideoUploadsList = getPendingList(primaryVideoList,false,true);
        saveDataToDB(pendingVideoUploadsList);
        
        //secondary list
        if (secondaryEnabled) {
            pendingUploadsListSecondary = getPendingList(secondaryList,true,false);
            saveDataToDB(pendingUploadsListSecondary);
            pendingVideoUploadsListSecondary = getPendingList(secondaryVideoList,true,true);
            saveDataToDB(pendingVideoUploadsListSecondary);
        }
        if(stopped) {
            return;
        }
        //need to maintain timestamp for better performance
        updateTimeStamp();
        List<SyncRecord> finalList = dbH.findAllPendingSyncRecords();

        if (finalList.size() == 0) {
            logDebug("Pending upload list is empty, now check view compression status");
            if (isCompressedVideoPending()) {
                startVideoCompression();
            } else {
                logDebug("Nothing to upload");
                finish();
                purgeDirectory(new File(tempRoot));
                return;
            }
        } else {
            logDebug("Got pending uploads " + finalList.size());
            startParallelUpload(finalList,false);
        }
    }
    
    private void startParallelUpload(List<SyncRecord> finalList,boolean isCompressedVideo) {
        for (SyncRecord file : finalList) {
            if (!running) {
                break;
            }
            isSec = file.isSecondary();
            MegaNode parent;
            if (isSec) {
                parent = secondaryUploadNode;
            } else {
                parent = cameraUploadNode;
            }

            if (file.getType() == SyncRecord.TYPE_PHOTO && !file.isCopyOnly()) {
                if(removeGPS) {
                    String newPath = createTempFile(file);
                    //IOException occurs.
                    if (ERROR_CREATE_FILE_IO_ERROR.equals(newPath)) {
                        continue;
                    }

                    // only retry for 60 seconds
                    int counter = 60;
                    while (ERROR_NOT_ENOUGH_SPACE.equals(newPath) && running && counter != 0) {
                        counter--;
                        try {
                            logDebug("Waiting for disk space to process");
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        //show no space notification
                        if (megaApi.getNumPendingUploads() == 0) {
                            logWarning("Stop service due to out of space issue");
                            finish();
                            String title = getString(R.string.title_out_of_space);
                            String message = getString(R.string.error_not_enough_free_space);
                            Intent intent = new Intent(this,ManagerActivityLollipop.class);
                            PendingIntent pendingIntent = PendingIntent.getActivity(this,0,intent,0);
                            showNotification(title,message,pendingIntent,true);
                            return;
                        }
                        newPath = createTempFile(file);
                    }
                    if (!newPath.equals(file.getNewPath())) {
                        file.setNewPath(newPath);
                    }
                } else {
                    // set as don't remove GPS
                    file.setNewPath(file.getLocalPath());
                }
            }

            String path;
            if (isCompressedVideo || file.getType() == SyncRecord.TYPE_PHOTO || (file.getType() == SyncRecord.TYPE_VIDEO && shouldCompressVideo())) {
                path = file.getNewPath();
                File temp = new File(path);
                if (!temp.exists()) {
                    path = file.getLocalPath();
                }
            } else {
                path = file.getLocalPath();
            }

            if (file.isCopyOnly()) {
                logDebug("Copy node " + file.getFileName());
                totalToUpload++;
                megaApi.copyNode(megaApi.getNodeByHandle(file.getNodeHandle()),parent,file.getFileName(),this);
            } else {
                File toUpload = new File(path);
                if (toUpload.exists()) {
                    logDebug("Upload node " + path);
                    //compare size
                    MegaNode node = checkExsitBySize(parent,toUpload.length());
                    if(node != null && node.getOriginalFingerprint() == null) {
                        dbH.deleteSyncRecordByPath(path,isSec);
                    } else {
                        totalToUpload++;
                        long lastModified = getLastModifiedTime(file);
                        megaApi.startUpload(path,parent,file.getFileName(),lastModified / 1000,this);
                    }
                } else {
                    dbH.deleteSyncRecordByPath(path,isSec);
                }
            }
        }
        if (totalToUpload == totalUploaded) {
            if (isCompressedVideoPending() && !canceled && isCompressorAvailable()) {
                logDebug("Got pending videos, will start compress");
                startVideoCompression();
            } else {
                logDebug("No pending videos, finish");
                onQueueComplete();
            }
        }
    }

    private MegaNode checkExsitBySize(MegaNode parent,long size) {
        ArrayList<MegaNode> nL = megaApi.getChildren(parent,MegaApiJava.ORDER_ALPHABETICAL_ASC);
        for (MegaNode node : nL) {
            if(node.getSize() == size) {
                return node;
            }
        }
        return null;
    }

    private long getLastModifiedTime(SyncRecord file) {
        File source = new File(file.getLocalPath());
        return source.lastModified();
    }
    
    private void saveDataToDB(ArrayList<SyncRecord> list) {
        logDebug("List length is " + list.size());
        for (SyncRecord file : list) {
            if(stopped) {
                return;
            }
            SyncRecord exist = dbH.recordExists(file.getOriginFingerprint(),file.isSecondary(),file.isCopyOnly());
            if (exist != null) {
                if (exist.getTimestamp() < file.getTimestamp()) {
                    logDebug("Got newer time stamp");
                    dbH.deleteSyncRecordByLocalPath(exist.getLocalPath(),exist.isSecondary());
                } else {
                    logWarning("Duplicated sync records");
                    continue;
                }
            }
            
            boolean isSec = file.isSecondary();
            MegaNode parent;
            if (isSec) {
                parent = secondaryUploadNode;
            } else {
                parent = cameraUploadNode;
            }
            logDebug("is Secondary " + isSec);
            
            if (file.isCopyOnly()) {
                //file exist in other location, server will copy internally
            } else {
                File f = new File(file.getLocalPath());
                if (!f.exists()) {
                    logWarning("File does not exist, remove from DB");
                    dbH.deleteSyncRecordByLocalPath(file.getLocalPath(),isSec);
                    continue;
                }
            }
            
            String fileName;
            boolean inCloud;
            boolean inDatabase;
            int photoIndex = 0;
            if (Boolean.parseBoolean(prefs.getKeepFileNames())) {
                //Keep the file names as device but need to handle same file name in different location
                String tempFileName = file.getFileName();
                
                do {
                    if(stopped) {
                        return;
                    }
                    fileName = getNoneDuplicatedDeviceFileName(tempFileName,photoIndex);
                    photoIndex++;
                    
                    inCloud = megaApi.getChildNode(parent,fileName) != null;
                    inDatabase = dbH.fileNameExists(fileName,isSec,SyncRecord.TYPE_ANY);
                } while ((inCloud || inDatabase));
            } else {
                do {
                    if(stopped) {
                        return;
                    }
                    fileName = getPhotoSyncNameWithIndex(getLastModifiedTime(file),file.getLocalPath(),photoIndex);
                    photoIndex++;
                    
                    inCloud = megaApi.getChildNode(parent,fileName) != null;
                    inDatabase = dbH.fileNameExists(fileName,isSec,SyncRecord.TYPE_ANY);
                } while ((inCloud || inDatabase));
            }
            
            String extension = "";
            String[] s = fileName.split("\\.");
            if (s != null && s.length > 0) {
                if (s.length > 0) {
                    extension = s[s.length - 1];
                }
            }
            
            file.setFileName(fileName);
            file.setNewPath(tempRoot + System.nanoTime() + "." + extension);
            logDebug("File name is " + fileName + "temp path is " + file.getNewPath());
            dbH.saveSyncRecord(file);
        }
    }
    
    private void onQueueComplete() {
        logDebug("Stopping foreground!");

        if(megaApi.getNumPendingUploads() <= 0) {
            megaApi.resetTotalUploads();
        }
        totalUploaded = 0;
        totalToUpload = 0;
        
        finish();
    }
    
    private ArrayList<SyncRecord> getPendingList(Queue<Media> mediaList,boolean isSecondary,boolean isVideo) {
        ArrayList<SyncRecord> pendingList = new ArrayList<>();
        MegaNode uploadNode;
        if (isSecondary) {
            uploadNode = megaApi.getNodeByHandle(secondaryUploadHandle);
        } else {
            uploadNode = megaApi.getNodeByHandle(cameraUploadHandle);
        }
        long uploadNodeHandle = uploadNode.getHandle();
        int type = isVideo ? SyncRecord.TYPE_VIDEO : SyncRecord.TYPE_PHOTO;

        while (mediaList.size() > 0) {
            if(stopped) {
                break;
            }
            logDebug("if (mediaList.size() > 0)");
            final Media media = mediaList.poll();
            if (dbH.localPathExists(localPath,isSecondary,SyncRecord.TYPE_ANY)) {
                continue;
            }
            
            //Source file
            File sourceFile = new File(media.filePath);
            String localFingerPrint = megaApi.getFingerprint(media.filePath);
            MegaNode nodeExists = null;
            
            try {
                nodeExists = getPossibleNodeFromCloud(localFingerPrint,uploadNode);
            } catch (Exception e) {
                logError("Exception, can not get possible nodes from cloud", e);
            }

            if (nodeExists == null) {
                logDebug("UPLOAD THE FILE: " + media.filePath);
                SyncRecord record = new SyncRecord(sourceFile.getAbsolutePath(),sourceFile.getName(),media.timestamp,isSecondary,type);
                if (shouldCompressVideo() && type == SyncRecord.TYPE_VIDEO) {
                    record.setStatus(STATUS_TO_COMPRESS);
                }
                float gpsData[] = getGPSCoordinates(sourceFile.getAbsolutePath(),isVideo);
                record.setLatitude(gpsData[0]);
                record.setLongitude(gpsData[1]);
                record.setOriginFingerprint(localFingerPrint);

                pendingList.add(record);
                logDebug("MediaFinalName: " + sourceFile.getName());
            } else {
                logDebug("NODE EXISTS: " + megaApi.getParentNode(nodeExists).getName() + " : " + nodeExists.getName());
                if (megaApi.getParentNode(nodeExists).getHandle() != uploadNodeHandle) {
                    SyncRecord record = new SyncRecord(nodeExists.getHandle(),sourceFile.getName(),true,media.filePath,media.timestamp,isSecondary,type);
                    record.setOriginFingerprint(nodeExists.getOriginalFingerprint());
                    record.setNewFingerprint(nodeExists.getFingerprint());
                    pendingList.add(record);
                    logDebug("MediaFinalName: " + sourceFile.getName());
                } else {
                    if (!isSecondary) {
                        if (isVideo) {
                            if (media.timestamp > currentVideoTimeStamp) {
                                currentVideoTimeStamp = media.timestamp;
                                dbH.setCamVideoSyncTimeStamp(media.timestamp);
                            }
                        } else {
                            if (media.timestamp > currentTimeStamp) {
                                currentTimeStamp = media.timestamp;
                                dbH.setCamSyncTimeStamp(media.timestamp);
                            }
                        }
                    } else {
                        if (isVideo) {
                            if (media.timestamp > secondaryVideoTimeStamp) {
                                secondaryVideoTimeStamp = media.timestamp;
                                dbH.setSecVideoSyncTimeStamp(media.timestamp);
                            }
                        } else {
                            if (media.timestamp > secondaryTimeStamp) {
                                secondaryTimeStamp = media.timestamp;
                                dbH.setSecSyncTimeStamp(media.timestamp);
                            }
                        }
                    }
                }
            }
        }
        return pendingList;
    }
    
    private boolean checkFile(Media media,String path) {
        
        if (media.filePath != null &&
                path != null &&
                path.compareTo("") != 0 &&
                media.filePath.startsWith(path)
        ) {
            return true;
        }
        
        return false;
    }
    
    private int shouldRun() {
        logDebug("shouldRun()");
        
        if (!isOnline(this)) {
            logWarning("Not online");
            finish();
            return 1;
        }

        if (isDeviceLowOnBattery(batteryIntent)) {
            return BATTERY_STATE_LOW;
        }

        prefs = dbH.getPreferences();
        if (prefs == null) {
            logWarning("Not defined, so not enabled");
            finish();
            return 2;
        } else {
            if (prefs.getCamSyncEnabled() == null) {
                logWarning("Not defined, so not enabled");
                finish();
                return 3;
            } else {
                if (!Boolean.parseBoolean(prefs.getCamSyncEnabled())) {
                    logDebug("Camera Sync Not enabled");
                    finish();
                    return 4;
                } else {
                    if (prefs.getCameraFolderExternalSDCard() != null && Boolean.parseBoolean(prefs.getCameraFolderExternalSDCard())) {
                        Uri uri = Uri.parse(prefs.getUriExternalSDCard());
                        localPath = getFullPathFromTreeUri(uri,this);
                    } else {
                        localPath = prefs.getCamSyncLocalPath();
                    }
                    
                    if (localPath == null) {
                        logWarning("Not defined, so not enabled");
                        finish();
                        return 5;
                    } else {
                        if ("".compareTo(localPath) == 0) {
                            logWarning("Not defined, so not enabled");
                            finish();
                            return 6;
                            
                        } else {
                            if(!localPath.endsWith(File.separator)) {
                                localPath += File.separator;
                            }
                            logDebug("Localpath: " + localPath);
                        }
                    }

                    if(prefs.getRemoveGPS() != null) {
                        removeGPS = Boolean.parseBoolean(prefs.getRemoveGPS());
                        logDebug("Should remove GPS: " + removeGPS);
                    }
                    
                    boolean isWifi = isOnWifi(this);
                    if (prefs.getCamSyncWifi() == null) {
                        if (!isWifi) {
                            logWarning("No wifi...");
                            finish();
                            return 7;
                        }
                    } else {
                        if (Boolean.parseBoolean(prefs.getCamSyncWifi())) {
                            if (!isWifi) {
                                logDebug("No wifi...");
                                finish();
                                return 8;
                            }
                        }
                    }
                    
                    UserCredentials credentials = dbH.getCredentials();
                    if (credentials == null) {
                        logWarning("There are not user credentials");
                        finish();
                        return 11;
                    }
                    
                    gSession = credentials.getSession();
                    isLoggingIn = MegaApplication.isLoggingIn();
                    if (megaApi.getRootNode() == null && !isLoggingIn) {
                        logWarning("RootNode = null");
                        
                        running = true;
                        
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (megaApi.getRootNode() == null) {
                                    isLoggingIn = MegaApplication.isLoggingIn();
                                    if (!isLoggingIn) {
    
                                        setLoginState(true);
    
                                        if (isChatEnabled()) {
                                            logWarning("shouldRun: Chat is ENABLED");
                                            if (megaChatApi == null) {
                                                megaChatApi = ((MegaApplication)getApplication()).getMegaChatApi();
                                            }
                                            
                                            int ret = megaChatApi.getInitState();

                                            if (ret == MegaChatApi.INIT_NOT_DONE || ret == MegaChatApi.INIT_ERROR) {
                                                ret = megaChatApi.init(gSession);
                                                logDebug("shouldRun: result of init ---> " + ret);
                                                chatSettings = dbH.getChatSettings();
                                                if (ret == MegaChatApi.INIT_NO_CACHE) {
                                                    logDebug("shouldRun: condition ret == MegaChatApi.INIT_NO_CACHE");

                                                } else if (ret == MegaChatApi.INIT_ERROR) {
                                                    logDebug("shouldRun: condition ret == MegaChatApi.INIT_ERROR");
                                                    if (chatSettings == null) {
                                                        logWarning("shouldRun: ERROR----> Switch OFF chat");
                                                        chatSettings = new ChatSettings();
                                                        chatSettings.setEnabled(false + "");
                                                        dbH.setChatSettings(chatSettings);
                                                    } else {
                                                        logWarning("shouldRun: ERROR----> Switch OFF chat");
                                                        dbH.setEnabledChat(false + "");
                                                    }
                                                    megaChatApi.logout(CameraUploadsService.this);
                                                } else {
                                                    logDebug("shouldRun: Chat correctly initialized");
                                                }
                                            }
                                        }
                                        logDebug("Camera upload start fast login");
                                        megaApi.fastLogin(gSession,CameraUploadsService.this);
                                    } else {
                                        logWarning("Another login is processing");
                                    }
                                } else {
                                    logDebug("postDelayed RootNode != null");
                                    
                                    int r = runLoggedIn();
                                    logDebug("shouldRunAfterLoginDelayed -> " + r);
                                    if (r == 0) {
                                        try {
                                            startCameraUploads();
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            handleException(e);
                                        }
                                    }
                                }
                            }
                        },10 * 1000);
                        
                        return LOGIN_IN;
                    }

                    logDebug("RootNode != null");
                    int r = runLoggedIn();
                    return r;
                }
            }
        }
    }
    
    private int runLoggedIn() {
        
        if (prefs.getCamSyncHandle() == null) {
            logWarning("if (prefs.getCamSyncHandle() == null)");
            cameraUploadHandle = -1;
        } else {
            logDebug("if (prefs.getCamSyncHandle() != null)");
            cameraUploadHandle = Long.parseLong(prefs.getCamSyncHandle());
        }

        if(prefs.getRemoveGPS() != null) {
            removeGPS = Boolean.parseBoolean(prefs.getRemoveGPS());
            logDebug("Should remove GPS: " + removeGPS);
        }
        
        if (prefs.getSecondaryMediaFolderEnabled() == null) {
            logWarning("if (prefs.getSecondaryMediaFolderEnabled() == null)");
            dbH.setSecondaryUploadEnabled(false);
            logWarning("Not defined, so not enabled");
            secondaryEnabled = false;
        } else {
            logDebug("if (prefs.getSecondaryMediaFolderEnabled() != null)");
            if (!Boolean.parseBoolean(prefs.getSecondaryMediaFolderEnabled())) {
                logDebug("Not enabled Secondary");
                secondaryEnabled = false;
            } else {
                secondaryEnabled = true;
                localPathSecondary = prefs.getLocalPathSecondaryFolder();
                if(!localPathSecondary.endsWith(File.separator)) {
                    localPathSecondary += File.separator;
                }
                logDebug("localPathSecondary: " + localPathSecondary);
            }
        }
        
        ArrayList<MegaNode> nl = megaApi.getChildren(megaApi.getRootNode());
        if (cameraUploadHandle == -1) {
            logDebug("Find the Camera Uploads folder of the old PhotoSync");
            for (int i = 0;i < nl.size();i++) {
                if ((CAMERA_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                    cameraUploadHandle = nl.get(i).getHandle();
                    dbH.setCamSyncHandle(cameraUploadHandle);
                } else if ((PHOTO_SYNC.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                    cameraUploadHandle = nl.get(i).getHandle();
                    dbH.setCamSyncHandle(cameraUploadHandle);
                    megaApi.renameNode(nl.get(i),CAMERA_UPLOADS,this);
                }
            }

            logDebug("If not Camera Uploads nor Photosync");
            if (cameraUploadHandle == -1) {
                logWarning("Must create the folder");
                megaApi.createFolder(CAMERA_UPLOADS,megaApi.getRootNode(),this);
                return 13;
            }
        } else {
            MegaNode n = megaApi.getNodeByHandle(cameraUploadHandle);
            if (n == null) {
                logWarning("Node with cameraUploadHandle is NULL");
                cameraUploadHandle = -1;
                for (int i = 0;i < nl.size();i++) {
                    if ((CAMERA_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                        cameraUploadHandle = nl.get(i).getHandle();
                        dbH.setCamSyncHandle(cameraUploadHandle);
                    } else if ((PHOTO_SYNC.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                        cameraUploadHandle = nl.get(i).getHandle();
                        dbH.setCamSyncHandle(cameraUploadHandle);
                        megaApi.renameNode(nl.get(i),CAMERA_UPLOADS,this);
                    }
                }
                
                if (cameraUploadHandle == -1) {
                    logWarning("If not Camera Uploads nor Photosync--- must create the folder");
                    megaApi.createFolder(CAMERA_UPLOADS,megaApi.getRootNode(),this);
                    return 14;
                }
            } else {
                logDebug("Sync Folder " + cameraUploadHandle + " Node: " + n.getName());
            }
        }
        
        if (secondaryEnabled) {
            logDebug("The secondary uploads are enabled");
            String temp = prefs.getMegaHandleSecondaryFolder();
            if (temp != null) {
                if (temp.compareTo("") != 0) {
                    secondaryUploadHandle = Long.parseLong(prefs.getMegaHandleSecondaryFolder());
                    if (secondaryUploadHandle == -1) {
                        for (int i = 0;i < nl.size();i++) {
                            if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                                secondaryUploadHandle = nl.get(i).getHandle();
                                dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                            }
                        }
                        
                        //If not "Media Uploads"
                        if (secondaryUploadHandle == -1) {
                            logWarning("Must create the secondary folder");
                            megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                            return 15;
                        }
                    } else {
                        logDebug("SecondaryUploadHandle: " + secondaryUploadHandle);
                        MegaNode n = megaApi.getNodeByHandle(secondaryUploadHandle);
                        //If ERROR with the handler (the node may no longer exist): Create the folder Media Uploads
                        if (n == null) {
                            secondaryUploadHandle = -1;
                            logWarning("The secondary media folder may not longer exists");
                            for (int i = 0;i < nl.size();i++) {
                                if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                                    secondaryUploadHandle = nl.get(i).getHandle();
                                    dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                                }
                            }
                            
                            //If not "Media Uploads"
                            if (secondaryUploadHandle == -1) {
                                logWarning("Must create the folder");
                                megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                                return 16;
                            }
                        } else {
                            logDebug("Secondary Folder " + secondaryUploadHandle + " Node: " + n.getName());
                            secondaryUploadNode = megaApi.getNodeByHandle(secondaryUploadHandle);
                        }
                    }
                } else {
                    //If empty string as SecondaryHandle
                    secondaryUploadHandle = -1;
                    for (int i = 0;i < nl.size();i++) {
                        if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                            secondaryUploadHandle = nl.get(i).getHandle();
                            dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                        }
                    }
                    
                    //If not "Media Uploads"
                    if (secondaryUploadHandle == -1) {
                        logWarning("Mmust create the folder");
                        megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                        return 17;
                    }
                }
            } else {
                for (int i = 0;i < nl.size();i++) {
                    if ((SECONDARY_UPLOADS.compareTo(nl.get(i).getName()) == 0) && (nl.get(i).isFolder())) {
                        secondaryUploadHandle = nl.get(i).getHandle();
                        dbH.setSecondaryFolderHandle(secondaryUploadHandle);
                    }
                }
                
                //If not "Media Uploads"
                if (secondaryUploadHandle == -1) {
                    logWarning("Must create the folder");
                    megaApi.createFolder(SECONDARY_UPLOADS,megaApi.getRootNode(),this);
                    return 18;
                }
            }
        } else {
            logDebug("Secondary NOT Enabled");
        }
        
        return 0;
    }
    
    private void initService() {
        logDebug("initService()");
        registerNetworkTypeChangeReceiver();
        try {
            app = (MegaApplication)getApplication();
        } catch (Exception ex) {
            finish();
        }

        int wifiLockMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        WifiManager wifiManager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
        lock = wifiManager.createWifiLock(wifiLockMode,"MegaDownloadServiceWifiLock");
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MegaDownloadServicePowerLock:");
        
        if (!wl.isHeld()) {
            wl.acquire();
        }
        if (!lock.isHeld()) {
            lock.acquire();
        }

        stopByNetworkStateChange = false;
        lastUpdated = 0;
        totalUploaded = 0;
        totalToUpload = 0;
        canceled = false;
        isOverQuota = false;
        running = true;
        handler = new Handler();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PAGE_SIZE = 1000;
            PAGE_SIZE_VIDEO = 50;
        } else {
            PAGE_SIZE = 400;
            PAGE_SIZE_VIDEO = 10;
        }
        
        megaApi = app.getMegaApi();
        megaChatApi = app.getMegaChatApi();
        
        if (megaApi == null) {
            finish();
            return;
        }
        
        initDbH();

        String previousIP = app.getLocalIpAddress();
        // the new logic implemented in NetworkStateReceiver
        String currentIP = getLocalIpAddress(getApplicationContext());
        app.setLocalIpAddress(currentIP);
        if ((currentIP != null) && (currentIP.length() != 0) && (currentIP.compareTo("127.0.0.1") != 0))
        {
            if ((previousIP == null) || (currentIP.compareTo(previousIP) != 0)) {
                logDebug("Reconnecting...");
                megaApi.reconnect();
            }
            else{
                logDebug("Retrying pending connections...");
                megaApi.retryPendingConnections();
            }
        }
        // end new logic
        mIntent = new Intent(this,ManagerActivityLollipop.class);
        mIntent.setAction(ACTION_CANCEL_CAM_SYNC);
        mPendingIntent = PendingIntent.getActivity(this,0,mIntent,0);
        tempRoot = new File(getCacheDir(),CU_CACHE_FOLDER).getAbsolutePath() + File.separator;
        File root = new File(tempRoot);
        if (!root.exists()) {
            root.mkdirs();
        }
        
        if (dbH.shouldClearCamsyncRecords()) {
            dbH.deleteAllSyncRecords(TYPE_ANY);
            dbH.saveShouldClearCamsyncRecords(false);
        }
    }
    
    private void handleException(Exception e) {
        logWarning("Handle exception", e);
        
        if (running) {
            handler.removeCallbacksAndMessages(null);
            running = false;
        }
        releaseLocks();
        
        if (isOverQuota) {
            showStorageOverQuotaNotification();
        }
        
        canceled = true;
        running = false;
        stopForeground(true);
        cancelNotification();
    }
    
    private void finish() {
        logDebug("finish");
        
        if (running) {
            handler.removeCallbacksAndMessages(null);
            running = false;
        }
        cancel();
    }
    
    private void cancel() {
        releaseLocks();
        
        if (isOverQuota) {
            showStorageOverQuotaNotification();
            stopRunningCameraUploadService(this);
        }
        
        if (mVideoCompressor != null) {
            mVideoCompressor.stop();
        }
        cuTransfers.clear();
        canceled = true;
        running = false;
        stopForeground(true);
        cancelNotification();
        stopSelf();
    }
    
    private void cancelNotification() {
        if (mNotificationManager != null) {
            logDebug("Cancelling notification ID is " + notificationId);
            mNotificationManager.cancel(notificationId);
        } else {
            logWarning("No notification to cancel");
        }
    }
    
    @Override
    public void onRequestStart(MegaChatApiJava api,MegaChatRequest request) {
    
    }
    
    @Override
    public void onRequestUpdate(MegaChatApiJava api,MegaChatRequest request) {
    
    }
    
    @Override
    public void onRequestFinish(MegaChatApiJava api,MegaChatRequest request,MegaChatError e) {
        if (request.getType() == MegaChatRequest.TYPE_CONNECT) {
            setLoginState(false);
        }
    }
    
    @Override
    public void onRequestTemporaryError(MegaChatApiJava api,MegaChatRequest request,MegaChatError e) {
    
    }
    
    @Override
    public void onRequestStart(MegaApiJava api,MegaRequest request) {
        logDebug("onRequestStart: " + request.getRequestString());
    }
    
    @Override
    public void onRequestUpdate(MegaApiJava api,MegaRequest request) {
        logDebug("onRequestUpdate: " + request.getRequestString());
    }
    
    @Override
    public void onRequestFinish(MegaApiJava api,MegaRequest request,MegaError e) {
        logDebug("onRequestFinish: " + request.getRequestString());
        
        try {
            requestFinished(request,e);
        } catch (Throwable th) {
            logError("Error", th);
            th.printStackTrace();
        }
    }
    
    private synchronized void requestFinished(MegaRequest request,MegaError e) {
        if (request.getType() == MegaRequest.TYPE_LOGIN) {
            if (e.getErrorCode() == MegaError.API_OK) {
                logDebug("Fast login OK");
                logDebug("Calling fetchNodes from CameraSyncService");
                megaApi.fetchNodes(this);
            } else {
                logError("ERROR: " + e.getErrorString());
                setLoginState(false);
                finish();
            }
        } else if (request.getType() == MegaRequest.TYPE_FETCH_NODES) {
            if (e.getErrorCode() == MegaError.API_OK) {
                logDebug("Fetch nodes ok");
                chatSettings = dbH.getChatSettings();
                if (chatSettings != null) {
                    boolean chatEnabled = Boolean.parseBoolean(chatSettings.getEnabled());
                    if (chatEnabled) {
                        logDebug("Chat enabled-->connect");
                        megaChatApi.connectInBackground(this);
                    } else {
                        logWarning("Chat NOT enabled - readyToManager");
                    }
                } else {
                    logWarning("chatSettings NULL - readyToManager");
                }
                setLoginState(false);
    
                try {
                    logDebug("Start service here MegaRequest.TYPE_FETCH_NODES");
                    task = createWorkerThread();
                    task.start();
                } catch (Exception ex) {
                    logError("Exception", ex);
                    finish();
                }
            } else {
                logError("ERROR: " + e.getErrorString());
                setLoginState(false);
                finish();
            }
        } else if (request.getType() == MegaRequest.TYPE_CREATE_FOLDER) {
            if (e.getErrorCode() == MegaError.API_OK) {
                logDebug("Folder created: " + request.getName());
                String name = request.getName();
                if (name.contains(CAMERA_UPLOADS)) {
                    logDebug("CamSync Folder UPDATED DB");
                    dbH.setCamSyncHandle(request.getNodeHandle());
                } else {
                    //Update in database
                    logDebug("Secondary Folder UPDATED DB");
                    dbH.setSecondaryFolderHandle(request.getNodeHandle());
                }
            } else {
                finish();
            }
        } else if (request.getType() == MegaRequest.TYPE_CANCEL_TRANSFER) {
            logDebug("Cancel transfer received");
            if (e.getErrorCode() == MegaError.API_OK) {
                //clear pause state and reset
                megaApi.pauseTransfers(false,this);
                Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if(megaApi.getNumPendingUploads() <= 0) {
                            megaApi.resetTotalUploads();
                        }
                    }
                }, 200);
            } else {
                finish();
            }
        } else if (request.getType() == MegaRequest.TYPE_CANCEL_TRANSFERS) {
            logDebug("Cancel all uploads received");
            megaApi.pauseTransfers(false,this);
            megaApi.resetTotalUploads();
        } else if (request.getType() == MegaRequest.TYPE_PAUSE_TRANSFERS) {
            logDebug("Pausetransfer false received");
            if (e.getErrorCode() == MegaError.API_OK) {
                finish();
            }
        } else if (request.getType() == MegaRequest.TYPE_COPY) {
            if (e.getErrorCode() == MegaError.API_OK) {
                MegaNode node = megaApi.getNodeByHandle(request.getNodeHandle());
                String fingerPrint = node.getFingerprint();
                boolean isSecondary = node.getParentHandle() == secondaryUploadHandle;
                dbH.deleteSyncRecordByFingerprint(fingerPrint,fingerPrint,isSecondary);
            }
            updateUpload();
        }
    }
    
    private void setLoginState(boolean b) {
        isLoggingIn = b;
        MegaApplication.setLoggingIn(b);
    }
    
    @Override
    public void onRequestTemporaryError(MegaApiJava api,MegaRequest request,MegaError e) {
        logWarning("onRequestTemporaryError: " + request.getRequestString());
    }
    
    @Override
    public void onTransferStart(MegaApiJava api,MegaTransfer transfer) {
        logDebug("onTransferStart: " + transfer.getFileName());
        cuTransfers.add(transfer);
    }
    
    @Override
    public void onTransferUpdate(MegaApiJava api,MegaTransfer transfer) {
        transferUpdated(api,transfer);
    }
    
    private synchronized void transferUpdated(MegaApiJava api,MegaTransfer transfer) {
        if (canceled) {
            logDebug("Transfer cancel: " + transfer.getFileName());
            megaApi.cancelTransfer(transfer);
            cancel();
            return;
        }
        
        if (isOverQuota) {
            return;
        }
        
        updateProgressNotification();
    }
    
    @Override
    public void onTransferTemporaryError(MegaApiJava api,MegaTransfer transfer,MegaError e) {
        logWarning("onTransferTemporaryError: " + transfer.getFileName());
        if(e.getErrorCode()==MegaError.API_EOVERQUOTA){
            if (e.getValue() != 0)
                logWarning("TRANSFER OVERQUOTA ERROR: " + e.getErrorCode());
            else
                logWarning("STORAGE OVERQUOTA ERROR: " + e.getErrorCode());

            isOverQuota = true;
            cancel();
        }
    }
    
    @Override
    public void onTransferFinish(MegaApiJava api,MegaTransfer transfer,MegaError e) {
        logDebug("Image sync finished: " + transfer.getFileName() + " size " + transfer.getTransferredBytes());
        logDebug("transfer.getPath:" + transfer.getPath());
        logDebug("transfer.getNodeHandle:" + transfer.getNodeHandle());

        try {
            transferFinished(api, transfer, e);
        } catch (Throwable th) {
            logError("onTransferFinish error", th);
            th.printStackTrace();
        }
    }
    
    private synchronized void transferFinished(final MegaApiJava api,final MegaTransfer transfer,MegaError e) {
        String path = transfer.getPath();
        if (isOverQuota) {
            return;
        }

        if (transfer.getState() == MegaTransfer.STATE_COMPLETED) {
            String size = getSizeString(transfer.getTotalBytes());
            AndroidCompletedTransfer completedTransfer = new AndroidCompletedTransfer(transfer.getFileName(), transfer.getType(), transfer.getState(), size, transfer.getNodeHandle() + "");
            dbH.setCompletedTransfer(completedTransfer);
        }

        if (e.getErrorCode() == MegaError.API_OK) {
            logDebug("Image Sync OK: " + transfer.getFileName() + " IMAGESYNCFILE: " + path);
            MegaNode node = megaApi.getNodeByHandle(transfer.getNodeHandle());
            boolean isSecondary = (node.getParentHandle() == secondaryUploadHandle);
            SyncRecord record = dbH.findSyncRecordByNewPath(path);
            if (record == null) {
                record = dbH.findSyncRecordByLocalPath(path,isSecondary);
            }
            if (record != null) {
                String originalFingerprint = record.getOriginFingerprint();
                megaApi.setOriginalFingerprint(node,originalFingerprint,this);
                megaApi.setNodeCoordinates(node,record.getLatitude(),record.getLongitude(),null);
                
                File src = new File(record.getLocalPath());
                if (src.exists()) {
                    logDebug("Creating preview");
                    File previewDir = getPreviewFolder(this);
                    final File preview = new File(previewDir,MegaApiAndroid.handleToBase64(transfer.getNodeHandle()) + ".jpg");
                    File thumbDir = getThumbFolder(this);
                    final File thumb = new File(thumbDir,MegaApiAndroid.handleToBase64(transfer.getNodeHandle()) + ".jpg");
                    final SyncRecord finalRecord = record;
                    if(isVideoFile(transfer.getPath())) {
                        threadPool.execute(new Runnable() {

                            @Override
                            public void run() {
                                File img = new File(finalRecord.getLocalPath());
                                if(!preview.exists()) {
                                    //for Android 5, 5.1 devices may have insufficient memory, so don't create previews.
                                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        createVideoPreview(CameraUploadsService.this, img, preview);
                                    }
                                }
                                createVideoThumbnail(api,finalRecord.getLocalPath(),thumb);
                            }
                        });
                    } else if (MimeTypeList.typeForName(transfer.getPath()).isImage()) {
                        threadPool.execute(new Runnable() {

                            @Override
                            public void run() {
                                File img = new File(finalRecord.getLocalPath());
                                if(!preview.exists()) {
                                    //for Android 5, 5.1 devices may have insufficient memory, so don't create previews.
                                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        createImagePreview(img, preview);
                                    }
                                }
                                createImageThumbnail(api,finalRecord.getLocalPath(),thumb);
                            }
                        });
                    }
                }
                //delete database record
                dbH.deleteSyncRecordByPath(path,isSecondary);
                //delete temp files
                if (path.startsWith(tempRoot)) {
                    File temp = new File(path);
                    if (temp.exists()) {
                        temp.delete();
                    }
                }
            }
        } else if (e.getErrorCode() == MegaError.API_EOVERQUOTA) {
            logWarning("Over quota error: " + e.getErrorCode());
            isOverQuota = true;
            cancel();
        } else {
            logWarning("Image Sync FAIL: " + transfer.getFileName() + "___" + e.getErrorString());
        }
        if (canceled) {
            logWarning("Image sync cancelled: " + transfer.getFileName());
            cancel();
        }
        updateUpload();
    }
    
    private void updateUpload() {
        if (!canceled) {
            updateProgressNotification();
        }
        
        totalUploaded++;
        logDebug("Total to upload is " + totalToUpload + " totalUploaded " + totalUploaded + " pendings are " + megaApi.getNumPendingUploads());
        if (totalToUpload == totalUploaded) {
            logDebug("Photo upload finished, now checking videos");
            if (isCompressedVideoPending() && !canceled && isCompressorAvailable()) {
                logDebug("Got pending videos, will start compress");
                startVideoCompression();
            } else {
                logDebug("No pending videos, finish");
                onQueueComplete();
            }
        }
    }
    
    @Override
    public boolean onTransferData(MegaApiJava api,MegaTransfer transfer,byte[] buffer) {
        return true;
    }
    
    private void updateTimeStamp() {
        //primary
        Long timeStampPrimary = dbH.findMaxTimestamp(false,SyncRecord.TYPE_PHOTO);
        if (timeStampPrimary == null) {
            timeStampPrimary = 0L;
        }
        if (timeStampPrimary > currentTimeStamp) {
            updateCurrentTimeStamp(timeStampPrimary);
        }
        
        Long timeStampPrimaryVideo = dbH.findMaxTimestamp(false,SyncRecord.TYPE_VIDEO);
        if (timeStampPrimaryVideo == null) {
            timeStampPrimaryVideo = 0L;
        }
        if (timeStampPrimaryVideo > currentVideoTimeStamp) {
            updateCurrentVideoTimeStamp(timeStampPrimaryVideo);
        }
        
        //secondary
        if (secondaryEnabled) {
            Long timeStampSecondary = dbH.findMaxTimestamp(true,SyncRecord.TYPE_PHOTO);
            if (timeStampSecondary == null) {
                timeStampSecondary = 0L;
            }
            if (timeStampSecondary > secondaryTimeStamp) {
                updateSecondaryTimeStamp(timeStampSecondary);
            }
            
            Long timeStampSecondaryVideo = dbH.findMaxTimestamp(true,SyncRecord.TYPE_VIDEO);
            if (timeStampSecondaryVideo == null) {
                timeStampSecondaryVideo = 0L;
            }
            if (timeStampSecondaryVideo > secondaryVideoTimeStamp) {
                updateSecondaryVideoTimeStamp(timeStampSecondaryVideo);
            }
        }
    }
    
    private void updateCurrentTimeStamp(long timeStamp) {
        logDebug("timesTamp: " + timeStamp);
        currentTimeStamp = timeStamp;
        dbH.setCamSyncTimeStamp(currentTimeStamp);
    }
    
    private void updateCurrentVideoTimeStamp(long timeStamp) {
        logDebug("timesTamp: " + timeStamp);
        currentVideoTimeStamp = timeStamp;
        dbH.setCamVideoSyncTimeStamp(currentVideoTimeStamp);
    }
    
    private void updateSecondaryTimeStamp(long timeStamp) {
        logDebug("timesTamp: " + timeStamp);
        secondaryTimeStamp = timeStamp;
        dbH.setSecSyncTimeStamp(secondaryTimeStamp);
    }
    
    private void updateSecondaryVideoTimeStamp(long timeStamp) {
        logDebug("timesTamp: " + timeStamp);
        secondaryVideoTimeStamp = timeStamp;
        dbH.setSecVideoSyncTimeStamp(secondaryVideoTimeStamp);
    }
    
    private boolean isCompressedVideoPending() {
        return dbH.findVideoSyncRecordsByState(STATUS_TO_COMPRESS).size() > 0 && String.valueOf(VIDEO_QUALITY_MEDIUM).equals(prefs.getUploadVideoQuality());
    }
    
    private boolean isCompressorAvailable() {
        if (mVideoCompressor == null) {
            return true;
        } else {
            return !mVideoCompressor.isRunning();
        }
    }
    
    private void startVideoCompression() {
        logDebug("startVideoCompression");
        
        List<SyncRecord> fullList = dbH.findVideoSyncRecordsByState(STATUS_TO_COMPRESS);
        if(megaApi.getNumPendingUploads() <= 0) {
            megaApi.resetTotalUploads();
        }
        totalUploaded = 0;
        totalToUpload = 0;

        mVideoCompressor = new VideoCompressor(this,this);
        mVideoCompressor.setPendingList(fullList);
        mVideoCompressor.setOutputRoot(tempRoot);
        long totalPendingSizeInMB = mVideoCompressor.getTotalInputSize() / (1024 * 1024);
        logDebug("Total videos are " + fullList.size() + " " + totalPendingSizeInMB + "mbyte to Conversion");
        
        if (shouldStartVideoCompression(totalPendingSizeInMB)) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    logDebug("Starting compressor");
                    mVideoCompressor.start();
                }
            });
            t.start();
        } else {
            logDebug("Compression queue bigger than setting, show notification to user.");
            finish();
            Intent intent = new Intent(this,ManagerActivityLollipop.class);
            intent.setAction(ACTION_SHOW_SETTINGS);
            PendingIntent pendingIntent = PendingIntent.getActivity(this,0,intent,0);
            String title = getString(R.string.title_compression_size_over_limit);
            String size = prefs.getChargingOnSize();
            String message = getString(R.string.message_compression_size_over_limit, size + getString(R.string.label_file_size_mega_byte));
            showNotification(title,message,pendingIntent,true);
        }
        
    }
    
    private boolean shouldStartVideoCompression(long queueSize) {
    
        if (isChargingRequired(queueSize) && !isCharging(mContext)) {
            logDebug("shouldStartVideoCompression " + false);
            return false;
        
        }
        logDebug("shouldStartVideoCompression " + true);
        return true;
    }
    
    @Override
    public void onInsufficientSpace() {
        logWarning("onInsufficientSpace");
        finish();
        Intent intent = new Intent(this,ManagerActivityLollipop.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,0,intent,0);
        String title = getResources().getString(R.string.title_out_of_space);
        String message = getResources().getString(R.string.message_out_of_space);
        showNotification(title,message,pendingIntent,true);
    }
    
    public synchronized void onCompressUpdateProgress(int progress) {
        if (!canceled) {
            String message = getString(R.string.message_compress_video, progress + "%");
            String subText = getString(R.string.title_compress_video, mVideoCompressor.getCurrentFileIndex(),mVideoCompressor.getTotalCount());
            showProgressNotification(progress,mPendingIntent,message,subText,"");
        }
    }
    
    public synchronized void onCompressSuccessful(SyncRecord record) {
        logDebug("Compression successfully " + record.getLocalPath());
        dbH.updateSyncRecordStatusByLocalPath(STATUS_PENDING,record.getLocalPath(),record.isSecondary());
    }
    
    public synchronized void onCompressNotSupported(SyncRecord record) {
        logDebug("Compression failed " + record.getLocalPath());
    }
    
    public synchronized void onCompressFailed(SyncRecord record) {
        String localPath = record.getLocalPath();
        boolean isSecondary = record.isSecondary();
        logWarning("Compression failed " + localPath);
        //file can not be compress will be uploaded directly?
        File srcFile = new File(localPath);
        if (srcFile.exists()) {
            StatFs stat = new StatFs(tempRoot);
            double availableFreeSpace = stat.getAvailableBytes();
            if (availableFreeSpace > srcFile.length()) {
                logDebug("Can not compress but got enough disk space, so should be un-supported format issue");
                String newPath = record.getNewPath();
                File temp = new File(newPath);
                dbH.updateSyncRecordStatusByLocalPath(STATUS_PENDING,localPath,isSecondary);
                if (newPath.startsWith(tempRoot) && temp.exists()) {
                    temp.delete();
                }
            } else {
                //record will remain in DB and will be re-compressed next launch
            }
        } else {
            logWarning("Compressed video not exists, remove from DB");
            dbH.deleteSyncRecordByLocalPath(localPath,isSecondary);
        }
    }
    
    public void onCompressFinished(String currentIndexString) {
        logDebug("onCompressFinished");

        if (!canceled) {
            logDebug("Preparing to upload compressed video");
            ArrayList<SyncRecord> compressedList = new ArrayList<>(dbH.findVideoSyncRecordsByState(STATUS_PENDING));
            if (compressedList.size() > 0) {
                startParallelUpload(compressedList,true);
            } else {
                onQueueComplete();
            }
        }
    }
    
    private synchronized void updateProgressNotification() {
        //refresh UI every 1 seconds to avoid too much workload on main thread
        long now = System.currentTimeMillis();
        if (now - lastUpdated > ONTRANSFERUPDATE_REFRESH_MILLIS) {
            lastUpdated = now;
        } else {
            return;
        }
        
        int pendingTransfers = megaApi.getNumPendingUploads();
        int totalTransfers = megaApi.getTotalUploads();
        long totalSizePendingTransfer = megaApi.getTotalUploadBytes();
        long totalSizeTransferred = megaApi.getTotalUploadedBytes();
        
        int progressPercent = (int)Math.round((double)totalSizeTransferred / totalSizePendingTransfer * 100);
        
        String message;
        if (totalTransfers == 0) {
            message = getString(R.string.download_preparing_files);
        } else {
            int inProgress;
            if (pendingTransfers == 0) {
                inProgress = totalTransfers - pendingTransfers;
            } else {
                inProgress = totalTransfers - pendingTransfers + 1;
            }
            message = getResources().getQuantityString(R.plurals.upload_service_notification,totalTransfers,inProgress,totalTransfers);
        }
        
        String info = getProgressSize(this,totalSizeTransferred,totalSizePendingTransfer);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,0,mIntent,0);
        showProgressNotification(progressPercent,pendingIntent,message,info,getString(R.string.settings_camera_notif_title));
    }

    private Notification createNotification(String title,String content,PendingIntent intent,boolean isAutoCancel){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(notificationChannelId,notificationChannelName,NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            channel.setSound(null,null);
            mNotificationManager.createNotificationChannel(channel);
        }

        mBuilder = new NotificationCompat.Builder(mContext,notificationChannelId);
        mBuilder.setSmallIcon(R.drawable.ic_stat_camera_sync)
                .setOngoing(false)
                .setContentTitle(title)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setContentText(content)
                .setOnlyAlertOnce(true)
                .setAutoCancel(isAutoCancel);

        if (intent != null) {
            mBuilder.setContentIntent(intent);
        }
        return mBuilder.build();
    }
    
    private void showNotification(String title,String content,PendingIntent intent,boolean isAutoCancel) {
        mNotification = createNotification(title, content, intent, isAutoCancel);
        mNotificationManager.notify(notificationId,mNotification);
    }

    private void showProgressNotification(int progressPercent, PendingIntent pendingIntent, String message, String subText, String contentText) {
        mNotification = null;
        mBuilder = new NotificationCompat.Builder(mContext, notificationChannelId);
        mBuilder
                .setSmallIcon(R.drawable.ic_stat_camera_sync)
                .setProgress(100, progressPercent, false)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(subText))
                .setContentTitle(message)
                .setContentText(contentText)
                .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(true);
            channel.setSound(null, null);
            mNotificationManager.createNotificationChannel(channel);
            mBuilder.setSubText(subText);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mBuilder.setSubText(subText);
        } else {
            mBuilder.setColor(ContextCompat.getColor(this, R.color.mega))
                    .setContentInfo(subText);
        }
        mNotification = mBuilder.build();
        mNotificationManager.notify(notificationId, mNotification);
    }

    private void showStorageOverQuotaNotification() {
        logDebug("showStorageOverQuotaNotification");

        String contentText = getString(R.string.download_show_info);
        String message = getString(R.string.overquota_alert_title);

        Intent intent = new Intent(this, ManagerActivityLollipop.class);
        intent.setAction(ACTION_OVERQUOTA_STORAGE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, OVER_QUOTA_NOTIFICATION_CHANNEL_ID);
        builder.setSmallIcon(R.drawable.ic_stat_camera_sync)
                .setContentIntent(PendingIntent.getActivity(mContext, 0, intent, 0))
                .setAutoCancel(true)
                .setTicker(contentText)
                .setContentTitle(message)
                .setOngoing(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(OVER_QUOTA_NOTIFICATION_CHANNEL_ID, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(true);
            channel.setSound(null, null);
            mNotificationManager.createNotificationChannel(channel);
            builder.setContentText(contentText);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setContentText(contentText);
        } else {
            builder.setContentInfo(contentText)
                    .setColor(ContextCompat.getColor(this, R.color.mega));

        }
        mNotificationManager.notify(NOTIFICATION_STORAGE_OVERQUOTA, builder.build());
    }
    
    private void removeGPSCoordinates(String filePath) {
        logDebug("Remove GPS coordinates from path " + filePath);
        try {
            ExifInterface exif = new ExifInterface(filePath);
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE,"0/1,0/1,0/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,"0");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,"0/1,0/1,0/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"0");
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE,"0/1,0/1,0/1000");
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF,"0");
            exif.saveAttributes();
        } catch (IOException e) {
            logError("Exception", e);
            e.printStackTrace();
        }
    }
    
    private String createTempFile(SyncRecord file) {
        logDebug("createTempFile");
        File srcFile = new File(file.getLocalPath());
        if (!srcFile.exists()) {
            logError(ERROR_SOURCE_FILE_NOT_EXIST);
            return ERROR_SOURCE_FILE_NOT_EXIST;
        }
        
        try {
            StatFs stat = new StatFs(tempRoot);
            double availableFreeSpace = stat.getAvailableBytes();
            if (availableFreeSpace <= srcFile.length()) {
                logError(ERROR_NOT_ENOUGH_SPACE);
                return ERROR_NOT_ENOUGH_SPACE;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            logError("Exception", ex);
        }
        
        String destPath = file.getNewPath();
        File destFile = new File(destPath);
        try {
            copyFile(srcFile,destFile);
            removeGPSCoordinates(destPath);
        } catch (IOException e) {
            e.printStackTrace();
            logError(ERROR_CREATE_FILE_IO_ERROR, e);
            return ERROR_CREATE_FILE_IO_ERROR;
        }
        return destPath;
    }
    
    private String getNoneDuplicatedDeviceFileName(String fileName,int index) {
        if (index == 0) {
            return fileName;
        }
        
        String name = "", extension = "";
        int pos = fileName.lastIndexOf(".");
        if (pos > 0) {
            name = fileName.substring(0,pos);
            extension = fileName.substring(pos);
        }
        
        fileName = name + "_" + index + extension;

        logDebug("Filename: " + fileName);
        return fileName;
    }
    
    private float[] getGPSCoordinates(String filePath,boolean isVideo) {
        float output[] = new float[2];
        try {
            if (isVideo) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(filePath);
                
                String location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                if (location != null) {
                    logDebug("Location: " + location);
                    
                    boolean secondTry = false;
                    try {
                        final int mid = location.length() / 2; //get the middle of the String
                        String[] parts = {location.substring(0,mid),location.substring(mid)};
                        
                        output[0] = Float.parseFloat(parts[0]);
                        output[1] = Float.parseFloat(parts[1]);
                        
                    } catch (Exception exc) {
                        secondTry = true;
                        logError("Exception, second try to set GPS coordinates", exc);
                    }
                    
                    if (secondTry) {
                        try {
                            String latString = location.substring(0,7);
                            String lonString = location.substring(8,17);
                            
                            output[0] = Float.parseFloat(latString);
                            output[1] = Float.parseFloat(lonString);
                            
                        } catch (Exception ex) {
                            logError("Exception again, no chance to set coordinates of video", ex);
                        }
                    }
                } else {
                    logWarning("No location info");
                }
                retriever.release();
            } else {
                ExifInterface exif = new ExifInterface(filePath);
                exif.getLatLong(output);
            }
        } catch (Exception e) {
            e.printStackTrace();
            logError("Exception", e);
        }
        return output;
    }

    private MegaNode getPossibleNodeFromCloud(String localFingerPrint, MegaNode uploadNode) {
        logDebug("getPossibleNodeFromCloud");
        MegaNode preferNode = null;

        MegaNodeList possibleNodeListFPO = megaApi.getNodesByOriginalFingerprint(localFingerPrint, uploadNode);
        if(possibleNodeListFPO != null && possibleNodeListFPO.size() > 0) {
            // the desired node, do nothing.
            logDebug("Found node with same fingerprint in the same folder!");
            return getFirstNodeFromList(possibleNodeListFPO);
        }

        possibleNodeListFPO = megaApi.getNodesByOriginalFingerprint(localFingerPrint, null);
        if(possibleNodeListFPO != null && possibleNodeListFPO.size() > 0) {
            // node with same fingerprint but in different folder, copy.
            preferNode =  getFirstNodeFromList(possibleNodeListFPO);
        }

        logDebug("No possibile node found");
        return preferNode;
    }

    private MegaNode getFirstNodeFromList(MegaNodeList megaNodeList) {
        if(megaNodeList != null && megaNodeList.size() > 0) {
            return megaNodeList.get(0);
        }
        return null;
    }
    
    private void releaseLocks() {
        if ((lock != null) && (lock.isHeld())) {
            try {
                lock.release();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        if ((wl != null) && (wl.isHeld())) {
            try {
                wl.release();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
    
    private boolean isChargingRequired(long queueSize) {
        initDbH();
        MegaPreferences preferences = dbH.getPreferences();
        if (preferences != null && preferences.getConversionOnCharging() != null) {
            if (Boolean.parseBoolean(preferences.getConversionOnCharging())) {
                int queueSizeLimit = Integer.parseInt(preferences.getChargingOnSize());
                if (queueSize > queueSizeLimit) {
                    logDebug("isChargingRequired " + true + ", queue size is " + queueSize + ", limit size is " + queueSizeLimit);
                    return true;
                }
            }
        }
        logDebug("isChargingRequired " + false);
        return false;
    }
    
    private void initDbH(){
        if(dbH == null){
            dbH = DatabaseHandler.getDbHandler(getApplicationContext());
        }
    }

    private boolean isDeviceLowOnBattery(Intent intent){
        if(intent == null){
            return false;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        logDebug("Device battery level is " + level);
        return level <= LOW_BATTERY_LEVEL && !isCharging(CameraUploadsService.this);
    }
}
