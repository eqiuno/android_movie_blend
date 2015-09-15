package com.example.pliu.opengl;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;

import com.example.pliu.opengl.gles.FullFrameRect;
import com.example.pliu.opengl.gles.Texture2dProgram;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements SurfaceTexture.OnFrameAvailableListener {
    public final static  String TAG = "VIDEO_PLAYER";
    private GLSurfaceView mGLSurfaceView;
    private PlayMovieThread mPlayThread;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGLSurfaceView = new GLSurfaceView(this);
        mGLSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        mGLSurfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        mGLSurfaceView.setBackgroundResource(R.drawable.bg1);
        mGLSurfaceView.setZOrderOnTop(true);
        // Check if the system supports OpenGL ES 2.0.
        mGLSurfaceView.setEGLContextClientVersion(2);     // select GLES 2.0
        mGLSurfaceView.setDebugFlags(GLSurfaceView.DEBUG_LOG_GL_CALLS);
        EffectRender mRenderer = new EffectRender(this, new MovieHandler(this));
        mGLSurfaceView.setRenderer(mRenderer);
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        //this.addContentView(mGLSurfaceView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(mGLSurfaceView);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mGLSurfaceView.requestRender();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    protected void onResume() {
        super.onResume();
        mGLSurfaceView.onResume();
    }

    private void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        File movieFile = new File(Environment.getExternalStorageDirectory(), "/data/data/output.mp4");
        Log.i(TAG, "movieFile:" + movieFile + " is exists:" + movieFile.exists());
        try {
            mPlayThread = new PlayMovieThread(movieFile, new Surface(st), new SpeedControlCallback());
            mPlayThread.start();
        }catch (Exception e) {
            Log.e(TAG, "PLAYER: error:" + e);
        }
    }

    private static class PlayMovieThread extends Thread {
        private final File mFile;
        private final Surface mSurface;
        private final SpeedControlCallback mCallback;
        private MoviePlayer mMoviePlayer;

        /**
         * Creates thread and starts execution.
         * <p>
         * The object takes ownership of the Surface, and will access it from the new thread.
         * When playback completes, the Surface will be released.
         */
        public PlayMovieThread(File file, Surface surface, SpeedControlCallback callback) {
            mFile = file;
            mSurface = surface;
            mCallback = callback;

            start();
        }

        /**
         * Asks MoviePlayer to halt playback.  Returns without waiting for playback to halt.
         * <p>
         * Call from UI thread.
         */
        public void requestStop() {
            mMoviePlayer.requestStop();
        }

        @Override
        public void run() {
            try {
                mMoviePlayer = new MoviePlayer(mFile, mSurface, mCallback);
                mMoviePlayer.setLoopMode(true);
                mMoviePlayer.play();
            } catch (IOException ioe) {
                Log.e(TAG, "movie playback failed", ioe);
            } finally {
                mSurface.release();
                Log.d(TAG, "PlayMovieThread stopping");
            }
        }
    }

    static class MovieHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<MainActivity> mWeakActivity;

        public MovieHandler(MainActivity activity) {
            mWeakActivity = new WeakReference<MainActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            MainActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}

class EffectRender implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.TAG;
    private FullFrameRect mFullScreen;
    private final float[] mSTMatrix = new float[16];
    private int mTextureId, mBgTextureId;
    MainActivity.MovieHandler mHandler;
    private SurfaceTexture mSurfaceTexture;
    private Context mContext;
    public EffectRender(Context context, MainActivity.MovieHandler handler) {
        mHandler = handler;
        mContext = context;
    }


    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT_TRANSPARENT));

        mTextureId = mFullScreen.createTextureObject();
        mBgTextureId = mFullScreen.loadTexture(mContext, R.drawable.bg1);

        // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
        // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
        // available messages will arrive on the main thread.
        mSurfaceTexture = new SurfaceTexture(mTextureId);
        mHandler.sendMessage(mHandler.obtainMessage(
                MainActivity.MovieHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
    }

    public void updateFilter() {
        Texture2dProgram.ProgramType programType;
        float[] kernel = null;
        float colorAdj = 0.0f;
        programType = Texture2dProgram.ProgramType.TEXTURE_EXT_TRANSPARENT;

//            Log.d(TAG, "Updating filter to " + mNewFilter);
//            switch (mNewFilter) {
//                case CameraCaptureActivity.FILTER_NONE:
//                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT;
//                    break;
//                case CameraCaptureActivity.FILTER_BLACK_WHITE:
//                    // (In a previous version the TEXTURE_EXT_BW variant was enabled by a flag called
//                    // ROSE_COLORED_GLASSES, because the shader set the red channel to the B&W color
//                    // and green/blue to zero.)
//                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
//                    break;
//                case CameraCaptureActivity.FILTER_BLUR:
//                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                    kernel = new float[] {
//                            1f/16f, 2f/16f, 1f/16f,
//                            2f/16f, 4f/16f, 2f/16f,
//                            1f/16f, 2f/16f, 1f/16f };
//                    break;
//                case CameraCaptureActivity.FILTER_SHARPEN:
//                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                    kernel = new float[] {
//                            0f, -1f, 0f,
//                            -1f, 5f, -1f,
//                            0f, -1f, 0f };
//                    break;
//                case CameraCaptureActivity.FILTER_EDGE_DETECT:
//                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                    kernel = new float[] {
//                            -1f, -1f, -1f,
//                            -1f, 8f, -1f,
//                            -1f, -1f, -1f };
//                    break;
//                case CameraCaptureActivity.FILTER_EMBOSS:
//                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
//                    kernel = new float[] {
//                            2f, 0f, 0f,
//                            0f, -1f, 0f,
//                            0f, 0f, -1f };
//                    colorAdj = 0.5f;
//                    break;
//                default:
//                    throw new RuntimeException("Unknown filter mode " + mNewFilter);
//            }

        // Do we need a whole new program?  (We want to avoid doing this if we don't have
        // too -- compiling a program could be expensive.)
        if (programType != mFullScreen.getProgram().getProgramType()) {
            mFullScreen.changeProgram(new Texture2dProgram(programType));
            // If we created a new program, we need to initialize the texture width/height.
            //mIncomingSizeUpdated = true;
        }

        // Update the filter kernel (if any).
        if (kernel != null) {
            mFullScreen.getProgram().setKernel(kernel, colorAdj);
        }

        //mCurrentFilter = mNewFilter;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        Log.d(TAG, "onDrawFrame tex=" + mTextureId);
        mSurfaceTexture.updateTexImage();
        mSurfaceTexture.getTransformMatrix(mSTMatrix);
        mFullScreen.drawFrame(new int[] {mBgTextureId, mTextureId}, mSTMatrix);
    }
}
