/*
    Copyright 2012 Nik Cain nik@showmehills.com

    This file is part of ShowMeHills.

    ShowMeHills is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    ShowMeHills is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with ShowMeHills.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.showmehills;

/*
 * Main Activity. Implements a camera preview with an overlay. Listens for orientation changes and
 * asks HillDatabase for hills that are in view. It then draws HillOverlayItem in the appropriate place.
 * The 'appropriate place' is dependent on two calibration factors - the first gives the field of view
 * and the second adjusts the compass direction.
 * Tapping on the HillOverlayItem opens the HillInfo activity.
 *
 * The field of view calibration seemed necessary as the hardware parameters of the phone didn't match
 * reality. This approach seemed more failsafe for any camera, even though it introduces the element of
 * human error.
 *
 * The compass adjustment is necessary since the compass on my HTC Desire HD is temperamental to the point
 * of unusable. Even with the adjustment it rarely works well.
 */

import android.app.Activity;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.graphics.*;
import android.hardware.*;
import android.location.*;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.analytics.Tracker;

import org.florescu.android.rangeseekbar.RangeSeekBar;

public class ShowMeHillsActivity extends Activity implements IShowMeHillsActivity, SensorEventListener, OnTouchListener {

    public float hfov = (float) 50.2;
    public float vfov = (float) 20.0;
    private SensorManager mSensorManager;
    private RapidGPSLock mGPS;
    Sensor accelerometer;
    Sensor magnetometer;
    float[] mGravity;
    float[] mGeomagnetic;

    Timer timer;
    private int GPSretryTime = 60;
    private int CompassSmoothingWindow = 50;

    //private Location curLocation;
    private boolean isCalibrated = false;
    private double calibrationStep = -1;
    private float compassAdjustment = 0;
    private ArrayList<HillMarker> mMarkers = new ArrayList<>();

    float mRotationMatrixA[] = new float[9];
    float mRotationMatrixB[] = new float[9];
    float mDeclination = 0;
    private boolean mHasAccurateGravity = false;
    private boolean mHasAccurateAccelerometer = false;

    public int scrwidth = 10;
    public int scrheight = 10;
    public int scrdpi = 10;
    private int mMainTextSize = 20;
    public static CameraPreviewSurface cv;
    public DrawOnTop mDraw;
    private HillDatabase myDbHelper;
    private filteredDirection fd = new filteredDirection();
    private filteredElevation fe = new filteredElevation();

    RangeSeekBar heightSeekBar;
    RangeSeekBar distanceSeekBar;

    // preferences
    Float maxdistance = 30f;
    Float textsize = 1f;
    boolean showdir = false;
    boolean showdist = false;
    boolean typeunits = false; // true for metric, false for imperial
    boolean showheight = false;
    boolean showhelp = true;
    String uniqueID = "nothere";

    // constants
    private static final float TEXT_SIZE_DECREMENT = 1;
    private static final float TEXT_SIZE_MIN = 7;

    private static final int ALPHA_LABEL_MAX = 255;
    private static final int ALPHA_LINE_MAX = 205;
    private static final int ALPHA_DECREMENT = 10;
    private static final int ALPHA_STROKE_MIN = 200;
    private static final int ALPHA_LABEL_MIN = 180;
    private static final int ALPHA_LINE_MIN = 50;

    TextView dirText;
    TextView fovText;
    TextView locText;

    public class HillMarker
    {
        public HillMarker(int id, Rect loc) { location = loc; hillid=id; }
        public Rect location;
        public int hillid;
    }

    public int GetRotation()
    {
        Display display = getWindowManager().getDefaultDisplay();
        return display.getRotation();
    }

