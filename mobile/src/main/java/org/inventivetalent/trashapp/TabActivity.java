package org.inventivetalent.trashapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProviders;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.tabs.TabLayout;
import org.inventivetalent.trashapp.common.*;
import org.inventivetalent.trashapp.ui.main.PageViewModel;
import org.inventivetalent.trashapp.ui.main.SectionsPagerAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.inventivetalent.trashapp.common.Constants.*;
import static org.inventivetalent.trashapp.common.OverpassResponse.convertElementsToPoints;
import static org.inventivetalent.trashapp.common.OverpassResponse.elementsSortedByDistanceFrom;

public class TabActivity extends AppCompatActivity implements TrashCanResultHandler, TrashcanUpdater {

	private SharedPreferences sharedPreferences;
	private boolean           debug;

	private       LocationManager  mLocationManager;
	public static Location         lastKnownLocation;
	public static GeomagneticField geoField;

	private       SensorManager mSensorManager;
	private       float[]       mGravity          = new float[3];
	private       boolean       gravitySet;
	private       float[]       mGeomagnetic      = new float[3];
	private       boolean       magneticSet;
	public static float[]       lastKnownRotation = new float[3];

	public static RotationBuffer rotationBuffer = new RotationBuffer();

	boolean initialSearchCompleted = false;
	public static List<OverpassResponse.Element> nearbyTrashCans = new ArrayList<>();
	public static OverpassResponse.Element       closestTrashCan;

	private int searchItaration = 0;

	private final LocationListener    mLocationListener = new LocationListener() {
		@Override
		public void onLocationChanged(final Location location) {
			Log.i("TrashApp", "onLocationChanged");
			Log.i("TrashApp", location.toString());

			setLastKnownLocation(location);
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
		}

		@Override
		public void onProviderEnabled(String provider) {
		}

		@Override
		public void onProviderDisabled(String provider) {
		}
	};
	private final SensorEventListener mSensorListener   = new SensorEventListener() {
		@Override
		public void onSensorChanged(SensorEvent event) {
			if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
				System.arraycopy(event.values, 0, mGravity, 0, event.values.length);
				gravitySet = true;
			}
			if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
				System.arraycopy(event.values, 0, mGeomagnetic, 0, event.values.length);
				magneticSet = true;
			}

