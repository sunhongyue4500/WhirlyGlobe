package com.mousebirdconsulting.maply;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.util.ByteArrayBuffer;

import android.content.Context;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;

public class OSMVectorTilePager implements QuadPagingLayer.PagingInterface
{
	MaplyController maplyControl = null;
	String remotePath = null;
	int minZoom = 0;
	int maxZoom = 0;
	File cacheDir = null;
	ExecutorService executor = null;
	
	OSMVectorTilePager(MaplyController inMaplyControl,String inRemotePath, int inMinZoom, int inMaxZoom)
	{
		maplyControl = inMaplyControl;
		remotePath = inRemotePath;
		minZoom = inMinZoom;
		maxZoom = inMaxZoom;
		
		// We'll keep 4 threads in a pool for parsing data
		executor = Executors.newFixedThreadPool(4);
	}

	@Override
	public int minZoom() {
		return minZoom;
	}

	@Override
	public int maxZoom() {
		return maxZoom;
	}

	// Connection task fetches the JSON as a string
	private class ConnectionTask extends AsyncTask<String, Void, String>
	{
		QuadPagingLayer layer = null;
		MaplyTileID tileID = null;
		OSMVectorTilePager pager = null;
		
		ConnectionTask(OSMVectorTilePager inPager,QuadPagingLayer inLayer, MaplyTileID inTileID)
		{
			layer = inLayer;
			tileID = inTileID;
			pager = inPager;
		}
		
	    @Override
	    protected String doInBackground(String... urls) {
	    	String aString = null;
	    	try {
		    	URL url = new URL(urls[0]);

	    		/* Open a connection to that URL. */
	    		final HttpURLConnection aHttpURLConnection = (HttpURLConnection) url.openConnection();

	    		/* Define InputStreams to read from the URLConnection. */
	    		InputStream aInputStream = aHttpURLConnection.getInputStream();
	    		BufferedInputStream aBufferedInputStream = new BufferedInputStream(
	    				aInputStream);

	    		/* Read bytes to the Buffer until there is nothing more to read(-1) */
	    		ByteArrayBuffer aByteArrayBuffer = new ByteArrayBuffer(50);
	    		int current = 0;
	    		while ((current = aBufferedInputStream.read()) != -1) {
	    			aByteArrayBuffer.append((byte) current);
	    		}


	    		/* Convert the Bytes read to a String. */
	    		aString = new String(aByteArrayBuffer.toByteArray());               
	    	} 
	    	catch (IOException e) {
//	    		Log.d("OSMVectorTilePager", e.toString());
	    		pager.didNotLoad(layer,tileID);
	    	}
	    	return aString;
	    }

	    @Override
	    protected void onPostExecute(String result) 
	    {
	    	if (result != null)
	    		pager.didLoad(layer,tileID,result,false);
	    	else
	    		pager.didNotLoad(layer,tileID);
	    }

	}
	
	// Generate the cache file name
	String makeTileFileName(MaplyTileID tileID)
	{
		return tileID.level + "_" + tileID.x + "_" + tileID.y + ".json";		
	}

	// The paging layer calls us here to start paging a tile
	@Override
	public void startFetchForTile(final QuadPagingLayer layer,final MaplyTileID tileID) 
	{
//		Log.i("OSMVectorTilePager","Starting Tile : " + tileID.level + " (" + tileID.x + "," + tileID.y + ")");
		
		// Look for it in the cache
		if (cacheDir != null)
		{
			String tileFileName = makeTileFileName(tileID);
			String json = readFromFile(tileFileName);
			if (!json.isEmpty())
			{
				didLoad(layer,tileID,json,true);
				return;
			}
		}
		
		// Form the tile URL
		int maxY = 1<<tileID.level;
		int remoteY = maxY - tileID.y - 1;
		final String tileURL = remotePath + "/" + tileID.level + "/" + tileID.x + "/" + remoteY + ".json";
		
		// Need to kick this task off on the main thread
		final OSMVectorTilePager pager = this;
		Handler handle = new Handler(Looper.getMainLooper());
		handle.post(new Runnable()
			{
				@Override
				public void run()
				{
					ConnectionTask task = new ConnectionTask(pager,layer,tileID);
					String[] params = new String[1];
					params[0] = tileURL;
					task.execute(params);				
				}
			});
	}

	// Group data together for efficiency
	class VectorGroup
	{
		public ArrayList<VectorObject> vecs = new ArrayList<VectorObject>();
	};
	
