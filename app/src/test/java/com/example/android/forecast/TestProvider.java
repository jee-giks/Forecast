package com.example.android.forecast;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.util.Log;

import com.example.android.forecast.data.ForecastContract.LocationEntry;
import com.example.android.forecast.data.ForecastContract.WeatherEntry;

import java.util.Map;
import java.util.Set;


/**
 * Created by nerd on 22/09/2016.
 */

public class TestProvider extends AndroidTestCase {

    public static final String LOG_TAG = TestProvider.class.getSimpleName();

    public void testDeleteAllRecords() {
        // delete weather records before location due to relationship constraints in the DB
        mContext.getContentResolver().delete(WeatherEntry.CONTENT_URI, null, null);
        //delete location records
        mContext.getContentResolver().delete(LocationEntry.CONTENT_URI, null, null);

        Cursor cursor = mContext.getContentResolver().query(WeatherEntry.CONTENT_URI,
                null, null, null, null);
        assertEquals(cursor.getCount(), 0);
        cursor.close();

        cursor = mContext.getContentResolver().query(LocationEntry.CONTENT_URI,
                null, null, null, null);
        assertEquals(cursor.getCount(), 0);

        cursor.close();
    }

    public void testGetType () {

        String type = mContext.getContentResolver().getType(WeatherEntry.CONTENT_URI);
        // vdn.android.cursor.dir/com.example.android.forecast/weather
        assertEquals(WeatherEntry.CONTENT_TYPE_DIR, type);

        // content://com.example.android.forecast/weather/94074
        String testLocation = "94074";
        // vdn.android.cursor.dir/com.example.android.forecast/94074
        type = mContext.getContentResolver().getType(WeatherEntry.buildWeatherLocation(testLocation));
        assertEquals(WeatherEntry.CONTENT_TYPE_DIR, type);

        // content://com.example.android.forecast/weather/94074/20160902
        String testDate = "20160902";
        // vdn.android.cursor.dir/com.example.android.forecast/20160902
        type = mContext.getContentResolver().getType(WeatherEntry.buildWeatherLocationWithDate(testLocation, testDate));
        assertEquals(WeatherEntry.CONTENT_TYPE_ITEM, type);

        type = mContext.getContentResolver().getType(LocationEntry.CONTENT_URI);
        // vnd.android.cursor.dir/com.example.android.forecast/location
        assertEquals(LocationEntry.CONTENT_TYPE_DIR, type);

        type = mContext.getContentResolver().getType(LocationEntry.buildLocationUri(1L));
        // vnd.android.cursor.dir/com.example.android.forecast/location
        assertEquals(LocationEntry.CONTENT_TYPE_ITEM, type);

    }


    // City name
    public static String TEST_CITY_NAME = "Mountain View";
    public static String TEST_LOCATION  = "99705";
    public static String TEST_DATE = "20161209";

    ContentValues getLocationContentValues () {
        // Test data to be inserted into tables

        String testLocationSetting = "99705";
        double testLatitude = 64.772;
        double testLongitude = 147.355;

        // Create a new map of values, with column names as keys
        ContentValues values = new ContentValues();
        values.put(LocationEntry.COLUMN_CITY_NAME, TEST_CITY_NAME);
        values.put(LocationEntry.COLUMN_LOCATION_SETTING, testLocationSetting);
        values.put(LocationEntry.COLUMN_LATITUDE, testLatitude);
        values.put(LocationEntry.COLUMN_LONGITUDE, testLongitude);

        return values;
    }

    ContentValues getWeatherContentValues (long locationRowId) {
        ContentValues weatherValues = new ContentValues();
        weatherValues.put(WeatherEntry.COLUMN_LOC_KEY, locationRowId);
        weatherValues.put(WeatherEntry.COLUMN_DATETEXT, "1474621881337");
        weatherValues.put(WeatherEntry.COLUMN_DEGREES, 1.1);
        weatherValues.put(WeatherEntry.COLUMN_HUMIDITY, 1.2);
        weatherValues.put(WeatherEntry.COLUMN_PRESSURE, 1.3);
        weatherValues.put(WeatherEntry.COLUMN_MAX_TEMP, 75);
        weatherValues.put(WeatherEntry.COLUMN_MIN_TEMP, 65);
        weatherValues.put(WeatherEntry.COLUMN_SHORT_DESC, "HailStorms");
        weatherValues.put(WeatherEntry.COLUMN_WIND_SPEED, 5.5);
        weatherValues.put(WeatherEntry.COLUMN_WEATHER_ID, 300);

        return weatherValues;
    }

