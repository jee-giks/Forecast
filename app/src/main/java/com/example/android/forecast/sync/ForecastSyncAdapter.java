package com.example.android.forecast.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.text.format.Time;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.example.android.forecast.MainActivity;
import com.example.android.forecast.R;
import com.example.android.forecast.Utility;
import com.example.android.forecast.data.ForecastContract;
import com.example.android.forecast.data.ForecastContract.WeatherEntry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.GregorianCalendar;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

/**
 * Created by nerd on 05/10/2016.
 */

public class ForecastSyncAdapter extends AbstractThreadedSyncAdapter {

    public final String LOG_TAG = ForecastSyncAdapter.class.getSimpleName();

    private static final int SYNC_INTERVAL = 60 * 180;
    private static final int SYNC_FLEXTIME = SYNC_INTERVAL / 3;


    private static final String[] NOTIFY_WEATHER_PROJECTION = {
            WeatherEntry.COLUMN_WEATHER_ID,
            WeatherEntry.COLUMN_MAX_TEMP,
            WeatherEntry.COLUMN_MIN_TEMP,
            WeatherEntry.COLUMN_SHORT_DESC
    };


    // These indices must mach the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;

    private static final long DAY_IN_MILLIS = 1000 * 60 * 60 * 24;
    private static final int WEATHER_NOTIFICATION_ID = 3004;

    @Retention(RetentionPolicy.SOURCE) // Retain only for our source code
    @IntDef({LOCATION_STATUS_OK, LOCATION_STATUS_SERVER_DOWN, LOCATION_STATUS_SERVER_INVALID,
    LOCATION_STATUS_UNKNOWN, LOCATION_STATUS_INVALID})
    public @interface LocationStatus {}

    public static final int LOCATION_STATUS_OK = 0;
    public static final int LOCATION_STATUS_SERVER_DOWN = 1;
    public static final int LOCATION_STATUS_SERVER_INVALID = 2;
    public static final int LOCATION_STATUS_UNKNOWN = 3;
    public static final int LOCATION_STATUS_INVALID = 4;

    private boolean DEBUG = true;