	// Sort vectors into groups based on their kind
	HashMap<String,VectorGroup> sortIntoGroups(VectorObject vecs)
	{
		HashMap<String,VectorGroup> groups = new HashMap<String,VectorGroup>();

		// Sort the roads based on types
		for (VectorObject vec : vecs)
		{
			AttrDictionary attrs = vec.getAttributes();
			String kind = attrs.getString("kind");

			// Change how it looks depending on the kind
			VectorGroup group = null;
			if (groups.containsKey(kind))
				group = groups.get(kind);
			else {
				group = new VectorGroup();
				groups.put(kind, group);
			}
			group.vecs.add(vec);
		}	
		
		return groups;
	}
	
	// Style for a particular kind of road
	class RoadStyle
	{
		RoadStyle(boolean inTL,float inW,float inR,float inG,float inB,int inDP)
		{
			twoLines = inTL;
			width = inW;
			red = inR/255.f;
			green = inG/255.f;
			blue = inB/255.f;
			drawPriority = inDP;
		}
		public boolean twoLines;
		public float width;
		public float red,green,blue;
		public int drawPriority;
	};
	
	HashMap<String,RoadStyle> roadStyles = null;
	
	// Initialize road styles
	void initRoadStyles()
	{
		roadStyles = new HashMap<String,RoadStyle>();
		roadStyles.put("highway", new RoadStyle(true,10.f,204.f,141.f,4.f,400));
		roadStyles.put("major_road", new RoadStyle(true,6.f,239.f,237.f,88.f,402));
		roadStyles.put("minor_road", new RoadStyle(false,2.f,64,64,64,404));
		roadStyles.put("rail", new RoadStyle(true,6.f,100,100,100,406));
		roadStyles.put("path", new RoadStyle(false,1.f,64,64,64,408));
	}
		
	// Style roads based on their type
	void styleRoads(VectorObject roads,List<ComponentObject> compObjs)
	{
		if (roads == null)
			return;
		
		if (roadStyles == null)
			initRoadStyles();

		HashMap<String,VectorGroup> groups = sortIntoGroups(roads);
		
		// Note: Scale up for high res displays
		float scale = 2;
		
		// Now work through what we find, matching up to styles
		for (String roadType : groups.keySet())
		{
			VectorGroup group = groups.get(roadType);
			RoadStyle roadStyle = roadStyles.get(roadType);
			if (roadStyle == null)
				roadStyle = roadStyles.get("minor_road");

			// Base line underneath road
			if (roadStyle.twoLines)
			{
				VectorInfo roadInfo = new VectorInfo();
				roadInfo.setColor(roadStyle.red/2.f,roadStyle.green/2.f,roadStyle.blue/2.f,1.f);
				roadInfo.setDrawPriority(roadStyle.drawPriority);
				roadInfo.setLineWidth(roadStyle.width*scale);
				roadInfo.setEnable(false);
				compObjs.add(maplyControl.addVectors(group.vecs, roadInfo));
			}
			
			// Road itself
			VectorInfo roadInfo = new VectorInfo();
			roadInfo.setColor(roadStyle.red,roadStyle.green,roadStyle.blue,1.f);
			roadInfo.setDrawPriority(roadStyle.drawPriority+1);
			roadInfo.setLineWidth(roadStyle.width*scale);
			roadInfo.setEnable(false);
			compObjs.add(maplyControl.addVectors(group.vecs, roadInfo));
		}		
	}
	
	void styleRoadLabels(VectorObject roads,List<ComponentObject> compObjs)
	{		
	}

	void styleBuildings(VectorObject buildings,List<ComponentObject> compObjs)
	{
		if (buildings == null)
			return;
		
		VectorInfo buildingInfo = new VectorInfo();
		buildingInfo.setColor(1.f,186.f/255.f,103.f/255.f,1.f);
		buildingInfo.setFilled(true);
		buildingInfo.setDrawPriority(601);
		ComponentObject compObj = maplyControl.addVector(buildings,buildingInfo);
		compObjs.add(compObj);
	}
	
	// Land styles are just colors
	HashMap<String,Integer> landStyles = null;
	void initLandStyles()
	{
		landStyles = new HashMap<String,Integer>();
		int alpha = 255/4;
		Integer green = Color.argb(alpha,111,224,136);
		Integer darkGreen = Color.argb(alpha,111,224,136);
		Integer tan = Color.argb(alpha,210,180,140);
		Integer gray = Color.argb(alpha,(int) 0.1f*255,0,0);
		Integer grayer = Color.argb(alpha,(int) 0.2*255,0,0);
		Integer grayest = Color.argb(alpha,(int) 0.3*255,0,0);
		landStyles.put("scrub", green);
		landStyles.put("park", green);
		landStyles.put("school", gray);
		landStyles.put("meadow", tan);
		landStyles.put("nature_reserve", green);
		landStyles.put("garden", green);
		landStyles.put("pitch", green);
		landStyles.put("wood", darkGreen);
		landStyles.put("farm", tan);
		landStyles.put("farmyard", tan);
		landStyles.put("recreation_ground", green);
		// Note: There's an awful lot of these
		landStyles.put("commercial", grayer);
		landStyles.put("residential", gray);
		landStyles.put("industrial", grayest);
		landStyles.put("common", gray);
		landStyles.put("parking", gray);
		landStyles.put("default", gray);
	}