    // Validate the cursor: ensure everything in content matches the insert
    static public  void validateCursor (ContentValues expectedValues, Cursor valueCursor ) {
        Set<Map.Entry<String, Object>> valueSet = expectedValues.valueSet();

        for (Map.Entry<String, Object> entry : valueSet) {
            // Iterate through the columns and check for their index
            String columnName = entry.getKey();
            int idx = valueCursor.getColumnIndex(columnName);
            assertFalse(-1 == idx);
            String expectedValue = entry.getValue().toString();

            assertEquals(expectedValue, valueCursor.getString(idx));
        }
    }

    public void testInsertAndReadProvider() {

        ContentValues values = getLocationContentValues();

        // Insert data into the database
        long locationRowId;
        Uri locationUri = mContext.getContentResolver().insert(LocationEntry.CONTENT_URI, values);

        locationRowId = ContentUris.parseId(locationUri);
        // Verify the row exists.
        assertTrue(locationRowId != -1);

        Log.d(LOG_TAG, "New row id: " + locationRowId);

        // A cursor is a primary interface to the query results
        Cursor cursor = mContext.getContentResolver().query(
                LocationEntry.CONTENT_URI, null, null, null, null
        );

        if (cursor.moveToFirst()) { // move cursor to first row
            validateCursor(values, cursor);
            // Get content values for the weather
            ContentValues weatherValues = getWeatherContentValues(locationRowId);

            long weatherRowId;
            Uri insertUri = mContext.getContentResolver().insert(
                    WeatherEntry.CONTENT_URI, weatherValues);
            weatherRowId = ContentUris.parseId(insertUri);

            // If we were not using a content provider
            // weatherRowId = db.insert(WeatherEntry.TABLE_NAME, null, weatherValues);
            assertTrue(weatherRowId != -1);

            Cursor weatherCursor = mContext.getContentResolver().query(WeatherEntry.CONTENT_URI,
                    null, null, null, null);

            if (weatherCursor.moveToFirst()) {
                validateCursor(weatherValues, weatherCursor);
            } else {
                fail("No weather data returned");
            }

            weatherCursor.close();

            // test weather location
            weatherCursor = mContext.getContentResolver().query(
                    WeatherEntry.buildWeatherLocation(TEST_LOCATION),
                    null, null, null, null);

            if (weatherCursor.moveToFirst()) {
                validateCursor(weatherValues, weatherCursor);
            } else {
                fail("No weather data returned");
            }

            weatherCursor.close();

            // test weather location with start date
            weatherCursor = mContext.getContentResolver().query(
                    WeatherEntry.buildWeatherLocationWithDate(TEST_LOCATION, TEST_DATE),
                    null, null, null, null);

            if (weatherCursor.moveToFirst()) {
                validateCursor(weatherValues, weatherCursor);
            } else {
                fail("No weather data returned");
            }

            // test weather location
            weatherCursor = mContext.getContentResolver().query(
                    WeatherEntry.buildWeatherLocation(TEST_LOCATION),
                    null, null, null, null);

            if (weatherCursor.moveToFirst()) {
                validateCursor(weatherValues, weatherCursor);
            } else {
                fail("No weather data returned");
            }

            weatherCursor.close();


        } else {
            fail("No values returned");
        }
    }

    public void testUpdateLocation () {
        // delete all records first
        testDeleteAllRecords();

        ContentValues values = getLocationContentValues();

        // insert the record to be updated later
        Uri locationUri = mContext.getContentResolver().insert(
                LocationEntry.CONTENT_URI,
                values);
        long locationRowId = ContentUris.parseId(locationUri);
        assertTrue(locationRowId != -1);
        Log.d(LOG_TAG, "New row id: " + locationRowId);

        ContentValues newValues = new ContentValues(values);
        newValues.put(LocationEntry._ID, locationRowId);
        // update city from Northpole to Nairobi
        newValues.put(LocationEntry.COLUMN_CITY_NAME, "Nairobi");

        int count = mContext.getContentResolver().update(
                LocationEntry.CONTENT_URI,
                newValues,
                Long.toString(locationRowId),
                new String [] {Long.toString(locationRowId)});
        assertEquals(count, 1);

        Cursor cursor = mContext.getContentResolver().query(
                LocationEntry.buildLocationUri(locationRowId),
                null, null, null, null
        );

        if (cursor.moveToFirst()) {
            validateCursor(newValues, cursor);
        }

        cursor.close();
    }
}
