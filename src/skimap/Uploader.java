package skimap;

import static org.junit.Assert.assertArrayEquals;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.recombee.api_client.RecombeeClient;
import com.recombee.api_client.api_requests.AddItemProperty;
import com.recombee.api_client.api_requests.AddUserProperty;
import com.recombee.api_client.api_requests.DeleteItemProperty;
import com.recombee.api_client.api_requests.SetItemValues;
import com.recombee.api_client.exceptions.ApiException;

/**
 * 
 * @author ivan
 *
 */
public class Uploader {

	private static RecombeeClient client;
	
	private static String RECOMBEE_TOKEN;
	
	private static String DB_NAME;
	
	private static final Logger logger = Logger.getLogger(Uploader.class.getName());
	
	static {
	    FileHandler fh;  
	    try {  
	        fh = new FileHandler("uploader-logs.log");  
	        logger.addHandler(fh);
	        SimpleFormatter formatter = new SimpleFormatter();  
	        fh.setFormatter(formatter);  
	    } catch (SecurityException e) {  
	        e.printStackTrace();  
	    } catch (IOException e) {  
	        e.printStackTrace();  
	    }  
	}
	
	static {
		Properties properties = new Properties();
        try {
			properties.load(new FileInputStream("local.properties"));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
        
        // init Recombee client
        RECOMBEE_TOKEN = properties.getProperty("RECOMBEE_TOKEN");
        DB_NAME = properties.getProperty("DB_NAME");
        client = new RecombeeClient(DB_NAME, RECOMBEE_TOKEN);
        
        // setup properties for ski resort items
//        try {
//        	client.send(new AddItemProperty("name", "string"));
//        	client.send(new AddItemProperty("lat", "string"));
//        	client.send(new AddItemProperty("lng", "string"));
//        	client.send(new AddItemProperty("officialWebsite", "string"));
//        	client.send(new AddItemProperty("terrainPark", "boolean"));
//        	client.send(new AddItemProperty("nightSkiing", "boolean"));
//        	client.send(new AddItemProperty("skiMapUrl", "string"));
//        	client.send(new AddItemProperty("region", "string"));
//        	client.send(new AddItemProperty("lastUpdated", "timestamp"));
//        	client.send(new AddItemProperty("top", "int"));
//        	client.send(new AddItemProperty("longestRun", "int"));
//          client.send(new AddItemProperty("liftCount", "int"));
//		} catch (ApiException e) {
//			e.printStackTrace();
//			logger.info("properties have been already setup!");
//		}
	}
	public static DocumentBuilderFactory domFactory;
	
	public static DocumentBuilder builder;
	
	static {
		domFactory = DocumentBuilderFactory.newInstance();
		domFactory.setNamespaceAware(true);
	}
	
	private static final String URL_SKI_INDEX = "https://skimap.org/SkiAreas/index.xml";
	private static final String URL_SKI_AREA_BASE = "https://skimap.org/SkiAreas/view/";
	private static final String URL_SKI_MAP_BASE = "https://skimap.org/SkiMaps/view/";
	private static SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+hh:MM");
	
	
	
	private static void fetchSkiMaps() throws Exception {
		// get list index from xml ski index 
		// create list with those that <region> == italy
		List<String> skiAreaIdList = getSkiAreaIds();
		
		// iterate list and fetch jsons from ski areas
		for (String id: skiAreaIdList) {
			try {
				// get json
				logger.info("Fetching ski area with id: " + id);
				URL url = new URL(URL_SKI_AREA_BASE + id + ".json");
				JSONObject json = new JSONObject(
						IOUtils.toString(url, Charset.forName("UTF-8")));
				
				// get map of values
				Map<String, Object> values = getValuesFromJson(json);
				
				// fetch ski map image url from ski maps
				String skiMap = getSkiMapImageUrl(json);
				values.put("skiMapUrl", skiMap);

				logger.info(values.toString());
				
				client.send(new SetItemValues(id, values)
						  .setCascadeCreate(true)
						);
				
			} catch(Exception e) {
				logger.info(e.getMessage());
				e.printStackTrace();
			}
		}
	}
	
	
	private static String getSkiMapImageUrl(JSONObject json) throws Exception {
		String skiMapId = json.getJSONArray("ski_maps")
				.getJSONObject(0).getString("id");
		String skiMapImageUrl = "";
		
		// get json
		builder = domFactory.newDocumentBuilder();
		URL url = new URL(URL_SKI_MAP_BASE + skiMapId + ".xml");
		InputStream stream = url.openStream();
		Document doc = builder.parse(stream);
		
		// select the render url attribute 
		XPath xpath = XPathFactory.newInstance().newXPath();
		boolean isFetched = false;
		try {
			XPathExpression expr = xpath.compile(
					"/skiMap/render[@url]");
			Node result = (Node) expr.evaluate(doc, XPathConstants.NODE);
			skiMapImageUrl = result.getAttributes()
					.getNamedItem("url").getNodeValue();
			isFetched = true;
		} catch(Exception e) {
			logger.info("couldn't retrieve render attribute for ski map id "
															+ skiMapId);
		}
		if ( !isFetched) {
			try {
			XPathExpression expr = xpath.compile(
					"/skiMap/unprocessed[@url]");
			Node result = (Node) expr.evaluate(doc, XPathConstants.NODE);
			skiMapImageUrl = result.getAttributes()
					.getNamedItem("url").getNodeValue();
			} catch(Exception e) {
				logger.info("couldn't retrieve unprocessed attribute for "
											+ "ski map id "+ skiMapId);
			}
		}
		
		return skiMapImageUrl;
	}

	private static Map<String, Object> getValuesFromJson(JSONObject json) {
		Map<String, Object> values = new HashMap<>();
		try {			
			// 1. name - must be present
			String propertyName = "name";
			String name = json.getString(propertyName);
			values.put(propertyName, name);
			
			// 2. officialWebsite 
			propertyName = "officialWebsite";
			if (json.has("official_website")) {
				String officialWebsite = json.getString("official_website");
				values.put(propertyName, officialWebsite);
			} 
			
			// 3. region - must be present
			propertyName = "region";
			if (json.has("regions")) {
				String region = json.getJSONArray("regions")
								.getJSONObject(0).getString("name");
				values.put(propertyName, region);
			}
			// 4. liftCount
			propertyName = "liftCount";
			if (json.has("lift_count")) {
				int liftCount = json.getInt("lift_count");
				values.put(propertyName, liftCount);
			}
			
			// 5. longestRun
			propertyName = "longestRun";
			if (json.has("longest_run")) {
				int longestRun = json.getInt("longest_run");
				values.put(propertyName, longestRun);
			}
			
			// 6. top
			propertyName = "top";
			if (json.has("top_elevation")) {
				int top = json.getInt("top_elevation");
				values.put(propertyName, top);
			}

			// 7. drop
			// drop is not present in Json, only in XML
//			propertyName = "drop";
//			if (json.has(propertyName)) {
//				int drop = json.getInt(propertyName);
//				values.put(propertyName, drop);
//			}
			
			// 8. lat - attribute ???
			propertyName = "lat";
			if (json.has("latitude")) { 
				String lat = Double.toString(json.getDouble("latitude"));
				values.put(propertyName, lat);
			}
			// 9. lng
			propertyName = "lng";
			if (json.has("longitude")) { 
				String lng = Double.toString(json.getDouble("longitude"));
				values.put(propertyName, lng);
			}
			
			// 10. lastUpdated - must be present
			propertyName = "lastUpdated";
			if (json.has("created")) {
		        try {
		            Date lastUpdated = formatter.parse(json.getString("created"));
		            values.put(propertyName, lastUpdated);
		        } catch (Exception e) {
					logger.info(e.getMessage());
					e.printStackTrace();
				}
				
			} else {
				Date lastUpdated = new Date();
				values.put(propertyName, lastUpdated);
			}
			// 11. terrainPark
			propertyName = "terrainPark";
			if (json.has("terrain_park")) {
				String terrainParkS = json.getString("terrain_park");
				if (terrainParkS.equals("Yes")) {
					values.put(propertyName, true);
				} else {
					values.put(propertyName, false);
				}
			} else {
				values.put(propertyName, false);
			}

			// 12. nightSkiing
			propertyName = "nightSkiing";
			if (json.has("night_skiing")) {
				String nightSkiingS = json.getString("night_skiing");
				if (nightSkiingS.equals("Yes")) {
					values.put(propertyName, true);
				} else {
					values.put(propertyName, false);
				}
			} else {
				values.put(propertyName, false);
			}
			
		} catch (Exception e) {
			logger.info(e.getMessage());
			e.printStackTrace();
		}
		
		return values;
	}

	/**
	 * 
	 * @return
	 * @throws Exception
	 */
	private static List<String> getSkiAreaIds() throws Exception{
		/// download xml from index
			builder = domFactory.newDocumentBuilder();
			URL url = new URL(URL_SKI_INDEX);
			logger.info("url created for index of ski");
			InputStream stream = url.openStream();
			logger.info("opened stream for ski index");
			Document doc = builder.parse(stream);
//			String xmlPath = "index.xml";
//			Document doc = builder.parse(xmlPath);
			
			// select with xpath region id == 86 (italy)
			XPath xpath = XPathFactory.newInstance().newXPath();
			XPathExpression expr = xpath.compile(
						"/skiAreas/skiArea[descendant::region[@id='86']]");
			
			Object result = expr.evaluate(doc, XPathConstants.NODESET);
			NodeList nodes = (NodeList) result;
			
			// select skiarea ids
			List<String> skiAreaIdList = new ArrayList<>();
			for (int i = 0; i < nodes.getLength(); i++){
				NamedNodeMap nl = nodes.item(i).getAttributes();
				for (int j = 0; j < nl.getLength(); j ++) {
					String s = nl.getNamedItem("id").getTextContent();
					skiAreaIdList.add(s);
				}
			}
			logger.info("Total number of ski resorts fetched:" + 
						Integer.toString(skiAreaIdList.size()));
			return skiAreaIdList;
	}

	
	
	public static void main(String[] args) throws Exception {
		fetchSkiMaps();
	}
	
	
	
	
	
	
	
	
}
