package com.lois.tyexif;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.lois.tytool.exif.TyExif;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    Button btn_select, btn_play, btn_record, btn_info;
    ImageView iv_image;
    TyExif mTyExif;

    private static final int mFrequency = 16000;
    private static final int mChannel = AudioFormat.CHANNEL_CONFIGURATION_STEREO;
    private static final int mSampBit = AudioFormat.ENCODING_PCM_16BIT;
    private static int mPrimePlaySize = 0; // 较优播放块大小

    PlayAudioThread playAudioThread;
    AudioRecordThread audioRecordThread;
    List<byte[]> mAudioBufferList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        iv_image = findViewById(R.id.iv_image);
        btn_select = findViewById(R.id.btn_select);
        btn_select.setOnClickListener(this);
        btn_play = findViewById(R.id.btn_play);
        btn_play.setOnClickListener(this);
        btn_record = findViewById(R.id.btn_record);
        btn_record.setOnClickListener(this);
        btn_info = findViewById(R.id.btn_info);
        btn_info.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.btn_select) {
            Intent intent = new Intent(Intent.ACTION_PICK, null);
            intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            startActivityForResult(intent, 99);
        } else if (view.getId() == R.id.btn_play) {
            if (mTyExif == null || mTyExif.getAudioPcm() == null || mTyExif.getAudioTime() <= 0) {
                Toast.makeText(this, "没有音频", Toast.LENGTH_SHORT).show();
                return;
            }
            if (playAudioThread != null) {
                playAudioThread.setIsStop(true);
                playAudioThread = null;
            }
            playAudioThread = new PlayAudioThread(mTyExif.getAudioPcm());
            playAudioThread.start();
        } else if (view.getId() == R.id.btn_record) {
            if (mTyExif == null) {
                Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show();
                return;
            }
            if (playAudioThread != null) {
                playAudioThread.setIsStop(true);
                playAudioThread = null;
            }
            if (audioRecordThread != null) {
                audioRecordThread.setIsStop(true);
                audioRecordThread = null;
                btn_record.setText("开始录制");
                // 获取音频数据
                if (mAudioBufferList != null && mAudioBufferList.size() > 0) {
                    int minSize = mAudioBufferList.get(0).length;
                    int len = minSize * mAudioBufferList.size();
                    byte[] audioBuffer = new byte[len];
                    for (int i = 0; i < mAudioBufferList.size(); i++) {
                        byte[] temp = mAudioBufferList.get(i);
                        if (i * minSize + minSize < len) {
                            System.arraycopy(temp, 0, audioBuffer, i * minSize, minSize);
                        }
                    }
                    try {
                        mTyExif.setAudioPcm(audioBuffer).commit();
                        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        e.printStackTrace();
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                audioRecordThread = new AudioRecordThread();
                audioRecordThread.start();
                btn_record.setText("停止录制");
            }
        } else if (view.getId() == R.id.btn_info) {
            if (mTyExif == null) {
                Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show();
                return;
            }
            AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this)
                    //标题
                    .setTitle("详细信息")
                    //内容
                    .setMessage(mTyExif.toFormatString())
                    .setPositiveButton("确认", null)
                    .create();
            alertDialog.show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 99) {
            // 从相册返回的数据
            Log.e(TAG, "Result:" + data.toString());
            if (data != null) {
                // 得到图片的全路径
                Uri uri = data.getData();
                iv_image.setImageURI(uri);
                Log.e(TAG, "Uri:" + uri.getPath());
                String path = getRealPathFromUri(this, uri);
                Log.e(TAG, "Path:" + path);
                if (path == null) {
                    Log.e(TAG, "path == null");
                    return;
                }
                try {
                    TyExif tyExif = new TyExif(path);
                    if (tyExif != null) {
                        mTyExif = tyExif;
                    }
                    Log.e(TAG, tyExif.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 根据Uri获取图片的绝对路径
     *
     * @param context 上下文对象
     * @param uri     图片的Uri
     * @return 如果Uri对应的图片存在, 那么返回该图片的绝对路径, 否则返回null
     */
    public static String getRealPathFromUri(Context context, Uri uri) {
        int sdkVersion = Build.VERSION.SDK_INT;
        if (sdkVersion >= 19) { // api >= 19
            return getRealPathFromUriAboveApi19(context, uri);
        } else { // api < 19
            return getRealPathFromUriBelowAPI19(context, uri);
        }
    }

    /**
     * 适配api19以下(不包括api19),根据uri获取图片的绝对路径
     *
     * @param context 上下文对象
     * @param uri     图片的Uri
     * @return 如果Uri对应的图片存在, 那么返回该图片的绝对路径, 否则返回null
     */
    private static String getRealPathFromUriBelowAPI19(Context context, Uri uri) {
        return getDataColumn(context, uri, null, null);
    }

    /**
     * 适配api19及以上,根据uri获取图片的绝对路径
     *
     * @param context 上下文对象
     * @param uri     图片的Uri
     * @return 如果Uri对应的图片存在, 那么返回该图片的绝对路径, 否则返回null
     */
    @SuppressLint("NewApi")
    private static String getRealPathFromUriAboveApi19(Context context, Uri uri) {
        String filePath = null;
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // 如果是document类型的 uri, 则通过document id来进行处理
            String documentId = DocumentsContract.getDocumentId(uri);
            if (isMediaDocument(uri)) { // MediaProvider
                // 使用‘:‘分割
                String id = documentId.split(":")[1];

                String selection = MediaStore.Images.Media._ID + "=?";
                String[] selectionArgs = {id};
                filePath = getDataColumn(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs);
            } else if (isDownloadsDocument(uri)) { // DownloadsProvider
                Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(documentId));
                filePath = getDataColumn(context, contentUri, null, null);
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            // 如果是 content 类型的 Uri
            filePath = getDataColumn(context, uri, null, null);
        } else if ("file".equals(uri.getScheme())) {
            // 如果是 file 类型的 Uri,直接获取图片对应的路径
            filePath = uri.getPath();
        }
        return filePath;
    }

    /**
     * 获取数据库表中的 _data 列，即返回Uri对应的文件路径
     *
     * @return
     */
    private static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        String path = null;

        String[] projection = new String[]{MediaStore.Images.Media.DATA};
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(projection[0]);
                path = cursor.getString(columnIndex);
            }
        } catch (Exception e) {
            if (cursor != null) {
                cursor.close();
            }
        }
        return path;
    }

    /**
     * @param uri the Uri to check
     * @return Whether the Uri authority is MediaProvider
     */
    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri the Uri to check
     * @return Whether the Uri authority is DownloadsProvider
     */
    private static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * 播放音频的线程
     */
    private static class PlayAudioThread extends Thread {
        AudioTrack mAudioTrack;
        byte[] pcmBuffer;
        boolean isStop = false;
        int mPlayOffset = 0;

        public PlayAudioThread(byte[] pcmBuffer) {
            this.pcmBuffer = pcmBuffer;
            createAudioTrack();
        }

        @Override
        public void run() {
            Log.d(TAG, "PlayAudioThread run mPlayOffset = " + mPlayOffset);
            mAudioTrack.play();
            while (!isStop) {
                try {
                    mAudioTrack.write(pcmBuffer, mPlayOffset, mPrimePlaySize);
                    mPlayOffset += mPrimePlaySize;
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
                if (mPlayOffset >= pcmBuffer.length) {
                    break;
                }
            }
            mAudioTrack.stop();
            Log.d(TAG, "PlayAudioThread complete...");
        }

        private void createAudioTrack() {
            // 获得构建对象的最小缓冲区大小
            int minBufSize = AudioTrack.getMinBufferSize(mFrequency, mChannel, mSampBit);
            mPrimePlaySize = minBufSize;
            Log.d(TAG, "mPrimePlaySize = " + mPrimePlaySize);
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mFrequency,
                    mChannel, mSampBit, minBufSize, AudioTrack.MODE_STREAM);
        }

        public void setIsStop(boolean isStop) {
            this.isStop = isStop;
        }
    }

    private class AudioRecordThread extends Thread {
        boolean isStop = false;

        @Override
        public void run() {
            //根据定义好的几个配置，来获取合适的缓冲大小
            int bufferSize = AudioRecord.getMinBufferSize(mFrequency, mChannel, mSampBit);
//                int bufferSize = 640;
            Log.i(TAG, "RecordTask: dataSize=" + bufferSize);//1280
            //实例化AudioRecord//MediaRecorder.AudioSource.VOICE_COMMUNICATION
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, mFrequency, mChannel, mSampBit, bufferSize);
//            record.setPreferredDevice(findAudioDevice(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC));
            //开始录制
            record.startRecording();
            byte[] audioData = new byte[bufferSize];
            while (!isStop) {
                //从bufferSize中读取字节，返回读取的short个数
                final int number = record.read(audioData, 0, audioData.length);
                mAudioBufferList.add(audioData);
                audioData = new byte[bufferSize];
            }
            //录制结束
            record.stop();
            record.release();
        }

        public void setIsStop(boolean isStop) {
            this.isStop = isStop;
        }
    }
}