	// Style land use based on the types
	void styleLandUsage(VectorObject land,List<ComponentObject> compObjs)
	{
		if (land == null)
			return;
		
		if (landStyles == null)
			initLandStyles();

		HashMap<String,VectorGroup> groups = sortIntoGroups(land);
		
		// Now work through what we find, matching up to styles
		for (String landType : groups.keySet())
		{
			VectorGroup group = groups.get(landType);
			Integer landStyle = landStyles.get(landType);
			if (landStyle == null)
				landStyle = landStyles.get("default");

			if (landStyle != null)
			{
				VectorInfo landInfo = new VectorInfo();
				landInfo.setColor(Color.red(landStyle)/255.f, Color.green(landStyle)/255.f, Color.blue(landStyle)/255.f, Color.alpha(landStyle)/255.f);
				landInfo.setFilled(true);
				compObjs.add(maplyControl.addVectors(group.vecs, landInfo));
			}
		}		
	}

	void styleWater(VectorObject water,List<ComponentObject> compObjs)
	{
		if (water == null)
			return;

		// Filled water
		VectorInfo waterInfo = new VectorInfo();
		waterInfo.setFilled(true);
		waterInfo.setColor(137.f/255.f,188.f/255.f,228.f/255.f,1.f);
		waterInfo.setDrawPriority(200);
		ComponentObject compObj = maplyControl.addVector(water, waterInfo);
		compObjs.add(compObj);
	}

	// The connection task loaded data.  Yay!
	void didLoad(final QuadPagingLayer layer,final MaplyTileID tileID,final String json,final boolean wasCached)
	{
//		Log.i("OSMVectorTilePager","Loaded Tile : " + tileID.level + " (" + tileID.x + "," + tileID.y + ")");

		// Do the merge (which can take a while) on one of our thread pool threads
		executor.execute(
				new Runnable()
				{
					@Override
					public void run()
					{
						// Write it out to the cache
						if (!wasCached)
						{
							String tileFileName = makeTileFileName(tileID);
							writeToFile(tileFileName,json);
						}
						
						// Parse the GeoJSON assembly into groups based on the type
						Map<String,VectorObject> vecData = VectorObject.FromGeoJSONAssembly(json);

						// Work through the various top level types
						ArrayList<ComponentObject> compObjs = new ArrayList<ComponentObject>();
						styleRoads(vecData.get("highroad"),compObjs);
						styleRoadLabels(vecData.get("skeletron"),compObjs);
						styleBuildings(vecData.get("buildings"),compObjs);
						// Note: The land usage is kind of a mess
//						styleLandUsage(vecData.get("land-usages"),compObjs);
						styleWater(vecData.get("water-areas"),compObjs);
						
						layer.addData(compObjs, tileID);
						layer.tileDidLoad(tileID);
						
					}
				}
		);
	}
	
	// The connection task failed to load data.  Boo!
	void didNotLoad(final QuadPagingLayer layer,final MaplyTileID tileID)
	{
//		Log.i("OSMVectorTilePager","Failed Tile : " + tileID.level + " (" + tileID.x + "," + tileID.y + ")");

		layer.layerThread.addTask(
				new Runnable()
				{
					@Override
					public void run()
					{
						layer.tileFailedToLoad(tileID);
					}
				});
	}
	
	// Write a string to file
	// Courtesy: http://stackoverflow.com/questions/14376807/how-to-read-write-string-from-a-file-in-android
	private void writeToFile(String fileName,String data) {
	    try {
	        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(maplyControl.activity.openFileOutput(fileName, Context.MODE_PRIVATE));
	        outputStreamWriter.write(data);
	        outputStreamWriter.close();
	    }
	    catch (IOException e) {
//	        Log.e("Exception", "File write failed: " + e.toString());
	    } 
	}
	
	// Create a file into a string
	private String readFromFile(String inputFile) 
	{
	    String ret = "";

	    try {
	        InputStream inputStream = maplyControl.activity.openFileInput(inputFile);

	        if ( inputStream != null ) {
	            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
	            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
	            String receiveString = "";
	            StringBuilder stringBuilder = new StringBuilder();

	            while ( (receiveString = bufferedReader.readLine()) != null ) {
	                stringBuilder.append(receiveString);
	            }

	            inputStream.close();
	            ret = stringBuilder.toString();
	        }
	    }
	    catch (FileNotFoundException e) {
//	        Log.e("Maply", "File not found: " + e.toString());
	    } catch (IOException e) {
//	        Log.e("Maply", "Can not read file: " + e.toString());
	    }

	    return ret;
	}
}