    private void getPrefs() {
        // Get the xml/preferences.xml preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        String md = prefs.getString("distance", ""+maxdistance);
        if (md.equals("")) md = "30.0";
        maxdistance = Float.parseFloat(md);
        String ts = prefs.getString("textsize", ""+textsize);
        if (ts.equals("") || ts.equals("1.0"))
        {
            if (scrdpi < 160) ts = "15.0";
            else if (scrdpi < 240) ts = "25.0";
            else if (scrdpi < 480) ts = "40.0";
            else ts = "50.0";

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("textsize", ts);
            editor.commit();
        }
        textsize = Float.parseFloat(ts);

        showdir = prefs.getBoolean("showdir", false);
        showdist = prefs.getBoolean("showdist", false);
        showheight = prefs.getBoolean("showalt", false);
        typeunits = prefs.getString("distunits", "metric").equalsIgnoreCase("metric");
        isCalibrated = prefs.getBoolean("isCalibrated", false);
        if (!isCalibrated) {
            ImageView view = (ImageView)findViewById(R.id.leftarrowimage);
            view.setVisibility(View.VISIBLE);
        }
        else {
            ImageView view = (ImageView)findViewById(R.id.leftarrowimage);
            view.setVisibility(View.INVISIBLE);
            view = (ImageView)findViewById(R.id.rightarrowimage);
            view.setVisibility(View.INVISIBLE);
        }
        hfov = prefs.getFloat("hfov", (float) 50.2);
        compassAdjustment = prefs.getFloat("compassAdjustment", 0);
        showhelp = prefs.getBoolean("showhelp", true);
        CompassSmoothingWindow = Integer.parseInt(prefs.getString("smoothing", "50"));
        uniqueID = prefs.getString("uniqueID", "nothere");
        if (uniqueID.equals("nothere"))
        {
            uniqueID = UUID.randomUUID().toString();

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("uniqueID", uniqueID);
            editor.commit();
        }

        locText = (TextView) findViewById(R.id.locText);
        fovText = (TextView) findViewById(R.id.fovText);
        dirText = (TextView) findViewById(R.id.dirText);
    }