			if (gravitySet && magneticSet) {
				float[] r = new float[9];
				float[] i = new float[9];

				if (SensorManager.getRotationMatrix(r, i, mGravity, mGeomagnetic)) {
					SensorManager.getOrientation(r, lastKnownRotation);
					rotationBuffer.add(lastKnownRotation[0]);

					ViewModelProviders.of(TabActivity.this).get(PageViewModel.class).mRotation.setValue(lastKnownRotation[0]);
				}
			}
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
			System.out.println(entry.getKey() + ": " + entry.getValue() + " (" + entry.getValue().getClass() + ")");
		}
		debug = Util.getBoolean(sharedPreferences, "enable_debug", false);

		setContentView(R.layout.activity_tab);
		SectionsPagerAdapter sectionsPagerAdapter = new SectionsPagerAdapter(this, getSupportFragmentManager());
		ViewPager viewPager = findViewById(R.id.view_pager);
		viewPager.setAdapter(sectionsPagerAdapter);
		TabLayout tabs = findViewById(R.id.tabs);
		tabs.setupWithViewPager(viewPager);

		Intent intent = getIntent();
		if (intent != null) {
			Log.i("TrashApp", intent.toString());
			Log.i("TrashApp", intent.getAction());
			Uri data = intent.getData();
			Log.i("TrashApp", data != null ? data.toString() : "n/a");
		}

		//		FloatingActionButton fab = findViewById(R.id.fab);
		//
		//		fab.setOnClickListener(new View.OnClickListener() {
		//			@Override
		//			public void onClick(View view) {
		//				Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
		//						.setAction("Action", null).show();
		//			}
		//		});
	}

	@Override
	protected void onResume() {
		super.onResume();

		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		if (mSensorManager == null) {
			Toast.makeText(this, "Your device doesn't support sensors", Toast.LENGTH_LONG).show();
			exitApp();
			return;
		}
		Sensor magneticSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		Sensor accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mSensorManager.registerListener(mSensorListener, magneticSensor, SensorManager.SENSOR_DELAY_NORMAL);
		mSensorManager.registerListener(mSensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);

		mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		if (requestLocationUpdates(true)) {
			lookForTrashCans();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();

		mSensorManager.unregisterListener(mSensorListener);
		mLocationManager.removeUpdates(mLocationListener);
	}

	void setLastKnownLocation(Location location) {
		if (location != null) {
			geoField = new GeomagneticField(
					(float) location.getLatitude(),
					(float) location.getLongitude(),
					(float) location.getAltitude(),
					System.currentTimeMillis()
			);
			lastKnownLocation = location;

			ViewModelProviders.of(this).get(PageViewModel.class).mLocation.setValue(location);

			if (!initialSearchCompleted) {
				lookForTrashCans();
			}
			updateClosestTrashcan(nearbyTrashCans);
		}
	}

	boolean requestLocationUpdates(boolean ask) {
		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			Log.i("TrashApp", "Location permissions not granted");
			if (ask) {
				Log.i("TrashApp", "Requesting location permissions");
				ActivityCompat.requestPermissions(this, new String[] {
						Manifest.permission.ACCESS_FINE_LOCATION,
						Manifest.permission.ACCESS_COARSE_LOCATION }, REQUEST_LOCATION_PERMS_CODE);
				return false;
			}
			Log.i("TrashApp", "Location permissions not granted and can't ask - exiting!");

			Toast.makeText(this, "This app requires location permissions", Toast.LENGTH_LONG).show();
			exitApp();
			return false;
		}

		Log.i("TrashApp", "Location permissions granted!");
		// has permission, request!
		mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_REFRESH_TIME,
				LOCATION_REFRESH_DISTANCE, mLocationListener);
		mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_REFRESH_TIME,
				LOCATION_REFRESH_DISTANCE, mLocationListener);

		//		Log.i("TrashApp", "Trying to get last known location from providers");
		//		Location location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		//		if (location == null) {
		//			location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
		//		} else {
		//			Log.i("TrashApp", "got last known location from gps provider");
		//		}
		//		if (location == null) {
		//			location = mLocationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
		//		} else {
		//			Log.i("TrashApp", "got last known location from network provider");
		//		}
		//		setLastKnownLocation(location);
		//		Log.i("TrashApp", lastKnownLocation != null ? lastKnownLocation.toString() : "n/a");

		mLocationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, mLocationListener, null);

		return true;
	}

	@Override
	public void lookForTrashCans() {
		if (lastKnownLocation == null) {
			return;
		}
		Log.i("TrashApp", "Looking for trash cans");
		double searchRadius = Util.getInt(sharedPreferences, "search_radius_start", DEFAULT_SEARCH_RADIUS) + SEARCH_STEP * searchItaration;// meters
		//TODO: might need to steadily increase the radius if we can't find anything closer
		double searchRadiusDeg = searchRadius * ONE_METER_DEG;

		Log.i("TrashApp", "Radius: " + (searchRadius / 1000) + "km / " + searchRadius + "m / " + searchRadiusDeg + "deg");

		double lat = lastKnownLocation.getLatitude();
		double lon = lastKnownLocation.getLongitude();

		OverpassBoundingBox boundingBox = new OverpassBoundingBox(lat - searchRadiusDeg, lon - searchRadiusDeg, lat + searchRadiusDeg, lon + searchRadiusDeg);
		Log.i("TrashApp", boundingBox.toCoordString());
		new TrashCanFinderTask(this, this).execute(boundingBox);
	}

	@Override
	public boolean shouldCacheResults() {
		return sharedPreferences.getBoolean("cache_data", true);
	}

	@Override
	public File getCacheFile() {
		return new File(getFilesDir(), "last_osm_query.json");
	}

	@Override
	public void handleTrashCanLocations(OverpassResponse response, boolean isCached) {
		Log.i("TrashApp", "Got trashcan locations (cached: " + isCached + ")");
		Log.i("TrashApp", response.toString());

		if (!isCached) { initialSearchCompleted = true; }

		List<OverpassResponse.Element> elements = response.elements;
		elements = convertElementsToPoints(elements);

		if (elements.isEmpty()) {
			if (!isCached && Util.getInt(sharedPreferences, "search_radius_start", DEFAULT_SEARCH_RADIUS) + SEARCH_STEP * searchItaration < Util.getInt(sharedPreferences, "search_radius_max", MAX_SEARCH_RADIUS)) {
				// still below max radius, keep looking
				searchItaration++;
				lookForTrashCans();
			} else {
				// reset
				searchItaration = 0;

				Toast.makeText(this, R.string.err_no_trashcans, Toast.LENGTH_LONG).show();
			}
		}
		updateClosestTrashcan(elements);
	}

	public void updateClosestTrashcan(List<OverpassResponse.Element> elements) {
		if (elements.isEmpty()) {
			closestTrashCan = null;
			ViewModelProviders.of(this).get(PageViewModel.class).mClosestCan.setValue(null);
		} else {
			elements = elementsSortedByDistanceFrom(elements, lastKnownLocation);
			// no need to convert to points again
			nearbyTrashCans.clear();
			nearbyTrashCans.addAll(elements);

			int i = 0;
			for (OverpassResponse.Element element : elements) {
				Log.i("TrashApp", (i++) + " " + element.toLocation() + " => " + lastKnownLocation.distanceTo(element.toLocation()));
			}

			OverpassResponse.Element closest = elements.get(0);
			ViewModelProviders.of(this).get(PageViewModel.class).mClosestCan.setValue(closest);
			closestTrashCan = closest;

			// reset
			searchItaration = 0;
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);

		if (requestCode == REQUEST_LOCATION_PERMS_CODE) {
			// just try to init the updates again
			if (requestLocationUpdates(false)) {
				lookForTrashCans();
			}
		}
	}

	void exitApp() {
		Intent homeIntent = new Intent(Intent.ACTION_MAIN);
		homeIntent.addCategory(Intent.CATEGORY_HOME);
		homeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(homeIntent);
	}

}