    public ForecastSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    private void getWeatherForecastData (String locationQuery) {
        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;

        // Will store the raw JSON response as a string.
        String forecastJsonString = null;
        String apiKey = "de07b800a0f30d675a6ceb7d9b30ce11";

        try {
            // Construct URL for the OpenWeatherMap API
            final String FORECAST_URL = "http://api.openweathermap.org/data/2.5/forecast/daily?";
            final String QUERY_PARAM = "q";
            final String FORMAT = "mode";
            final String UNITS = "units";
            final String DAYS = "cnt";
            final String API_KEY = "APPID";

            Uri buildUri = Uri.parse(FORECAST_URL).buildUpon()
                    .appendQueryParameter(QUERY_PARAM, locationQuery)
                    .appendQueryParameter(FORMAT, "json")
                    .appendQueryParameter(UNITS, "metric")
                    .appendQueryParameter(DAYS, "7")
                    .appendQueryParameter(API_KEY, apiKey).build();

            URL url = new URL(buildUri.toString());

            Log.d("QUERYING URI: "+ LOG_TAG, buildUri.toString());

            // Create the request to OpenWeatherMap and open the connection
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            // Read the input stream into a string
            InputStream inputStream = urlConnection.getInputStream();
            StringBuffer buffer = new StringBuffer();
            if (inputStream == null) {
                return;
            }
            reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            while ((line = reader.readLine()) != null) {
                // add new line for easier debugging when printing it out
                buffer.append(line + "\n");
            }

            if (buffer.length() == 0) {
                // String is empty, no point in parsing. Return a server down status
                setLocationStatus(getContext(), LOCATION_STATUS_SERVER_DOWN);
                return;
            }
            forecastJsonString = buffer.toString();

            getWeatherDataFromJson(forecastJsonString, locationQuery);

        } catch (IOException e) {
            Log.e(LOG_TAG, "IO ERROR: ", e);
            e.printStackTrace();
            setLocationStatus(getContext(), LOCATION_STATUS_SERVER_DOWN);
        } catch (JSONException e) {
            Log.e(LOG_TAG, "JSON ERROR: ", e);
            e.printStackTrace();
            setLocationStatus(getContext(),  LOCATION_STATUS_SERVER_INVALID);
        }
        finally{
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, "ERROR CLOSING STREAM", e);
                }
            }
        }
        return;
    }

    private void getWeatherDataFromJson(String forecastJsonString, String locationSetting) throws
            JSONException {

        // Location information
        final String OWM_CITY = "city";
        final String OWM_CITY_NAME = "name";
        final String OWM_COORD = "coord";

        // Location coordinates
        final String OWM_LATITUDE = "lat";
        final String OWM_LONGITUDE = "lon";

        // Weather information. Each day's forecast info is an element of the "list" array
        final String OWM_LIST = "list";

        final String OWM_DATETIME = "dt";
        final String OWM_PRESSURE = "pressure";
        final String OWM_HUMIDITY = "humidity";
        final String OWM_WINDSPEED = "speed";
        final String OWM_WIND_DIRECTION = "deg";

        // All temperatures are children of the "temp" object
        final String OWM_TEMPERATURE = "temp";
        final String OWM_MAX = "max";
        final String OWM_MIN = "min";

        final String OWM_WEATHER = "weather";
        final String OWM_DESCRIPTION = "description";
        final String OWM_WEATHER_ID = "id";

        final String OWN_MESSAGE_CODE = "cod";

        try {
            JSONObject forecastJson = new JSONObject(forecastJsonString);

            if (forecastJson.has(OWN_MESSAGE_CODE)) {
                int errorCode = forecastJson.getInt(OWN_MESSAGE_CODE);

                switch (errorCode) {
                    case HttpURLConnection.HTTP_OK:
                        break;
                    case HttpURLConnection.HTTP_BAD_GATEWAY:
                        setLocationStatus(getContext(), LOCATION_STATUS_INVALID);
                        return;
                    case HttpURLConnection.HTTP_NOT_FOUND:
                        setLocationStatus(getContext(), LOCATION_STATUS_INVALID);
                        return;
                    default:
                        setLocationStatus(getContext(), LOCATION_STATUS_SERVER_DOWN);
                        return;
                }
            }

            JSONArray weatherArray = forecastJson.getJSONArray(OWM_LIST);

            JSONObject cityJson = forecastJson.getJSONObject(OWM_CITY);
            String cityName = cityJson.getString(OWM_CITY_NAME);

            JSONObject cityCoordinates = cityJson.getJSONObject(OWM_COORD);
            double cityLatitude = cityCoordinates.getDouble(OWM_LATITUDE);
            double cityLongitude = cityCoordinates.getDouble(OWM_LONGITUDE);

            /** Insert the location into the database. */
            long locationID = insertLocationIntoDB(
                    locationSetting, cityName, cityLatitude, cityLongitude
            );

            // Get and insert the new weather information into the database
            Vector<ContentValues> cVVector = new Vector<>(weatherArray.length());

            // Returns the forecast based upon the local time of the city
            // Use the GMT offset to translate this data properly
            Time dayTime = new Time();
            dayTime.setToNow();

            // Start at the day returned by local time
            int julianStartDay = Time.getJulianDay(System.currentTimeMillis(), dayTime.gmtoff);

            // Work using UTC
            dayTime = new Time();

            for(int i = 0; i < weatherArray.length(); i++) {
                //  These are the values that will be collected
                long dateTime;
                double pressure;
                int humidity;
                double windSpeed;
                double windDirection;

                double high;
                double low;

                String description;
                int weatherId;

                // Get the JSON object representing the day
                JSONObject dayForecast = weatherArray.getJSONObject(i);

                // CONVERT THIS TO UTC TIME
                dateTime = dayTime.setJulianDay(julianStartDay+i);

                GregorianCalendar calendar = new GregorianCalendar();
                calendar.add(GregorianCalendar.DAY_OF_WEEK, i);
//
//                Date time = gc.getTime();
//                SimpleDateFormat shortDateFormat = new SimpleDateFormat("EEE MMM dd");
//                String day = shortDateFormat.format(time);


                pressure = dayForecast.getDouble(OWM_PRESSURE);
                humidity = dayForecast.getInt(OWM_HUMIDITY);
                windSpeed = dayForecast.getDouble(OWM_WINDSPEED);
                windDirection = dayForecast.getDouble(OWM_WIND_DIRECTION);

                // description is in a child array called "weather", which is 1 element long.
                JSONObject weatherObject = dayForecast.getJSONArray(OWM_WEATHER).getJSONObject(0);
                description = weatherObject.getString(OWM_DESCRIPTION);
                weatherId = weatherObject.getInt(OWM_WEATHER_ID);

                // Temperatures are in a child object called "temp".  Try not to name variables
                // "temp" when working with temperature.  It confuses everybody.
                JSONObject temperatureObject = dayForecast.getJSONObject(OWM_TEMPERATURE);
                high = temperatureObject.getDouble(OWM_MAX);
                low = temperatureObject.getDouble(OWM_MIN);

                ContentValues weatherValues = new ContentValues();

                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_LOC_KEY, locationID);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_DATETEXT, dateTime);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_HUMIDITY, humidity);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_PRESSURE, pressure);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_WIND_SPEED, windSpeed);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_DEGREES, windDirection);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_MAX_TEMP, high);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_MIN_TEMP, low);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_SHORT_DESC, description);
                weatherValues.put(ForecastContract.WeatherEntry.COLUMN_WEATHER_ID, weatherId);

                cVVector.add(weatherValues);
            }

            int inserted = 0;

            /** Insert weather data into database */
            insertWeatherIntoDatabase(cVVector, julianStartDay);

            Log.d(LOG_TAG, "Sync Complete. " + cVVector.size() + " Inserted");
            setLocationStatus(getContext(), LOCATION_STATUS_OK);

        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
            setLocationStatus(getContext(), LOCATION_STATUS_SERVER_INVALID);
        }


    }

    private void notifyWeather() {
        Context context = getContext();

        // Checking the last update and notify if it's the first of the day
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String displayNotificationsKey = context.getString(R.string.enable_notifications_key);
        boolean displayNotifications = prefs.getBoolean(
                displayNotificationsKey,
                Boolean.parseBoolean(context.getString(R.string.enable_notifications_default)));

        if (displayNotifications) {
            String lastNotificationKey = context.getString(R.string.pref_last_notification);
            long lastSync = prefs.getLong(lastNotificationKey, 0);

            // Only send the next notification after 24 hours
            if (System.currentTimeMillis() - lastSync >= DAY_IN_MILLIS) {
                String locationQuery = Utility.getPreferredLocation(context);

                Uri weatherUri = WeatherEntry.buildWeatherLocationWithDate(
                        locationQuery, System.currentTimeMillis());

                // Query using a cursor
                Cursor cursor = context.getContentResolver().query(
                        weatherUri,
                        NOTIFY_WEATHER_PROJECTION,
                        null,
                        null,
                        null);

                if (cursor.moveToFirst()) {
                    int weatherId = cursor.getInt(INDEX_WEATHER_ID);
                    double high = cursor.getDouble(INDEX_MAX_TEMP);
                    double low = cursor.getDouble(INDEX_MIN_TEMP);
                    String description = cursor.getString(INDEX_SHORT_DESC);

                    int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
                    Resources resources = context.getResources();
                    int artResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
                    String artUrl = Utility.getArtUrlForWeatherCondition(context, weatherId);

                    // On  Gingerbread and lower devices, the icon width is 48dp
                    @SuppressLint("InlinedApi")
                    int largeIconWidth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                            ? resources.getDimensionPixelSize(
                            android.R.dimen.notification_large_icon_width):
                            resources.getDimensionPixelSize(R.dimen.notification_large_icon_default
                            );
                    // On  Gingerbread and lower devices, the icon height is 48dp
                    @SuppressLint("InlinedApi")
                    int largeIconHeight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                            ? resources.getDimensionPixelSize(
                            android.R.dimen.notification_large_icon_height):
                            resources.getDimensionPixelSize(R.dimen.notification_large_icon_default
                            );

                    Bitmap largeIcon;
                    try {
                        largeIcon = Glide.with(context)
                                .load(artUrl)
                                .asBitmap() // Load as a bitmap
                                .error(artResourceId)
                                .fitCenter()
                                .into(largeIconWidth, largeIconHeight) // fixed size
                                .get();

                        BitmapFactory.decodeResource(resources, artResourceId);
                    } catch (InterruptedException | ExecutionException e) {
                        Log.e(LOG_TAG, "ERROR RETRIEVING LARGE ICON FROM " + artUrl, e);
                        largeIcon = BitmapFactory.decodeResource(resources, artResourceId);
                    }




                    // Define the text of the forecast.
                    String highFormat = Utility.formatTemperature(context, high);
                    String contextHeader = String.format(
                            context.getString(R.string.format_notification),
                            highFormat, locationQuery);

                    // Build our notification using the NotificationCompat.builder
                    NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context)
                            .setSmallIcon(iconId)
                            .setLargeIcon(largeIcon)
                            .setContentTitle(contextHeader)
                            .setContentText(description);

                    // Create an explicit intent for the main activity
                    Intent resultIntent = new Intent(context, MainActivity.class);
                    TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
                    stackBuilder.addParentStack(MainActivity.class);

                    // Add the Intent that starts the Activity to the top of the stack
                    stackBuilder.addNextIntent(resultIntent);
                    PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(
                            0, PendingIntent.FLAG_UPDATE_CURRENT);
                    mBuilder.setContentIntent(resultPendingIntent);
                    NotificationManager mNotificationManager =
                            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

                    // notificationID allows you to update the notification later on
                    mNotificationManager.notify(WEATHER_NOTIFICATION_ID, mBuilder.build());


                    // Refreshing last sync
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putLong(lastNotificationKey, System.currentTimeMillis());
                    editor.commit();
                }
            }
        }
    }

    /**
     * Helper method to handle the insertion of a new location
     *  in the weather database
     *
     * @param locationSetting the location string to request the updates from the service
     * @param cityName the city name eg. Nairobi
     * @param lat the latitude of the city
     * @param lon the longitude of the city
     * @return the row id of the location
     */
    private long insertLocationIntoDB(String locationSetting, String cityName, double lat, double lon) {
        long locationId;

        Log.v(LOG_TAG, "Inserting " + cityName + ", with coord: "
                + lat + ", " + lon + " into the database...");

        //Check if the location exists in the db
        Cursor cursor = getContext().getContentResolver().query(
                ForecastContract.LocationEntry.CONTENT_URI,
                new String[]{ForecastContract.LocationEntry._ID},
                ForecastContract.LocationEntry.COLUMN_LOCATION_SETTING + " = ?",
                new String[]{locationSetting},
                null);

        if (cursor != null && cursor.moveToFirst()) {
            Log.v(LOG_TAG, "Found city in the db");
            int locationIdIndex = cursor.getColumnIndex(ForecastContract.LocationEntry._ID);
            locationId = cursor.getLong(locationIdIndex);

        } else {
            Log.v(LOG_TAG, "Didn't find it in the db. New Insert...");

            ContentValues locationValues = new ContentValues();
            // Add data along with the corresponding name of the data type
            // for the content provider to know what kind of data is being inserted
            locationValues.put(ForecastContract.LocationEntry.COLUMN_LOCATION_SETTING, locationSetting);
            locationValues.put(ForecastContract.LocationEntry.COLUMN_CITY_NAME, cityName);
            locationValues.put(ForecastContract.LocationEntry.COLUMN_LATITUDE, lat);
            locationValues.put(ForecastContract.LocationEntry.COLUMN_LONGITUDE, lon);

            Uri insertedUri = getContext().getContentResolver().insert(
                    ForecastContract.LocationEntry.CONTENT_URI,
                    locationValues
            );
            // The resulting uri contains the id for the row. Extract the locationId from the Uri
            locationId =  ContentUris.parseId(insertedUri);
        }

        cursor.close();

        return locationId;

    }

    private void insertWeatherIntoDatabase (Vector<ContentValues> vector, int julianStartDay) {

        // Returns the forecast based upon the local time of the city
        // Use the GMT offset to translate this data properly
        Time dayTime = new Time();
        // Work using UTC
        dayTime = new Time();

        if (vector.size() > 0) {
            ContentValues[] contentValuesArray = new ContentValues[vector.size()];
            vector.toArray(contentValuesArray);
            getContext().getContentResolver().bulkInsert(
                    WeatherEntry.CONTENT_URI, contentValuesArray);

            String oldDay = Long.toString(dayTime.setJulianDay(julianStartDay-1));

            // delete old data so we don't build up endless history
            Log.d(LOG_TAG,
                    "DELETING OLD RECORDS WITH " + WeatherEntry.COLUMN_DATETEXT + " <= ?" + oldDay);
            getContext().getContentResolver().delete(WeatherEntry.CONTENT_URI,
                    WeatherEntry.COLUMN_DATETEXT + " <= ?",
                    new String[]{oldDay});

            if (DEBUG) {
                Cursor weatherCursor = getContext().getContentResolver().query(
                        ForecastContract.WeatherEntry.CONTENT_URI,
                        null, null, null, null
                );

                if (weatherCursor.moveToFirst()) {
                    ContentValues resultValues = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(weatherCursor, resultValues);

                    Log.v(LOG_TAG, "QUERY SUCCESSFUL: ********************");
                    for (String key : resultValues.keySet()) {
                        Log.v(LOG_TAG, key + ": " + resultValues.getAsString(key));
                    }
                } else {
                    Log.v(LOG_TAG, "QUERY FAILED!!!!!!!!!!!!!!!!!!!!!!!! :-(");
                }
            }

            // NOTIFICATION
            notifyWeather();

            Log.d(LOG_TAG, "Sync Complete. " + vector.size() + " Inserted");
            setLocationStatus(getContext(), LOCATION_STATUS_OK);
        }

//        if (vector.size() > 0) {
//            ContentValues[] contentValuesArray = new ContentValues[vector.size()];
//            vector.toArray(contentValuesArray);

//            Calendar cal = Calendar.getInstance();
//            cal.add(Calendar.DATE, -1); // point to yesterday
//
//            String yesterdayDate = ForecastContract.getDbDateString(cal.getTime());
//            // DELETE records  that are more than a day old
//            getContext().getContentResolver().delete(WeatherEntry.CONTENT_URI,
//                    WeatherEntry.COLUMN_DATETEXT + " <= ?",
//                    new String[] { yesterdayDate });
//        }

    }

    /**
     * Performs a weather sync with the server to update the weather data
     * @param account
     * @param extras
     * @param authority
     * @param provider
     * @param syncResult
     */
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        Log.d(LOG_TAG, "===========================>>>>  STARTING SYNC...");
        Context context = getContext();
        String locationQuery = Utility.getPreferredLocation(context);
        if (locationQuery == null || locationQuery.isEmpty()) {
            return;
        }
        getWeatherForecastData(locationQuery);
    }

    /**
     * Helper method to have the sync adapter sync immediately
     * @param context An app context
     */
    public static void syncImmediately(Context context) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context),
                context.getString(R.string.content_authority), bundle);
    }

    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // we can enable inexact timers in our sync
            SyncRequest request = new SyncRequest.Builder()
                    .syncPeriodic(syncInterval, flexTime)
                    .setSyncAdapter(account, authority)
                    .build();
            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account, authority, new Bundle(), syncInterval);
        }
    }

    /**
     * Helper method to get the fake accounts to be used with
     * the sync adapter if the fake account does not exist yet.
     * If we maje a new account, we call the onAccountCreated method so we can initialize things.
     * @param context The context used to access the account service
     * @return a fake account
     */
    public static Account getSyncAccount(Context context) {
        // Get an instance of the android account manager
        AccountManager accManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name),
                context.getString(R.string.sync_account_type));

        // If pword does not exist, the account doesn't exist
        if (accManager.getPassword(newAccount) == null) {
            if (!accManager.addAccountExplicitly(newAccount, null, null)) {
                return null;
            }
            // If you don't set android:syncable="true" in your <provider>
            // then call context.setIsSyncable(account, AUTHORITY, 1) here
            onAccountCreated(newAccount, context);
        }
        return newAccount;
    }


    private static void onAccountCreated(Account newAccount, Context context) {
        // Since we created an account
        ForecastSyncAdapter.configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);

         // Call setSyncAutomatically to enable our periodic sync
        ContentResolver.setSyncAutomatically(
                newAccount, context.getString(R.string.content_authority), true);

        // Do a sync to get things started
        syncImmediately(context);
    }

    public static void initializeSyncAdapter(Context context) {
        getSyncAccount(context);
    }

    /**
     * Sets the location status into shared preferences. This function should be called from
     * the UI thread because it commits to write to the Shared Preferences.
     * @param context Context to get the PreferenceManager from
     * @param locationStatus The IntDef value to set
     */
    static private void setLocationStatus(Context context, @LocationStatus int locationStatus) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor spe = sp.edit();
        spe.putInt(context.getString(R.string.pref_location_status_key), locationStatus);
        spe.commit();
    }

}