    void SetSeekBars()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        heightSeekBar = (RangeSeekBar) findViewById(R.id.heightSeekBar);
        String md = prefs.getString("mindistance", "0");
        if (md.equals("")) md = "0";
        Float fval = Float.parseFloat(md);
        heightSeekBar.setRangeValues(fval, 9000);
        heightSeekBar.setOnTouchListener(new View.OnTouchListener() {
            int throttle = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                throttle++;
                if (throttle % 15 == 0)
                    UpdateMarkers();
                return false;
            }
        });

        distanceSeekBar = (RangeSeekBar) findViewById(R.id.distanceSeekBar);
        md = prefs.getString("mindistance", "0");
        if (md.equals("")) md = "0";
        Float mindistance = Float.parseFloat(md);
        md = prefs.getString("distance", "30");
        if (md.equals("")) md = "30";
        Float maxdistance = Float.parseFloat(md);
        distanceSeekBar.setRangeValues(mindistance, maxdistance);
    }

    @Override
    protected void onResume() {
        Log.d("showmehills", "onResume");
        cv.onResume();
        getPrefs();

        fd = new filteredDirection();
        fe = new filteredElevation();
        super.onResume();

        SetSeekBars();
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        mGPS.switchOn();

        if (timer != null)
        {
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        timer.scheduleAtFixedRate(new LocationTimerTask(),GPSretryTime* 1000,GPSretryTime* 1000);
        UpdateMarkers();
        try {
            myDbHelper.checkDataBase();
        }catch(SQLException sqle){
            throw sqle;
        }
    }

    @Override
    protected void onPause() {
        Log.d("showmehills", "onPause");
        cv.onPause();
        timer.cancel();
        timer = null;
        mGPS.switchOff();
        mSensorManager.unregisterListener(this);

        super.onPause();
        try {
            myDbHelper.close();
        }catch(SQLException sqle){
            throw sqle;
        }

    }
    @Override
    protected void onStop()
    {
        try {
            mGPS.switchOff();
            if (timer != null)
            {
                timer.cancel();
                timer = null;
            }
            mSensorManager.unregisterListener(this);
            //wl.release();
            myDbHelper.close();
        }catch(SQLException sqle){
            throw sqle;
        }
        super.onStop();
    }

    public synchronized Tracker getGoogleAnalyticsTracker() {
        AnalyticsTrackers analyticsTrackers = AnalyticsTrackers.getInstance();
        return analyticsTrackers.get(AnalyticsTrackers.Target.APP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        AnalyticsTrackers.initialize(this);

        Tracker t = getGoogleAnalyticsTracker();
        t.setScreenName("mainScreen");

        mGPS = new RapidGPSLock(this);
        mGPS.switchOn();
        mGPS.findLocation();

        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        timer.scheduleAtFixedRate(new LocationTimerTask(),GPSretryTime* 1000,GPSretryTime* 1000);

        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);

        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        myDbHelper = new HillDatabase(this, getString(R.string.dbname), getString(R.string.dbpath));

        DisplayMetrics displaymetrics = new DisplayMetrics();

        getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        scrdpi = displaymetrics.densityDpi;
        scrwidth = displaymetrics.widthPixels;
        scrheight = displaymetrics.heightPixels;

        setContentView(R.layout.main);

        mDraw = new DrawOnTop(this);
        addContentView(mDraw, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        cv = (CameraPreviewSurface)findViewById(R.id.cps);
        cv.init(this);
        cv.setOnTouchListener(this);


        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        if (prefs.getBoolean("showhelp", true))
        {
            Intent myHelpIntent = new Intent(getBaseContext(), Help.class);
            startActivityForResult(myHelpIntent, 0);
        }

        SetSeekBars();
        heightSeekBar = (RangeSeekBar) findViewById(R.id.heightSeekBar);
        heightSeekBar.setOnTouchListener(new View.OnTouchListener() {
            int throttle = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                throttle++;
                if (throttle % 15 == 0)
                    UpdateMarkers();
                return false;
            }
        });

        distanceSeekBar = (RangeSeekBar) findViewById(R.id.distanceSeekBar);
        distanceSeekBar.setOnTouchListener(new View.OnTouchListener() {
            int throttle = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                throttle++;
                if (throttle % 15 == 0)
                    UpdateMarkers();
                return false;
            }
        });

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.preferences_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle all of the possible menu actions.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        if (item.getItemId() == R.id.preferences_menutitem) {
            Intent settingsActivity = new Intent(getBaseContext(),AppPreferences.class);
            startActivity(settingsActivity);
        } else if (item.getItemId() == R.id.mapoverlay) {
            Location curLocation = mGPS.getCurrentLocation();
            if (curLocation != null)
            {
                RangeSeekBar heightSeekBar = (RangeSeekBar) findViewById(R.id.heightSeekBar);
                RangeSeekBar distanceSeekBar = (RangeSeekBar) findViewById(R.id.distanceSeekBar);
                myDbHelper.SetDirections(curLocation, heightSeekBar.getSelectedMinValue().intValue(), heightSeekBar.getSelectedMaxValue().intValue(),
                        distanceSeekBar.getSelectedMinValue().intValue(), distanceSeekBar.getSelectedMaxValue().intValue());
                editor.putFloat("longitude", (float)curLocation.getLongitude());
                editor.putFloat("latitude", (float)curLocation.getLatitude());
                editor.commit();
            }
            Intent myIntent = new Intent(getBaseContext(), MapsActivity.class);
            startActivityForResult(myIntent, 0);
        } else if (item.getItemId() == R.id.help) {
            Intent myHelpIntent = new Intent(getBaseContext(), Help.class);
            startActivityForResult(myHelpIntent, 0);
        } else if (item.getItemId() == R.id.about) {
            Intent myAboutIntent = new Intent(getBaseContext(), About.class);
            startActivityForResult(myAboutIntent, 0);
        } else if (item.getItemId() == R.id.exit) {
            finish();
        } else if (item.getItemId() == R.id.fovcalibrate) {
            calibrationStep = -1;

            ImageView view = (ImageView)findViewById(R.id.leftarrowimage);
            view.setVisibility(View.VISIBLE);
            isCalibrated = false;
            editor.putBoolean("isCalibrated", false);
            editor.commit();
        }
        return super.onOptionsItemSelected(item);
    }

    public void UpdateMarkers()
    {
        Location curLocation = mGPS.getCurrentLocation();
        if (curLocation != null)
        {
            RangeSeekBar heightSeekBar = (RangeSeekBar) findViewById(R.id.heightSeekBar);
            RangeSeekBar distanceSeekBar = (RangeSeekBar) findViewById(R.id.distanceSeekBar);
            myDbHelper.SetDirections(curLocation, heightSeekBar.getSelectedMinValue().intValue(), heightSeekBar.getSelectedMaxValue().intValue(),
                    distanceSeekBar.getSelectedMinValue().intValue(), distanceSeekBar.getSelectedMaxValue().intValue());
        }
    }

    class filteredDirection
    {
        double dir;
        double sinevalues[] = new double[CompassSmoothingWindow];
        double cosvalues[] = new double[CompassSmoothingWindow];
        int index = 0;

        void AddLatest( double d )
        {
            sinevalues[index] = Math.sin(d);
            cosvalues[index] = Math.cos(d);
            index++;
            if (index > CompassSmoothingWindow - 1) index = 0;
            double sumc = 0;
            double sums = 0;
            for (int a = 0; a < CompassSmoothingWindow; a++)
            {
                sumc += cosvalues[a];
                sums += sinevalues[a];
            }
            dir = Math.atan2(sums/CompassSmoothingWindow,sumc/CompassSmoothingWindow);
        }

        double getDirection()
        {
            // Allow for (possibly large) negative direction and/or compass adjustment by adding
            // two full circles before applying modulus to force a value between 0 and 360.
            return (Math.toDegrees(dir) + compassAdjustment + 720) % 360;
        }

        int GetVariation()
        {
            double Q;
            double sumc = 0;
            double sums = 0;
            for (int a = 0; a < CompassSmoothingWindow; a++)
            {
                sumc += cosvalues[a];
                sums += sinevalues[a];
            }
            double avgc = sumc/CompassSmoothingWindow;
            double avgs = sums/CompassSmoothingWindow;

            sumc = 0;
            sums = 0;
            for (int a = 0; a < CompassSmoothingWindow; a++)
            {
                sumc += Math.pow(cosvalues[a] - avgc, 2);
                sums += Math.pow(sinevalues[a] - avgs, 2);
            }
            Q = (sumc/(CompassSmoothingWindow-1)) + (sums/(CompassSmoothingWindow-1));

            return (int)(Q*1000);
        }
    }

    class filteredElevation
    {
        int AVERAGINGWINDOW = 10;
        double dir;
        double sinevalues[] = new double[AVERAGINGWINDOW];
        double cosvalues[] = new double[AVERAGINGWINDOW];
        int index = 0;
        void AddLatest( double d )
        {
            sinevalues[index] = Math.sin(d);
            cosvalues[index] = Math.cos(d);
            index++;
            if (index > AVERAGINGWINDOW - 1) index = 0;
            double sumc = 0;
            double sums = 0;
            for (int a = 0; a < AVERAGINGWINDOW; a++)
            {
                sumc += cosvalues[a];
                sums += sinevalues[a];
            }
            dir = Math.atan2(sums/AVERAGINGWINDOW,sumc/AVERAGINGWINDOW);
        }
        double getDirection() { return dir; }
    }

    class tmpHill {
        Hills h;
        double ratio;
        int toppt;
    }

    class DrawOnTop extends View {

        private Paint strokePaint = new Paint();
        private Paint textPaint = new Paint();
        private Paint paint = new Paint();
        private Paint transpRedPaint = new Paint();
        private Paint variationPaint = new Paint();

        private Paint settingPaint = new Paint();
        private Paint settingPaint2 = new Paint();

        int subwidth;
        int subheight;
        int gap;
        int txtgap;
        int vtxtgap;
        RectF fovrect;

        ArrayList<tmpHill> hillsToPlot;

        public DrawOnTop(Context context) {
            super(context);

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);

            strokePaint.setTextAlign(Paint.Align.CENTER);
            strokePaint.setTypeface(Typeface.DEFAULT_BOLD);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2);

            paint.setARGB(255, 255, 255, 255);
            transpRedPaint.setARGB(100,255,0,0);

            subwidth = (int)(scrwidth*0.7);
            subheight = (int)(scrheight*0.7);
            gap = (scrwidth - subwidth) / 2;
            txtgap = gap+(subwidth/30);
            vtxtgap = (subheight / 10);

            hillsToPlot = new ArrayList<>();
            fovrect = new RectF(gap,vtxtgap,scrwidth-gap,vtxtgap*11);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!isCalibrated)
            {
                drawCalibrationInstructions(canvas);
                return;
            }

            ArrayList<Hills> localhills = myDbHelper.localhills;

            int topPt = calculateHillsCanFitOnCanvas((int)(scrheight/1.6), localhills);

            drawHillLabelLines(canvas, topPt);

            drawHillLabelText(canvas, topPt);

            drawLocationAndOrientationStatus(canvas);

            drawSettingsButton(canvas);

            super.onDraw(canvas);
        }

        private int calculateHillsCanFitOnCanvas(int topPt, ArrayList<Hills> localhills) {
            Float drawtextsize = textsize;
            hillsToPlot.clear();
            mMarkers.clear();
            for (int h = 0; h < localhills.size() && topPt > 0; h++)
            {
                Hills h1 = localhills.get(h);

                // this is the angle of the peak from our line of sight
                double offset = fd.getDirection() - h1.direction;
                double offset2 = fd.getDirection() - (360+h1.direction);
                double offset3 = 360+fd.getDirection() - (h1.direction);
                double ratio = 0;
                // is it in our line of sight
                boolean inlineofsight=false;
                if (Math.abs(offset) * 2 < hfov)
                {
                    ratio = offset / hfov * -1;
                    inlineofsight = true;
                }
                if (Math.abs(offset2) * 2 < hfov)
                {
                    ratio = offset2 / hfov * -1;
                    inlineofsight = true;
                }
                if (Math.abs(offset3) * 2 < hfov)
                {
                    ratio = offset3 / hfov * -1;
                    inlineofsight = true;
                }
                if (inlineofsight)
                {
                    tmpHill th = new tmpHill();

                    th.h = h1;
                    th.ratio = ratio;
                    th.toppt = topPt;
                    hillsToPlot.add(th);

                    topPt -= (showdir || showdist || showheight && th.h.height > 0)?(1 + drawtextsize*2):drawtextsize;

                    if (drawtextsize - TEXT_SIZE_DECREMENT >= TEXT_SIZE_MIN)
                    {
                        drawtextsize -= TEXT_SIZE_DECREMENT;
                    }
                }
            }

            // Fudge-factor because we don't know exactly how high label text will display until we draw it later.
            // A tiny font at the top needs to be moved down slightly to avoid being clipped; larger fonts seem OK.
            topPt -= Math.max(0, 13 - drawtextsize);
            return topPt;
        }

        private void drawHillLabelLines(Canvas canvas, int toppt) {
            int alpha = ALPHA_LINE_MAX;
            // draw lines first
            for (int i = 0; i < hillsToPlot.size(); i++)
            {
                textPaint.setARGB(alpha, 255, 255, 255);
                strokePaint.setARGB(alpha, 0, 0, 0);
                tmpHill th = hillsToPlot.get(i);
                double vratio = Math.toDegrees(th.h.visualElevation - fe.getDirection());
                int yloc = (int)((scrheight * vratio / vfov) + (scrheight/2));
                int xloc = ((int)(scrwidth * th.ratio) + (scrwidth/2));
                canvas.drawLine(xloc, yloc, xloc, th.toppt - toppt, strokePaint);
                canvas.drawLine(xloc, yloc, xloc, th.toppt - toppt, textPaint);
                canvas.drawLine(xloc-20, th.toppt - toppt, xloc+20, th.toppt - toppt, strokePaint);
                canvas.drawLine(xloc-20, th.toppt - toppt, xloc+20, th.toppt - toppt, textPaint);

                if (alpha - ALPHA_DECREMENT >= ALPHA_LINE_MIN)
                {
                    alpha -= ALPHA_DECREMENT;
                }
            }
        }

        private void drawHillLabelText(Canvas canvas, int toppt) {
            boolean moreinfo;
            Float drawtextsize = textsize;
            int alpha = ALPHA_LABEL_MAX;
            // draw text over top
            for (int i = 0; i < hillsToPlot.size(); i++)
            {
                textPaint.setARGB(alpha, 255, 255, 255);
                strokePaint.setARGB(Math.min(alpha, ALPHA_STROKE_MIN), 0, 0, 0);

                textPaint.setTextSize(drawtextsize);
                strokePaint.setTextSize(drawtextsize);

                tmpHill th = hillsToPlot.get(i);
                moreinfo = (showdir || showdist || showheight && th.h.height > 0);
                int xloc = ((int)(scrwidth * th.ratio) + (scrwidth/2));

                Rect bnds = new Rect();
                strokePaint.getTextBounds(th.h.hillname,0,th.h.hillname.length(),bnds);
                bnds.left += xloc - (textPaint.measureText(th.h.hillname) / 2.0);
                bnds.right += xloc - (textPaint.measureText(th.h.hillname) / 2.0);
                bnds.top += th.toppt - 5 - toppt;
                if (moreinfo) bnds.top -= drawtextsize;
                bnds.bottom += th.toppt-5 - toppt;

                // for debug - draws bounding box of touch region to select hill
                //canvas.drawRect(bnds, strokePaint);

                mMarkers.add(new HillMarker(th.h.id, bnds));
                canvas.drawText(th.h.hillname, xloc, th.toppt - ((moreinfo)?drawtextsize:0) - 5 - toppt, strokePaint);
                canvas.drawText(th.h.hillname, xloc, th.toppt - ((moreinfo)?drawtextsize:0) - 5 - toppt, textPaint);

                if (showdir || showdist || showheight)
                {
                    boolean hascontents = false;
                    String marker = " (";
                    if (showdir)
                    {
                        hascontents = true;
                        marker += Math.floor(10*th.h.direction)/10 + "\u00B0";
                    }
                    if (showdist)
                    {
                        hascontents = true;
                        double multip = (typeunits)?1:0.621371;
                        marker += (showdir ? " " : "") + Math.floor(10*th.h.distance*multip)/10;
                        if (typeunits) marker += "km"; else marker += "miles";
                    }
                    if (showheight)
                    {
                        if (th.h.height > 0)
                        {
                            hascontents = true;
                            marker += ((showdir || showdist) ? " " : "") + distanceAsImperialOrMetric(th.h.height);
                        }
                    }
                    marker += ")";
                    if (hascontents)
                    {
                        canvas.drawText(marker, xloc, th.toppt-5 - toppt, strokePaint);
                        canvas.drawText(marker, xloc, th.toppt-5 - toppt, textPaint);
                    }
                }

                if (alpha - ALPHA_DECREMENT >= ALPHA_LABEL_MIN)
                {
                    alpha -= ALPHA_DECREMENT;
                }

                if (drawtextsize - TEXT_SIZE_DECREMENT >= TEXT_SIZE_MIN)
                {
                    drawtextsize -= TEXT_SIZE_DECREMENT;
                }
            }
        }

        private void drawLocationAndOrientationStatus(Canvas canvas) {
            textPaint.setTextSize(textsize);
            strokePaint.setTextSize(textsize);
            textPaint.setARGB(255, 255, 255, 255);
            strokePaint.setARGB(255, 0, 0, 0);

            String compadj = (compassAdjustment>=0)?"+":"";
            compadj += String.format("%.01f", compassAdjustment);
            String basetext = "" + (int)fd.getDirection() + (char)0x00B0;
            basetext +=" (adj:"+compadj+")";

            if (dirText != null) {
                dirText.setText(basetext);
            }

            basetext ="FOV: "+String.format("%.01f", hfov);
            if (fovText != null) {
                fovText.setText(basetext);
            }

            String acc;
            Location curLocation = mGPS.getCurrentLocation();
            if (curLocation != null)
            {
                acc = "+/- " + distanceAsImperialOrMetric(curLocation.getAccuracy());
            }
            else
            {
                acc = "?";
            }

            basetext ="\nLocation " + acc;
            if (locText != null) {
                locText.setText(basetext);
            }

            basetext = "";

            if (curLocation == null) basetext = "No GPS position yet";
            else if (curLocation.getAccuracy() > 200) basetext = "Warning - GPS position too inaccurate";

            if (basetext.equals(""))
            {
                canvas.drawText( basetext, scrwidth/2, scrheight/2, strokePaint);
                canvas.drawText( basetext, scrwidth/2, scrheight/2, textPaint);
            }

            int va = fd.GetVariation();
            variationPaint.setARGB(255, 255, 0, 0);
            variationPaint.setStrokeWidth(4);
            int dashlength = scrheight / 10;
            for (int i = 0; i < 360; i+=15)
            {
                if (i > va) variationPaint.setARGB(255, 0, 255, 0);
                canvas.drawLine((scrwidth/7)+(dashlength/5*(float)Math.sin( Math.toRadians(i))),
                                scrheight-(scrheight/2.7f)-(dashlength/5*(float)Math.cos( Math.toRadians(i))),
                                (scrwidth/7)+(dashlength*(float)Math.sin( Math.toRadians(i))),
                                scrheight-(scrheight/2.7f)-(dashlength*(float)Math.cos( Math.toRadians(i))),
                                variationPaint);
            }
        }

        private void drawSettingsButton(Canvas canvas) {
            settingPaint2.setStyle(Paint.Style.STROKE);
            settingPaint.setAntiAlias(true);
            settingPaint2.setAntiAlias(true);
            settingPaint2.setStrokeWidth((int)(scrwidth/100.0));
            settingPaint2.setARGB(255, 255, 255, 255);
            settingPaint.setARGB(255, 0, 0, 0);

            float barwidth = scrwidth/12.0f;
            int startPtw = scrwidth/60;
            int startPth = scrheight/60;
            int baroffset = scrwidth/50;
            canvas.drawRect(0.0f, 0.0f, barwidth + (startPtw*2), baroffset * 3.3f, settingPaint);

            canvas.drawLine(startPtw, startPth, startPtw + barwidth, startPth, settingPaint2);

            canvas.drawLine(startPtw, startPth+baroffset, startPtw + barwidth, startPth+baroffset, settingPaint2);
            baroffset += baroffset;
            canvas.drawLine(startPtw, startPth+baroffset, startPtw + barwidth, startPth+baroffset, settingPaint2);
        }

        private void drawCalibrationInstructions(Canvas canvas) {
            // adjust text to fit any screen - lol, so hacky :-D
            boolean happyWithSize = false;
            do
            {
                textPaint.setTextSize(mMainTextSize);
                float sz = textPaint.measureText("screen, wait for stabilisation, and tap again.");
                if (sz > scrwidth*0.7 )
                {
                    mMainTextSize--;
                }
                else if (sz < scrwidth*0.6)
                {
                    mMainTextSize++;
                }
                else
                {
                    happyWithSize = true;
                }
            } while (!happyWithSize);

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setARGB(255, 255, 255, 255);
            paint.setARGB(100, 0, 0, 0);

            // left, top, right, bottom
            canvas.drawRoundRect(fovrect, 50,50,paint);
            canvas.drawText( "To calibrate, view an object at the very", txtgap, vtxtgap*3, textPaint);
            canvas.drawText( "left edge of the screen, and wait for", txtgap, vtxtgap*4, textPaint);
            canvas.drawText( "the direction sensor to stabilise. Then", txtgap, vtxtgap*5, textPaint);
            canvas.drawText( "tap the screen (gently, so you don't move", txtgap, vtxtgap*6, textPaint);
            canvas.drawText( "the view!). Then turn around until the ", txtgap, vtxtgap*7, textPaint);
            canvas.drawText( "object is at the very right edge of the ", txtgap, vtxtgap*8, textPaint);
            canvas.drawText( "screen, wait for stabilisation, and tap again.", txtgap, vtxtgap*9, textPaint);

            canvas.drawText( "Dir: " + (int)fd.getDirection() + (char)0x00B0 + " SD: "+fd.GetVariation(), scrwidth/2, scrheight-(vtxtgap*2), textPaint);

            textPaint.setTextAlign(Paint.Align.CENTER);
        /*    if (calibrationStep == -1)
            {
                ImageView view = (ImageView)findViewById(R.id.LeftArrowImage);
                view.setVisibility(View.VISIBLE);
                //canvas.drawRect(0,0, 20, scrheight, transpRedPaint);
            }
            else
            {
                ImageView view = (ImageView)findViewById(R.id.LeftArrowImage);
                view.setVisibility(View.VISIBLE);
                //canvas.drawRect(scrwidth-20,0, scrwidth, scrheight, transpRedPaint);
            }
            */
            int va = fd.GetVariation();
            variationPaint.setARGB(255, 255, 0, 0);
            variationPaint.setStrokeWidth(4);
            int dashlength = scrheight / 10;
            for (int i = 0; i < 360; i+=15)
            {
                if (i > va) variationPaint.setARGB(255, 0, 255, 0);
                canvas.drawLine((scrwidth/10)+(dashlength/5*(float)Math.sin( Math.toRadians(i))),
                        scrheight-(scrheight/5)-(dashlength/5*(float)Math.cos( Math.toRadians(i))),
                        (scrwidth/10)+(dashlength*(float)Math.sin( Math.toRadians(i))),
                        scrheight-(scrheight/5)-(dashlength*(float)Math.cos( Math.toRadians(i))),
                        variationPaint);
            }
        }
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public void onSensorChanged(SensorEvent event) {
        // some phones never set the sensormanager as reliable, even when readings are ok
        // That means if we try to block it, those phones will never get a compass reading.
        // So we let any readings through until we know we can get accurate readings. Once We know that
        // we'll block the inaccurate ones
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && mHasAccurateAccelerometer) return;
            if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD && mHasAccurateGravity) return;
        }
        else
        {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) mHasAccurateAccelerometer = true;
            if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) mHasAccurateGravity = true;
        }


        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)  mGravity = event.values;
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) mGeomagnetic = event.values;

        if (mGravity != null && mGeomagnetic != null) {

            float[] rotationMatrixA = mRotationMatrixA;
            if (SensorManager.getRotationMatrix(rotationMatrixA, null, mGravity, mGeomagnetic)) {
                Matrix tmpA = new Matrix();
                tmpA.setValues(rotationMatrixA);
                tmpA.postRotate( -mDeclination );
                tmpA.getValues(rotationMatrixA);

                float[] rotationMatrixB = mRotationMatrixB;

                switch (GetRotation())
                {
                    // portrait - normal
                    case Surface.ROTATION_0: SensorManager.remapCoordinateSystem(rotationMatrixA,
                            SensorManager.AXIS_X, SensorManager.AXIS_Z,
                            rotationMatrixB);
                        break;
                    // rotated left (landscape)
                    case Surface.ROTATION_90: SensorManager.remapCoordinateSystem(rotationMatrixA,
                            //SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X,
                            SensorManager.AXIS_X, SensorManager.AXIS_Z,
                            rotationMatrixB);
                        break;
                    // upside down
                    case Surface.ROTATION_180: SensorManager.remapCoordinateSystem(rotationMatrixA,
                            SensorManager.AXIS_X, SensorManager.AXIS_Z,
                            rotationMatrixB);
                        break;
                    // rotated right (landscape)
                    case Surface.ROTATION_270: SensorManager.remapCoordinateSystem(rotationMatrixA,
                            SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X,
                            rotationMatrixB);
                        break;

                    default:  break;
                }

                float[] dv = new float[3];
                SensorManager.getOrientation(rotationMatrixB, dv);

                fd.AddLatest(dv[0]);
                fe.AddLatest((double)dv[1]);
            }
            mDraw.invalidate();
        }
    }

    public boolean onTouch(View v, MotionEvent event) {
        if (!isCalibrated)
        {
            // this is the standard FOV calibration
            if (calibrationStep == -1)
            {
                calibrationStep = fd.getDirection();

                Log.d("showmehills", "1st cal pt="+calibrationStep);

                ImageView view = (ImageView)findViewById(R.id.rightarrowimage);
                view.setVisibility(View.VISIBLE);
                view = (ImageView)findViewById(R.id.leftarrowimage);
                view.setVisibility(View.INVISIBLE);
            }
            else
            {

                ImageView view = (ImageView)findViewById(R.id.rightarrowimage);
                view.setVisibility(View.INVISIBLE);
                double curdir = fd.getDirection();
                if (calibrationStep - curdir < 0) calibrationStep += 360;
                hfov = (float)(calibrationStep - curdir);
                Log.d("showmehills", "2nd cal pt="+curdir);
                Log.d("showmehills", "Setting hfov calibration="+hfov);
                isCalibrated = true;
                calibrationStep = 0;
                SharedPreferences customSharedPreference = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
                SharedPreferences.Editor editor = customSharedPreference.edit();
                editor.putFloat("hfov", hfov);
                editor.putBoolean("isCalibrated", true);
                editor.commit();
            }
            return false;
        }
        // check if it's multi-touch for pinch control of FOV
		/*
       if (event.getPointerCount() > 1)
       {
    	   // multi-touch
    	   float x = event.getX(0) - event.getX(1);
    	   float y = event.getY(0) - event.getY(1);
    	   if (pinchdist > 0)
    	   {
    		   float delta = pinchdist - FloatMath.sqrt(x * x + y * y);
    		   hfov += (delta > 0) ? 1 : -1;
    	   }
    	   pinchdist = FloatMath.sqrt(x * x + y * y);
       }
       else
       {
    	   pinchdist = 0;
       }
       */
        if (event.getX() < scrwidth / 8 &&
                event.getY() < scrheight / 8)
        {
            openOptionsMenu();
        }
        else {
            Iterator<HillMarker> itr = mMarkers.iterator();
            while (itr.hasNext()) {
                HillMarker m = itr.next();
                if (m.location.contains((int)event.getX(), (int)event.getY()))
                {
                    Intent infoActivity = new Intent(getBaseContext(),HillInfo.class);
                    Bundle b = new Bundle();

                    b.putInt("key", m.hillid);

                    infoActivity.putExtras(b);
                    startActivity(infoActivity);
                }
            }
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        super.onKeyDown(keyCode, event);
        switch(keyCode)
        {
            case KeyEvent.KEYCODE_VOLUME_UP:
                compassAdjustment+=0.1;
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                compassAdjustment-=0.1;
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN  )
        {
            SharedPreferences customSharedPreference = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            SharedPreferences.Editor editor = customSharedPreference.edit();
            editor.putFloat("compassAdjustment", compassAdjustment);
            editor.commit();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    class LocationTimerTask extends TimerTask
    {
        @Override
        public void run()
        {
            Log.d("showmehills", "renew GPS search");
            runOnUiThread(new Runnable() {
                public void run() {
                    mGPS.RenewLocation();
                }
            });
        }
    }


    public LocationManager GetLocationManager() {
        return (LocationManager)getSystemService(Context.LOCATION_SERVICE);
    }

    private String distanceAsImperialOrMetric(double distance) {
        if (typeunits) return (int)distance + "m";
        else return (int)(distance*3.2808399) + "ft";
    }
}

